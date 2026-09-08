package cz.loplex.timebraid.plan

/**
 * A graph's edges in the flat form the walks want: both directions as a pair of arrays each,
 * addressed by the graph's own numbering.
 *
 * Filled in a **single pass** over the nodes, so [Graph.parentsOf] is asked about each node exactly
 * once. That matters beyond the pointer chasing: a graph that filters its edges — the braid's scope
 * does — allocates a list per node to answer, and a second pass would pay for it twice. The parents
 * land in a flat array as they arrive and the children are counted on the way past, so reversing
 * them afterwards never leaves the ints.
 *
 * The alternative, one `IntArray` of parent indices per node, is one object per node of short-lived
 * garbage: 26 MB per build over a 600k-commit history, three builds to a plan.
 */
internal class Adjacency<T : Any> private constructor(
    /** Where each node's parents start in [parentTargets]; one longer than the index space. */
    val parentStarts: IntArray,
    val parentTargets: IntArray,
    /** Where each node's children start in [childTargets]. */
    val childStarts: IntArray,
    val childTargets: IntArray,
    /** Parents per node — a walk's initial in-degrees, to be copied before they are counted down. */
    val parentCounts: IntArray,
    /** The node at each index, `null` where the index belongs to a node outside this graph. */
    val nodeAt: List<T?>,
) {

    companion object {

        fun <T : Any> of(graph: IndexedGraph<T>): Adjacency<T> {
            val space = graph.indexSpace
            val parentCounts = IntArray(space)
            val childStarts = IntArray(space + 1)
            val nodeAt = MutableList<T?>(space) { null }
            var parentTargets = IntArray(graph.nodes.size + graph.nodes.size / 8 + 1)
            var edges = 0
            var previous = -1

            for (node in graph.nodes) {
                val at = graph.indexOf(node)
                require(at in 0 until space) { "$node is numbered $at, outside 0..${space - 1}" }
                require(at > previous) { "$node is numbered $at after $previous — the nodes are out of index order" }
                previous = at
                nodeAt[at] = node

                val parents = graph.parentsOf(node)
                parentCounts[at] = parents.size
                if (edges + parents.size > parentTargets.size) {
                    parentTargets = parentTargets.copyOf(maxOf(parentTargets.size * 2, edges + parents.size))
                }
                for (parent in parents) {
                    val parentAt = graph.indexOf(parent)
                    require(parentAt in 0 until space) { "$parent is numbered $parentAt, outside 0..${space - 1}" }
                    parentTargets[edges++] = parentAt
                    childStarts[parentAt + 1]++
                }
            }

            val parentStarts = IntArray(space + 1)
            for (at in 0 until space) parentStarts[at + 1] = parentStarts[at] + parentCounts[at]

            // Every edge has to land on a node of this graph. A parent from outside would hold its
            // child back for ever and be reported as a cycle that is not there, so say so instead.
            for (edge in 0 until edges) {
                if (nodeAt[parentTargets[edge]] == null) {
                    var child = 0
                    while (parentStarts[child + 1] <= edge) child++
                    error("${nodeAt[child]} names a parent that is not among the nodes to walk")
                }
            }

            for (at in 0 until space) childStarts[at + 1] += childStarts[at]
            val cursor = childStarts.copyOf(space)
            val childTargets = IntArray(edges)
            for (at in 0 until space) {
                for (edge in parentStarts[at] until parentStarts[at + 1]) {
                    childTargets[cursor[parentTargets[edge]]++] = at
                }
            }

            return Adjacency(parentStarts, parentTargets, childStarts, childTargets, parentCounts, nodeAt)
        }
    }
}
