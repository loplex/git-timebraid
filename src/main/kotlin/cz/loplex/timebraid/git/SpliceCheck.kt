package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId

/**
 * Answers, before anything is written into the output, whether the repository at the output root
 * has something in the way of another input's destination.
 *
 * The root repository's tree cannot be an entry of the output's, so its top-level entries are
 * spliced in beside the other destinations (see [TreeAssembler]), and a destination reaching into
 * one of its directories is placed inside that directory. So an entry at the destination itself
 * collides with the input, and an entry on the way to it that is not a directory leaves no room for
 * it. Whether either happens is a question about a tree, and the root repository's tree is
 * different at every commit: the answer can be yes at one point of the braid and no at another,
 * and it matters only where the other input has content. Left to the writer alone it surfaces as a
 * run that has created the output, fetched every input into it and written part of the braid
 * before it stops, which is why it is asked here instead: one pass over the plan, no objects
 * written, and the writer's answer.
 *
 * The pass is cheap because the trees repeat. The root repository stands still for as many braid
 * positions as the other inputs have commits, and each destination is walked once per *distinct*
 * tree.
 */
class SpliceCheck(
    private val plan: MergePlan,
    private val inputs: BraidInputs,
    private val repoOf: Map<Source, SourceRepository>,
) {

    /**
     * Refuses the plan at the first commit where an input's destination meets something in the root
     * repository's tree, naming both; returns when there is none, or no repository at the root.
     */
    fun check() {
        val root = plan.graph.sources.firstOrNull { plan.subdirOf(it) == null } ?: return
        val repo = repoOf.getValue(root)
        val checked = HashSet<Pair<ObjectId, String>>()
        for (planned in plan.commits) {
            val content = plan.contentOf(planned.commit)
            val holder = content[root] ?: continue
            val tree = treeOf(holder)
            for (source in content.keys) {
                val subdir = plan.subdirOf(source) ?: continue
                if (!checked.add(tree to subdir)) continue
                val found = collisionAt(repo, tree, subdir) ?: continue
                throw IllegalArgumentException(
                    "$found at ${planned.commit} -- give that repository another subdirectory with " +
                        "<repo>::=<subdir>"
                )
            }
        }
    }

    /**
     * What is in the way of [subdir] in the root repository's [tree], walked as the writer walks
     * it, or `null` when nothing is: a segment the root repository does not have ends the walk,
     * since the braid builds the directory there itself; one above the destination has to be a
     * directory; the destination itself has to be free.
     */
    private fun collisionAt(repo: SourceRepository, tree: ObjectId, subdir: String): String? {
        val segments = subdir.split('/')
        var current = tree
        var path = ""
        for ((depth, segment) in segments.withIndex()) {
            val here = if (path.isEmpty()) segment else "$path/$segment"
            val entry = repo.entriesOf(current).firstOrNull { it.name == segment } ?: return null
            if (depth == segments.lastIndex) {
                return "subdirectory '$here' collides with an entry of the same name in the root " +
                    "repository"
            }
            if (entry.mode != FileMode.TREE) {
                return "'$here' is not a directory in the root repository, so no repository can be " +
                    "placed inside it"
            }
            current = entry.id
            path = here
        }
        return null
    }

    /** The original root tree of [commit], which is what its content is in the output. */
    private fun treeOf(commit: Commit): ObjectId =
        inputs.commits[commit]?.tree ?: error("$commit is not a commit of these inputs")
}
