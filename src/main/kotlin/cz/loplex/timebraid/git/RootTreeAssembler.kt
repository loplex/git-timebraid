package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.TreeFormatter

/** One entry of a git tree: a name, a mode, and the object it points at. */
class TreeEntry(val name: String, val mode: FileMode, val id: ObjectId)

/**
 * Builds the root tree of a braided commit: one entry per input repository that already has content
 * at this point in the braid, each pointing straight at that repository's own original tree.
 *
 * This is the tree rule of the README made concrete. Nothing is recursed into and no blob is
 * rewritten — a subdirectory entry *is* the original repository's root tree object, which is why the
 * output shares its content objects with the inputs and why writing costs one small tree per commit
 * rather than a copy of the whole worktree.
 *
 * The repository placed at the output root (`--root-repo`) is the one exception among the inputs:
 * its tree cannot be an entry, so its top-level entries are spliced in beside the subdirectories.
 * The root `.gitmodules` (see [assemble]) is the one entry that is no input's at all.
 */
class RootTreeAssembler(private val inserter: ObjectInserter) {

    /** Distinct root trees written so far, keyed by their entry list. */
    private val cache = HashMap<String, ObjectId>()

    /** Number of distinct root trees actually inserted, for reporting. */
    var treesWritten: Int = 0
        private set

    /**
     * @param rootEntries top-level entries of the root repository's tree, empty when there is no
     *   `--root-repo` or it has no content yet.
     * @param subdirEntries one entry per other repository with content, name = its subdirectory.
     * @param gitmodules the `.gitmodules` [SubmoduleWiring] built for this commit, or `null` when no
     *   input describes a submodule here. It replaces the root repository's own entry of that name
     *   rather than colliding with it. Where that entry is a file, this one already holds its
     *   sections; a symlink or a tree of that name is not read as one, and is dropped.
     * @param at describes the commit being built, used only to make a collision error locatable.
     */
    fun assemble(
        rootEntries: List<TreeEntry>,
        subdirEntries: List<TreeEntry>,
        gitmodules: ObjectId? = null,
        at: () -> String,
    ): ObjectId {
        val byName = LinkedHashMap<String, TreeEntry>(rootEntries.size + subdirEntries.size)
        for (entry in rootEntries) byName[entry.name] = entry
        for (entry in subdirEntries) {
            val clash = byName.put(entry.name, entry)
            require(clash == null) {
                "subdirectory '${entry.name}' collides with an entry of the same name in the root " +
                    "repository at ${at()} — give that repository another subdirectory with " +
                    "<repo>=<subdir>"
            }
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
