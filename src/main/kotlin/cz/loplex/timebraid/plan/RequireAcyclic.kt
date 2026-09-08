package cz.loplex.timebraid.plan

/** Thrown when a set of parent edges that is required to be a DAG turns out to contain a cycle. */
class CyclicGraphException(message: String) : IllegalStateException(message)

/**
 * Verifies that [graph] contains no cycle, and throws [CyclicGraphException] naming the offending
 * chain if it does.
 *
 * This runs in production, not only in tests. Reparenting adds an edge from each braid commit to its
 * predecessor in the braid sequence; as long as that sequence is a valid topological order, no cycle
 * can arise. So a cycle here means the ordering contradicted ancestry, and the only safe response is
 * to fail loudly before a single object is written. The graph checked is not always the commits' own
 * — reparenting checks the braided edges it has just derived, before anything is written from them.
 *
 * Iterative depth-first search — the first-parent chain of a real repository is tens of thousands of
 * commits deep, which recursion would not survive. Colouring nodes and unwinding a stack wants flat
 * arrays, which is what the graph's own numbering gives it. A caller passes a graph and the cycle is
 * named by what its nodes print as.
 *
 */
internal fun <T : Any> requireAcyclic(graph: IndexedGraph<T>) {
    val white: Byte = 0
    val gray: Byte = 1
    val black: Byte = 2

    val space = graph.indexSpace
    val nodeAt = MutableList<T?>(space) { null }
    val parents = arrayOfNulls<IntArray>(space)
    for (node in graph.nodes) {
        val at = graph.indexOf(node)
        nodeAt[at] = node
        val row = graph.parentsOf(node)
        parents[at] = IntArray(row.size) { graph.indexOf(row[it]) }
    }

    val color = ByteArray(space)
    val stackNode = IntArray(graph.nodes.size + 1)
    val stackNextParent = IntArray(graph.nodes.size + 1)

    for (node in graph.nodes) {
        val root = graph.indexOf(node)
        if (color[root] != white) continue
        var top = 0
        stackNode[0] = root
        stackNextParent[0] = 0
        color[root] = gray
        while (top >= 0) {
            val at = stackNode[top]
            val parentsOfNode = parents[at]!!
            if (stackNextParent[top] < parentsOfNode.size) {
                val parent = parentsOfNode[stackNextParent[top]++]
                when (color[parent]) {
                    white -> {
                        color[parent] = gray
                        top++
                        stackNode[top] = parent
                        stackNextParent[top] = 0
                    }
                    gray -> throw CyclicGraphException(cycleMessage(stackNode, top, parent, nodeAt))
                    else -> Unit
                }
            } else {
                color[at] = black
                top--
            }
        }
    }
}

private fun <T : Any> cycleMessage(
    stackNode: IntArray,
    top: Int,
    closing: Int,
    nodeAt: List<T?>,
): String {
    var from = top
    while (from > 0 && stackNode[from] != closing) from--
    val chain = (from..top).joinToString(" -> ") { nodeAt[stackNode[it]].toString() }
    return "cycle in the parent chain: $chain -> ${nodeAt[closing]}"
}
