package cz.loplex.timebraid.plan

/**
 * The braid a pass over the **whole graph** would produce: [TopoOrder] over every commit, filtered
 * down to the mainline first-parent chains.
 *
 * This is the far end of [BraidInterleave]'s scope: naming every ref through `--interleave-ref` puts
 * the whole graph in scope, and the braid should then match this exactly. Written independently, so
 * the tests can assert that end of the spectrum rather than reason about it — and so the difference
 * from the default end stays visible as an assertion: a whole-graph pass holds a merge back until
 * every one of its parents has been emitted, including a merged-in side branch's tip, and that moves
 * where the merge lands relative to another repository's commits.
 */
object WholeGraphBraid {

    fun compute(graph: CommitGraph, heads: List<Commit>): List<Commit> {
        val onBraid = HashSet<Commit>()
        for (head in heads) {
            var commit: Commit? = head
            while (commit != null && onBraid.add(commit)) {
                commit = commit.firstParent
            }
        }

        return graph.topologicalOrder().filter { it in onBraid }
    }
}
