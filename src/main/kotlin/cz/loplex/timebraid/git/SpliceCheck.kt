package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.ObjectId

/**
 * Answers, before anything is written into the output, whether the repository at the output root
 * has something in the way of another input's subdirectory.
 *
 * The root repository's tree cannot be an entry of the output's, so its top-level entries are
 * spliced in beside the subdirectories (see [RootTreeAssembler]), and one named like an input's
 * subdirectory collides with it. Whether one does is a question about a tree, and the root
 * repository's tree is different at every commit: the answer can be yes at one point of the braid
 * and no at another, and it matters only where the other input has content. Left to the writer alone
 * it surfaces as a run that has created the output, fetched every input into it and written part of
 * the braid before it stops, which is why it is asked here instead: one pass over the plan, no
 * objects written, and the writer's answer.
 *
 * The pass is cheap because the trees repeat. The root repository stands still for as many braid
 * positions as the other inputs have commits, and its entries are read once per *distinct* tree.
 */
class SpliceCheck(
    private val plan: MergePlan,
    private val inputs: BraidInputs,
    private val repoOf: Map<Source, SourceRepository>,
) {

    /**
     * Refuses the plan at the first commit where an input's subdirectory meets an entry of the root
     * repository's tree, naming both; returns when there is none, or no repository at the root.
     */
    fun check() {
        val root = plan.graph.sources.firstOrNull { plan.subdirOf(it) == null } ?: return
        val names = HashMap<ObjectId, Set<String>>()
        for (planned in plan.commits) {
            val content = plan.contentOf(planned.commit)
            val holder = content[root] ?: continue
            val tree = treeOf(holder)
            val entries = names.getOrPut(tree) {
                repoOf.getValue(root).topLevelEntries(tree).mapTo(HashSet()) { it.name }
            }
            for (source in content.keys) {
                val subdir = plan.subdirOf(source) ?: continue
                require(subdir !in entries) {
                    "subdirectory '$subdir' collides with an entry of the same name in the root " +
                        "repository at ${planned.commit} -- give that repository another " +
                        "subdirectory with <repo>=<subdir>"
                }
            }
        }
    }

    /** The original root tree of [commit], which is what its content is in the output. */
    private fun treeOf(commit: Commit): ObjectId =
        inputs.commits[commit]?.tree ?: error("$commit is not a commit of these inputs")
}
