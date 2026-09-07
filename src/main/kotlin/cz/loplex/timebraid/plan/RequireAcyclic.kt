package cz.loplex.timebraid.plan

/** Thrown when a set of parent edges that is required to be a DAG turns out to contain a cycle. */
class CyclicGraphException(message: String) : IllegalStateException(message)

/**
 * Verifies that [parents] contains no cycle, and throws [CyclicGraphException] naming the offending
 * chain if it does.
 *
 * This runs in production, not only in tests. Reparenting adds an edge from each braid commit to its
 * predecessor in the braid sequence; as long as that sequence is a valid topological order, no cycle
 * can arise. So a cycle here means the ordering contradicted ancestry, and the only safe response is
 * to fail loudly before a single object is written.
 *
 * Iterative depth-first search — the first-parent chain of a real repository is tens of thousands of
 * commits deep, which recursion would not survive.
 */
internal fun requireAcyclic(parents: Array<IntArray>, describe: (Int) -> String = { "#$it" }) {
    val white: Byte = 0
    val gray: Byte = 1
    val black: Byte = 2

    val size = parents.size
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
                    gray -> throw CyclicGraphException(cycleMessage(stackNode, top, parent, describe))
                    else -> Unit
                }
            } else {
                color[node] = black
                top--
            }
        }
    }
}

private fun cycleMessage(
    stackNode: IntArray,
    top: Int,
    closing: Int,
    describe: (Int) -> String,
): String {
    var from = top
    while (from > 0 && stackNode[from] != closing) from--
    val chain = (from..top).joinToString(" -> ") { describe(stackNode[it]) }
    return "cycle in the parent chain: $chain -> ${describe(closing)}"
}
