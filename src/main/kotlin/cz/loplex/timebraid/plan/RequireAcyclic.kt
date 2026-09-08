package cz.loplex.timebraid.plan

/** Thrown when a set of parent edges that is required to be a DAG turns out to contain a cycle. */
class CyclicGraphException(message: String) : IllegalStateException(message)

/**
 * Verifies that the edges [parentsOf] reports over [nodes] contain no cycle, and throws
 * [CyclicGraphException] naming the offending chain if they do.
 *
 * This runs in production, not only in tests. Reparenting adds an edge from each braid commit to its
 * predecessor in the braid sequence; as long as that sequence is a valid topological order, no cycle
 * can arise. So a cycle here means the ordering contradicted ancestry, and the only safe response is
 * to fail loudly before a single object is written.
 *
 * Iterative depth-first search — the first-parent chain of a real repository is tens of thousands of
 * commits deep, which recursion would not survive. It walks a numbering of its own, derived here and
 * never leaving, for the same reason [KahnOrder] derives one: colouring nodes and unwinding a stack
 * wants flat arrays rather than a graph of objects. A caller passes nodes and names them by what they
 * print as.
 *
 * @param nodes the nodes to check; every parent [parentsOf] names has to be among them.
 * @param parentsOf the edges to check, which are not always the nodes' own — reparenting checks the
 *   braided edges it has just derived, before anything is written from them.
 */
internal fun <T : Any> requireAcyclic(nodes: List<T>, parentsOf: (T) -> List<T>) {
    val white: Byte = 0
    val gray: Byte = 1
    val black: Byte = 2

    val size = nodes.size
    val indices = HashMap<T, Int>(size * 2)
    nodes.forEachIndexed { index, node -> indices[node] = index }
    val parents = Array(size) { index ->
        val row = parentsOf(nodes[index])
        IntArray(row.size) { at ->
            indices[row[at]] ?: error("${row[at]} is named as a parent but is not among the nodes to check")
        }
    }

    val color = ByteArray(size)
    val stackNode = IntArray(size)
    val stackNextParent = IntArray(size)

    for (root in 0 until size) {
        if (color[root] != white) continue
        var top = 0
        stackNode[0] = root
        stackNextParent[0] = 0
        color[root] = gray
        while (top >= 0) {
            val node = stackNode[top]
            val parentsOfNode = parents[node]
            if (stackNextParent[top] < parentsOfNode.size) {
                val parent = parentsOfNode[stackNextParent[top]++]
                when (color[parent]) {
                    white -> {
                        color[parent] = gray
                        top++
                        stackNode[top] = parent
                        stackNextParent[top] = 0
                    }
                    gray -> throw CyclicGraphException(cycleMessage(stackNode, top, parent, nodes))
                    else -> Unit
                }
            } else {
                color[node] = black
                top--
            }
        }
    }
}

private fun <T : Any> cycleMessage(
    stackNode: IntArray,
    top: Int,
    closing: Int,
    nodes: List<T>,
): String {
    var from = top
    while (from > 0 && stackNode[from] != closing) from--
    val chain = (from..top).joinToString(" -> ") { nodes[stackNode[it]].toString() }
    return "cycle in the parent chain: $chain -> ${nodes[closing]}"
}
