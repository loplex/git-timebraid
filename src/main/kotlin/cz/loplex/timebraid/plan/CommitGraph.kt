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
 * A handle onto the [CommitGraph] rather than a copy of anything it holds: the graph keeps the
 * commits in parallel arrays, and this puts a name on one row of them. There is exactly one instance
 * per commit, so identity comparison answers "the same commit", and a commit of one graph can be
 * told from a commit of another.
 *
 * Two repositories may contain the same commit sha without interfering — the inputs are independent
 * histories — so [id] alone does not identify a commit. The pair with [source] does, and so does
 * this object.
 */
class Commit internal constructor(
    private val graph: CommitGraph,
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
    val id: String get() = graph.idOf(index)

    /** Timestamp used to interleave the strands, as resolved by the reader according to `--order-by`. */
    val time: Long get() = graph.timeOf(index)

    /** The input repository this commit came from. */
    val source: Source get() = graph.sourceAt(graph.sourceOf(index))

    /** Parents in their original order, so the first entry is the first parent. */
    val parents: List<Commit> get() = graph.parentHandlesOf(index)

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
 * Internally a commit is a dense index and a set of commits is an `IntArray`, which is what the Kahn
 * passes want; [Commit] and [Source] are how that leaves the class. The operations are methods rather
 * than free functions over the arrays, so each stage of the pipeline hands the next one an object —
 * see [braid].
 *
 * Edges are *not* checked for acyclicity when the graph is constructed: a graph read out of a
 * repository is a DAG by definition, and the one place where that can genuinely break is reparenting,
 * which checks explicitly.
 */
class CommitGraph internal constructor(
    parents: Array<IntArray>,
    sourceIndex: IntArray,
    orderingTime: LongArray,
    commitIds: Array<String>,
    internal val sourceNames: List<String>,
) {
    /** Number of commits; every [Commit.index] falls in `0 until size`. */
    val size: Int = parents.size

    private val edges: Array<IntArray> = Array(size) { parents[it].copyOf() }
    private val source: IntArray = sourceIndex.copyOf()
    private val time: LongArray = orderingTime.copyOf()
    private val ids: Array<String> = commitIds.copyOf()

    internal val sourceCount: Int get() = sourceNames.size

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

    /** The input repositories, in the order they were read. */
    val sources: List<Source> = sourceNames.mapIndexed { index, name -> Source(index, name) }

    private val handles: Array<Commit> = Array(size) { Commit(this, it) }

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
    fun topologicalOrder(): List<Commit> = commitsAt(TopoOrder.compute(this))

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
            BraidInterleave.compute(
                this,
                indicesOf(heads, "head"),
                indicesOf(interleaveTips, "interleave tip"),
            ),
        )

    internal fun parentsOf(commit: Int): IntArray = edges[commit]

    internal fun firstParentOf(commit: Int): Int =
        if (edges[commit].isEmpty()) NO_COMMIT else edges[commit][0]

    internal fun sourceOf(commit: Int): Int = source[commit]

    internal fun sourceAt(index: Int): Source = sources[index]

    internal fun timeOf(commit: Int): Long = time[commit]

    internal fun idOf(commit: Int): String = ids[commit]

    /**
     * `<repository>/<id>`, the same text [Commit.toString] gives — spelled out from the arrays
     * because validation runs before the handles exist.
     */
    internal fun describe(commit: Int): String = "${sourceNames[source[commit]]}/${ids[commit]}"

    internal fun commitAt(commit: Int): Commit = handles[commit]

    internal fun commitsAt(indices: IntArray): List<Commit> = indices.map { handles[it] }

    internal fun parentHandlesOf(commit: Int): List<Commit> = commitsAt(edges[commit])

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

    /**
     * A copy of this graph with a different set of parent edges and everything else unchanged.
     * Used to run a pass over the reparented history through the same accessors as the original one.
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
