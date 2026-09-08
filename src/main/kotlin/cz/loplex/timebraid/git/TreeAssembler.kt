package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.TreeFormatter

/** One entry of a git tree: a name, a mode, and the object it points at. */
class TreeEntry(val name: String, val mode: FileMode, val id: ObjectId)

/**
 * One input's content and where in the output it lands.
 *
 * The path is held split because a git tree entry name cannot contain a `/`: a nested destination
 * (`libs/backend`) is not one entry but one entry per segment, and only the last of them is the
 * input's own tree. An empty path is the repository placed at the output root, whose content lands
 * beside every other input's rather than in a directory of its own.
 *
 * [entriesOf] is how the input's own content is read when something else is placed inside it — see
 * the splice in [TreeAssembler]. Each placement brings its own because it is asked about that
 * input's trees and no other's, and because the input it belongs to is the only thing that knows
 * which repository those trees live in.
 */
class Placement(
    /** The subdirectory path, segment by segment; empty for the repository at the output root. */
    val path: List<String>,
    /** The input's own root tree, which becomes the tree at [path]. */
    val tree: ObjectId,
    /** Entries of one of this input's trees — [tree] itself, or any tree below it. */
    val entriesOf: (ObjectId) -> List<TreeEntry>,
) {

    /** [subdir] as the planner holds it: a `/`-separated path, or `null` for the output root. */
    constructor(subdir: String?, tree: ObjectId, entriesOf: (ObjectId) -> List<TreeEntry>) :
        this(subdir?.split('/') ?: emptyList(), tree, entriesOf)

    init {
        require(path.none { it.isEmpty() }) {
            "'${path.joinToString("/")}' is not a subdirectory path"
        }
    }
}

/**
 * Builds the trees of a braided commit: one entry per input repository that already has content at
 * this point in the braid, at its own destination — pointing straight at that repository's own
 * original tree where the destination is one segment, and at a tree this class built where it is
 * nested.
 *
 * This is the tree rule of doc/how-it-works.md made concrete. Nothing is recursed into and no blob is
 * rewritten — the entry at an input's destination *is* the original repository's root tree object,
 * which is why the output shares its content objects with the inputs and why writing costs a handful
 * of small trees per commit rather than a copy of the whole worktree.
 *
 * A destination may be nested (`libs/backend`), which is where the "handful" comes from: the
 * segments above the last one are trees this class builds, and two inputs under one prefix share the
 * tree for it.
 *
 * One case breaks the "nothing is recursed into" rule, and it is the only one: an input placed
 * *inside* another. That is the repository at the output root, which every other destination is
 * inside of, and it is equally any input whose destination is a prefix of another's. There the two
 * are **spliced** — the containing repository's tree at that path is read into entries
 * ([Placement.entriesOf]) and the input placed inside it goes in beside them. The descent follows
 * only the prefixes some destination names, and only down the containing repository's own trees.
 *
 * Whether one destination may contain another at all is not decided here but by the planner; what
 * is refused here is what only the trees can show, and only for the commit they show it at: an
 * input landing where the repository around it already has an entry of that name, or reaching
 * through one that is not a directory.
 */
class TreeAssembler(private val inserter: ObjectInserter) {

    /** Distinct trees written so far, keyed by their entry list. */
    private val cache = HashMap<String, ObjectId>()

    /** Number of distinct trees actually inserted, for reporting. */
    var treesWritten: Int = 0
        private set

    /**
     * @param placements one per repository that has content at this commit, each at its own
     *   destination; at most one of them at the output root.
     * @param gitmodules the `.gitmodules` [SubmoduleWiring] built for this commit, or `null` when no
     *   input describes a submodule here. It replaces the root repository's own entry of that name
     *   rather than colliding with it. Where that entry is a file, this one already holds its
     *   sections; a symlink or a tree of that name is not read as one, and is dropped.
     * @param at describes the commit being built, used only to make a collision error locatable.
     */
    fun assemble(
        placements: List<Placement>,
        gitmodules: ObjectId? = null,
        at: () -> String,
    ): ObjectId {
        val roots = placements.filter { it.path.isEmpty() }
        require(roots.size <= 1) { "two repositories are placed at the output root at ${at()}" }
        val root = roots.firstOrNull()
        return build(
            existing = root?.let { it.entriesOf(it.tree) } ?: emptyList(),
            entriesOf = root?.entriesOf ?: NOTHING_TO_DESCEND_INTO,
            cursors = placements.filter { it.path.isNotEmpty() }.map { Cursor(it, 0) },
            gitmodules = gitmodules,
            path = "",
            at = at,
        )
    }

    /**
     * One tree of the output: [existing] — what the repository containing this level holds here —
     * with every placement that reaches this level put in beside it. [entriesOf] reads further into
     * that same repository, for the level below.
     *
     * [gitmodules] belongs to the root of the output and is therefore only ever passed to the
     * outermost call.
     */
    private fun build(
        existing: List<TreeEntry>,
        entriesOf: (ObjectId) -> List<TreeEntry>,
        cursors: List<Cursor>,
        gitmodules: ObjectId?,
        path: String,
        at: () -> String,
    ): ObjectId {
        val byName = LinkedHashMap<String, TreeEntry>(existing.size + cursors.size)
        for (entry in existing) byName[entry.name] = entry

        for ((name, group) in cursors.groupByTo(LinkedHashMap()) { it.segment }) {
            val here = if (path.isEmpty()) name else "$path/$name"
            // The twin of the root check above: `assemble` promises one placement per repository
            // with content, each at its own destination, and two at one non-root path would have
            // the second silently dropped here rather than refused.
            require(group.count { it.isLeaf } <= 1) {
                "two repositories are placed at '$here' at ${at()}"
            }
            val landing = group.firstOrNull { it.isLeaf }
            val below = group.filterNot { it.isLeaf }

            if (landing != null && below.isEmpty()) {
                // The ordinary case, and the cheap one: the entry written here is the input's own
                // tree object, which is what keeps the output sharing objects with its inputs.
                val clash = byName.put(name, TreeEntry(name, FileMode.TREE, landing.placement.tree))
                require(clash == null) { collision(here, at) }
                continue
            }

            // Something is placed below this segment, so the tree here is one the braid builds
            // itself, over whatever already occupies the segment: the input landing here when one
            // does — that is the splice — and otherwise the containing repository's own directory
            // of that name.
            val inside: List<TreeEntry>
            val deeper: (ObjectId) -> List<TreeEntry>
            if (landing != null) {
                require(name !in byName) { collision(here, at) }
                inside = landing.placement.entriesOf(landing.placement.tree)
                deeper = landing.placement.entriesOf
            } else {
                val occupant = byName[name]
                require(occupant == null || occupant.mode == FileMode.TREE) {
                    "'$here' is not a directory in the repository that holds it at ${at()}, so no " +
                        "repository can be placed inside it -- give that repository another " +
                        "subdirectory with <repo>=<subdir>"
                }
                inside = if (occupant == null) emptyList() else entriesOf(occupant.id)
                deeper = entriesOf
            }
            val subtree = build(inside, deeper, below.map { it.descend() }, null, here, at)
            byName[name] = TreeEntry(name, FileMode.TREE, subtree)
        }

        if (gitmodules != null) {
            byName[Constants.DOT_GIT_MODULES] =
                TreeEntry(Constants.DOT_GIT_MODULES, FileMode.REGULAR_FILE, gitmodules)
        }

        val entries = byName.values.sortedWith(GIT_TREE_ORDER)
        val key = entries.joinToString(" ") { "${it.mode.bits}:${it.name}:${it.id.name}" }
        return cache.getOrPut(key) {
            val formatter = TreeFormatter(entries.size)
            for (entry in entries) formatter.append(entry.name, entry.mode, entry.id)
            treesWritten++
            inserter.insert(formatter)
        }
    }

    /** A [Placement] being walked segment by segment, with [depth] segments already behind it. */
    private class Cursor(val placement: Placement, private val depth: Int) {

        /** The segment this cursor sits on. */
        val segment: String get() = placement.path[depth]

        /** Whether the input's own tree is the entry for [segment], rather than a tree below it. */
        val isLeaf: Boolean get() = depth == placement.path.size - 1

        fun descend(): Cursor = Cursor(placement, depth + 1)
    }

    companion object {

        /**
         * Stands in for the containing repository's reader where there is no repository at the
         * output root. Never called in that case — with nothing at the root, every level this class
         * builds starts out empty and there is no entry to descend into — so reaching it means the
         * recursion carried entries it cannot account for.
         */
        private val NOTHING_TO_DESCEND_INTO: (ObjectId) -> List<TreeEntry> = {
            error("no repository at the output root to descend into")
        }

        private fun collision(here: String, at: () -> String): String =
            "subdirectory '$here' collides with an entry of the same name in the repository that " +
                "holds it at ${at()} -- give that repository another subdirectory with " +
                "<repo>=<subdir>"

        /**
         * The order git stores tree entries in, which [TreeFormatter] does not apply on its own:
         * plain byte order over the entry name, except that a directory name sorts as if it ended
         * in `/`. That is what puts `a.txt` before `a/` — `.` (0x2E) precedes `/` (0x2F) — and a
         * tree written in any other order is one `git fsck` rejects.
         *
         * A gitlink is not a directory as far as this rule is concerned: only [FileMode.TREE] gets
         * the trailing slash.
         */
        val GIT_TREE_ORDER: Comparator<TreeEntry> = Comparator { a, b ->
            compareBytes(sortKey(a), sortKey(b))
        }

        private fun sortKey(entry: TreeEntry): ByteArray {
            val raw = entry.name.toByteArray(Charsets.UTF_8)
            if (entry.mode != FileMode.TREE) return raw
            return raw.copyOf(raw.size + 1).also { it[raw.size] = '/'.code.toByte() }
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            val common = minOf(a.size, b.size)
            for (i in 0 until common) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }
    }
}
