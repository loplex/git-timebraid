package cz.loplex.timebraid.plan

/**
 * The braid a pass over the **whole graph** would produce: [TopoOrder] over every commit, filtered
 * down to the mainline first-parent chains.
 *
 * Not what the tool does — [BraidInterleave] decides the braid, over the mainline chains alone. This
 * exists so the tests can state the difference between the two as an assertion instead of a comment:
 * a whole-graph pass holds a merge back until every one of its parents has been emitted, including a
 * merged-in side branch's tip, and that changes where the merge lands relative to another
 * repository's commits.
 */
object WholeGraphBraid {

    fun compute(graph: CommitGraph, heads: IntArray): IntArray {
        val onBraid = BooleanArray(graph.size)
        var count = 0
        for (head in heads) {
            var commit = head
            while (commit != CommitGraph.NO_COMMIT && !onBraid[commit]) {
                onBraid[commit] = true
                count++
                commit = graph.firstParentOf(commit)
            }
        }

        val braid = IntArray(count)
        var next = 0
        for (commit in TopoOrder.compute(graph)) {
            if (onBraid[commit]) braid[next++] = commit
        }
        return braid
    }
}
