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
 * input's own tree.
 */
class Placement(
    /** The subdirectory path, segment by segment; never empty. */
    val path: List<String>,
    /** The input's own root tree, which becomes the tree at [path]. */
    val tree: ObjectId,
) {

    /** [subdir] as validated by the planner — a `/`-separated path of usable segments. */
    constructor(subdir: String, tree: ObjectId) : this(subdir.split('/'), tree)

    init {
        require(path.isNotEmpty() && path.none { it.isEmpty() }) {
            "'${path.joinToString("/")}' is not a subdirectory path"
        }
    }
}

/**
 * Builds the root tree of a braided commit: one entry per input repository that already has content
 * at this point in the braid, each pointing straight at that repository's own original tree.
 *
 * This is the tree rule of the README made concrete. Nothing is recursed into and no blob is
 * rewritten — the entry at an input's destination *is* the original repository's root tree object,
 * which is why the output shares its content objects with the inputs and why writing costs a handful
 * of small trees per commit rather than a copy of the whole worktree.
 *
 * A destination may be nested (`libs/backend`), which is where the "handful" comes from: the
 * segments above the last one are trees this class builds, and two inputs under one prefix share
 * the tree for it. The repository placed at the output root (`--root-repo`) contributes its own
 * top-level entries beside them, and where a nested destination reaches into a directory that
 * repository already has, the two are spliced: its entries at that path and the inputs placed
 * inside it end up in one tree.
 */
class RootTreeAssembler(
    private val inserter: ObjectInserter,
    /**
     * Entries of one tree of the repository placed at the output root, for the splice described
     * above. Called only for a directory of that repository an input is placed inside of, so the
     * common case — no `--root-repo`, or none of its directories shared with an input — never needs
     * one.
     */
    private val rootTreeEntries: (ObjectId) -> List<TreeEntry> = {
        error("no repository at the output root to descend into")
    },
) {

    /** Distinct trees written so far, keyed by their entry list. */
    private val cache = HashMap<String, ObjectId>()

    /** Number of distinct trees actually inserted, for reporting. */
    var treesWritten: Int = 0
        private set

    /**
     * @param rootEntries top-level entries of the root repository's tree, empty when there is no
     *   `--root-repo` or it has no content yet.
     * @param placements one per other repository with content, each at its own destination.
     * @param gitmodules the `.gitmodules` [SubmoduleWiring] built for this commit, or `null` when no
     *   input describes a submodule here. It replaces the root repository's own entry of that name
     *   rather than colliding with it, because it already holds that file's sections.
     * @param at describes the commit being built, used only to make a collision error locatable.
     */
    fun assemble(
        rootEntries: List<TreeEntry>,
        placements: List<Placement>,
        gitmodules: ObjectId? = null,
        at: () -> String,
    ): ObjectId = build(rootEntries, placements.map { Cursor(it, 0) }, gitmodules, "", at)

    /**
     * One tree of the output: [existing] — what the root repository holds at [path] — with every
     * placement that reaches this level put in beside it.
     *
     * [gitmodules] belongs to the root of the output and is therefore only ever passed to the
     * outermost call.
     */
    private fun build(
        existing: List<TreeEntry>,
        cursors: List<Cursor>,
        gitmodules: ObjectId?,
        path: String,
        at: () -> String,
    ): ObjectId {
        val byName = LinkedHashMap<String, TreeEntry>(existing.size + cursors.size)
        for (entry in existing) byName[entry.name] = entry

        for ((name, group) in cursors.groupByTo(LinkedHashMap()) { it.segment }) {
            val here = if (path.isEmpty()) name else "$path/$name"
            val landing = group.firstOrNull { it.isLeaf }
            if (landing != null) {
                // Two placements under one name where one of them ends here means one repository's
                // destination contains another's, which the planner rejects — nothing can be added
                // beside content that is a single tree object.
                require(group.size == 1) {
                    "'$here' holds one repository and contains another at ${at()}"
                }
                val clash = byName.put(name, TreeEntry(name, FileMode.TREE, landing.placement.tree))
                require(clash == null) {
                    "subdirectory '$here' collides with an entry of the same name in the root " +
                        "repository at ${at()} — give that repository another subdirectory with " +
                        "<repo>::=<subdir>"
                }
                continue
            }

            // Nothing ends here, so this segment is a directory of the output the braid builds
            // itself — over the root repository's own directory of that name where there is one.
            val inside = byName[name]
            require(inside == null || inside.mode == FileMode.TREE) {
                "'$here' is not a directory in the root repository at ${at()}, so no repository " +
                    "can be placed inside it — give that repository another subdirectory with " +
                    "<repo>::=<subdir>"
            }
            val children = if (inside == null) emptyList() else rootTreeEntries(inside.id)
            val subtree = build(children, group.map { it.descend() }, null, here, at)
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
