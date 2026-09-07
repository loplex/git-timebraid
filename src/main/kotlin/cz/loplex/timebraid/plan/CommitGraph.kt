package cz.loplex.timebraid.plan

/** Thrown when a set of parent edges that is required to be a DAG turns out to contain a cycle. */
class CyclicGraphException(message: String) : IllegalStateException(message)

/**
 * One input repository — one *strand*, in the vocabulary of the README.
 *
 * Belongs to the [CommitGraph] that handed it out, and there is exactly one instance per repository,
 * so two sources are the same repository precisely when they are the same object.
 */
class Source internal constructor(
    /**
     * Position of this repository among the inputs, in the order they were read.
     *
     * Public because a caller that keeps one entry per repository — a subdirectory, a tree, an open
     * handle — wants an array rather than a map, and this is the index into it.
     */
    val index: Int,
    /** Name of the input repository. */
    val name: String,
) {
    override fun toString(): String = name
}

/**
 * One commit of one input repository.
 *
 * A handle onto the graph rather than a copy of anything it holds: the commits live in parallel
 * arrays, and this puts a name on one row of them. There is exactly one instance per commit, so
 * identity comparison answers "the same commit", and a commit of one graph can be told from a commit
 * of another.
 *
 * Two repositories may contain the same commit sha without interfering — the inputs are independent
 * histories — so [id] alone does not identify a commit. The pair with [source] does, and so does
 * this object.
 */
class Commit internal constructor(
    private val graph: CommitGraph,
    private val core: DenseGraph,
    /**
     * Position of this commit in the graph, in `0 until graph.size`.
     *
     * Public because it is the join key between the graph and anything indexed alongside it: the git
     * payload the reader kept, the identities the writer hands out, the bitmap it marks progress in.
     * A side table of `graph.size` entries is addressed by this, and cheaply.
     */
    val index: Int,
) {
    /** Original commit sha in production, a short name in tests. */
    val id: String get() = core.ids[index]

    /** Timestamp used to interleave the strands, as resolved by the reader according to `--order-by`. */
    val time: Long get() = core.time[index]

    /** The input repository this commit came from. */
    val source: Source get() = graph.sources[core.source[index]]

    /** Parents in their original order, so the first entry is the first parent. */
    val parents: List<Commit> get() = graph.commitsAt(core.edges[index])

    /** First parent, or `null` for a root commit. */
    val firstParent: Commit? get() = parents.firstOrNull()

    override fun toString(): String = "$source/$id"
}

/**
 * Immutable DAG of every commit of every input repository.
 *
 * This is the planner's entire input. It deliberately knows nothing about git: a commit's identity is
 * an opaque string supplied by whoever built the graph, and its timestamp is a single `Long` resolved
 * once by the reader. The planner never learns whether it got author or committer time, which keeps
 * that choice a one-line decision outside this package.
 *
 * What it hands out is [Commit] and [Source]. The dense arrays the passes run on stay in a
 * [DenseGraph] this class holds privately and passes to the stages it creates — see [braid] — so the
 * indices are never reachable from a caller, only from a stage that was given them.
 */
class CommitGraph internal constructor(private val core: DenseGraph) {

    /** Number of commits; every [Commit.index] falls in `0 until size`. */
    val size: Int get() = core.size

    /** The input repositories, in the order they were read. */
    val sources: List<Source> = core.sourceNames.mapIndexed { index, name -> Source(index, name) }

    private val handles: Array<Commit> = Array(core.size) { Commit(this, core, it) }

    /** Every commit of every input repository. Position in this list is [Commit.index]. */
    val commits: List<Commit> = handles.asList()

    /**
     * A topological order of the original history: every commit exactly once, parents before children,
     * earliest timestamp first among the commits that are ready at any moment.
     *
     * The pipeline orders the *braided* history instead — see [ReparentedGraph.writeOrder] — because
     * only there is the interleave already baked into the parent edges.
     *
     * @throws CyclicGraphException if the graph is not a DAG.
     */
    fun topologicalOrder(): List<Commit> = commitsAt(TopoOrder.compute(core))

    /**
     * Interleaves the strands' mainlines into the single braid the output's history is built around.
     *
     * @param heads mainline tips, one per input repository. Two heads that share a tail contribute
     *   each commit once; a duplicate would later become a commit that is its own predecessor.
     * @param interleaveTips commits whose ancestry is allowed to delay a braid commit — the refs named
     *   by `--interleave-ref`, already resolved. Empty by default; see [BraidInterleave] for what
     *   widening the scope trades away.
     */
    fun braid(heads: List<Commit>, interleaveTips: List<Commit> = emptyList()): Braid =
        Braid(
            this,
            core,
            BraidInterleave.compute(
                core,
                indicesOf(heads, "head"),
                indicesOf(interleaveTips, "interleave tip"),
            ),
        )

    internal fun commitAt(commit: Int): Commit = handles[commit]

    internal fun commitsAt(indices: IntArray): List<Commit> = indices.map { handles[it] }

    /**
     * Indices of [commits], checking on the way that each one really is a commit of this graph.
     *
     * Identity is the check, not the bare index: a commit of another graph would otherwise be read as
     * whatever this graph happens to hold at the same position.
     */
    internal fun indicesOf(commits: List<Commit>, what: String): IntArray = IntArray(commits.size) {
        val commit = commits[it]
        require(commit.index in handles.indices && handles[commit.index] === commit) {
            "$what $commit is not a commit of this graph"
        }
        commit.index
    }

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
