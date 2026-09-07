package cz.loplex.timebraid.plan

/**
 * Dense index of one commit inside a [CommitGraph].
 *
 * The planner works exclusively with dense indices: a commit is an `Int` in `0 until graph.size`,
 * and a set of commits is an `IntArray`. This type puts a name on that number where a single commit
 * crosses the boundary out of the planner ([PlannedCommit.commit]); the bulk structures stay
 * primitive arrays on purpose, because wrapping every array element would cost readability without
 * buying any type safety that the arrays themselves do not already have.
 */
@JvmInline
value class CommitId(val index: Int) {
    override fun toString(): String = "#$index"
}

/** Thrown when a set of parent edges that is required to be a DAG turns out to contain a cycle. */
class CyclicGraphException(message: String) : IllegalStateException(message)

/**
 * Immutable indexed DAG of every commit of every input repository (every *strand*, in the
 * vocabulary of the README).
 *
 * This is the planner's entire input. It deliberately knows nothing about git: a commit is an index,
 * its identity is an opaque string supplied by whoever built the graph, and its timestamp is a single
 * `Long` resolved once by the reader according to `--order-by`. The planner never learns whether it
 * got author or committer time, which keeps that choice a one-line decision outside this package.
 *
 * Parent edges point from a child to its parents, in their original order, so `parentsOf(c)[0]` is
 * the first parent. Edges are *not* checked for acyclicity when the graph is constructed — a graph
 * read out of a repository is a DAG by definition, and the one place where that can genuinely break
 * is reparenting, which checks explicitly (see [requireAcyclic] and [Reparenter]).
 */
class CommitGraph internal constructor(
    parents: Array<IntArray>,
    sourceIndex: IntArray,
    orderingTime: LongArray,
    commitIds: Array<String>,
    /** Names of the input repositories, indexed by source index. */
    val sourceNames: List<String>,
) {
    /** Number of commits; valid commit indices are `0 until size`. */
    val size: Int = parents.size

    private val edges: Array<IntArray> = Array(size) { parents[it].copyOf() }
    private val source: IntArray = sourceIndex.copyOf()
    private val time: LongArray = orderingTime.copyOf()
    private val ids: Array<String> = commitIds.copyOf()

    /** Number of input repositories. */
    val sourceCount: Int get() = sourceNames.size

    init {
        require(source.size == size) { "sourceIndex has ${source.size} entries, expected $size" }
        require(time.size == size) { "orderingTime has ${time.size} entries, expected $size" }
        require(ids.size == size) { "commitIds has ${ids.size} entries, expected $size" }
        for (commit in 0 until size) {
            require(source[commit] in sourceNames.indices) {
                "commit #$commit has source index ${source[commit]}, expected 0..${sourceCount - 1}"
            }
            val parentsOfCommit = edges[commit]
            for (i in parentsOfCommit.indices) {
                val parent = parentsOfCommit[i]
                require(parent in 0 until size) {
                    "commit #$commit has parent index $parent, expected 0..${size - 1}"
                }
                require(parent != commit) { "commit ${describe(commit)} is its own parent" }
                for (j in 0 until i) {
                    require(parentsOfCommit[j] != parent) {
                        "commit ${describe(commit)} lists parent ${describe(parent)} twice"
                    }
                }
            }
        }
    }

    /**
     * Parents of [commit], first parent first. The returned array is the graph's own storage and
     * must not be modified.
     */
    fun parentsOf(commit: Int): IntArray = edges[commit]

    /** First parent of [commit], or [NO_COMMIT] for a root commit. */
    fun firstParentOf(commit: Int): Int = if (edges[commit].isEmpty()) NO_COMMIT else edges[commit][0]

    /** Index of the input repository [commit] came from. */
    fun sourceOf(commit: Int): Int = source[commit]

    /** Name of the input repository [commit] came from. */
    fun sourceNameOf(commit: Int): String = sourceNames[source[commit]]

    /** Timestamp used to interleave the strands, as resolved by the reader. */
    fun timeOf(commit: Int): Long = time[commit]

    /** Opaque identity of [commit] — the original commit sha in production, a short name in tests. */
    fun idOf(commit: Int): String = ids[commit]

    /** Human-readable `<repository>/<id>`, used in diagnostics. */
    fun describe(commit: Int): String = "${sourceNames[source[commit]]}/${ids[commit]}"

    /**
     * A copy of this graph with a different set of parent edges and everything else unchanged.
     * Used to view the reparented history through the same API as the original one.
     */
    internal fun withParents(parents: Array<IntArray>): CommitGraph =
        CommitGraph(parents, source, time, ids, sourceNames)

    companion object {
        /** Returned where a commit index is expected but there is none (no parent, no content yet). */
        const val NO_COMMIT: Int = -1
    }
}

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
