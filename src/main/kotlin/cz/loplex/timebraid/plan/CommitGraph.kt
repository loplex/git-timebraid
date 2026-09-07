package cz.loplex.timebraid.plan

/**
 * One input repository — one *strand*, in the vocabulary of the README.
 *
 * Belongs to the [CommitGraph] that handed it out, and there is exactly one instance per repository,
 * so two sources are the same repository precisely when they are the same object.
 */
class Source internal constructor(
    private val graph: CommitGraph,
    private val index: Int,
    /** Name of the input repository. */
    val name: String,
) {
    /**
     * Position of this repository among [graph]'s inputs, in the order they were read.
     *
     * Only obtainable by naming the graph it is meant for, and only from inside this module. An
     * index is a coordinate into one graph's tables and means nothing without that graph — and
     * means the wrong thing, silently, against another. Requiring the graph makes that pairing the
     * caller states rather than assumes.
     */
    internal fun indexIn(graph: CommitGraph, what: String = "repository"): Int {
        require(graph === this.graph) { "$what $this does not belong to this graph" }
        return index
    }

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
    private val index: Int,
) {
    /**
     * Position of this commit in [graph], in `0 until graph.size`.
     *
     * Only obtainable by naming the graph it is meant for, and only from inside this module — see
     * [Source.indexIn] for why. A caller outside the package keeps one entry per commit in a map
     * keyed by the commit itself, which cannot be paired with the wrong graph at all.
     */
    internal fun indexIn(graph: CommitGraph, what: String = "commit"): Int {
        require(graph === this.graph) { "$what $this is not a commit of this graph" }
        return index
    }

    /** Original commit sha in production, a short name in tests. */
    val id: String get() = graph.idAt(index)

    /** Timestamp used to interleave the strands, as resolved by the reader according to `--order-by`. */
    val time: Long get() = graph.timeAt(index)

    /** The input repository this commit came from. */
    val source: Source get() = graph.sources[graph.sourceAt(index)]

    /** Parents in their original order, so the first entry is the first parent. */
    val parents: List<Commit> get() = graph.parentsAt(index)

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
 * What it hands out is [Commit] and [Source]. The dense arrays behind them stay in a [DenseGraph]
 * this class holds privately, and nothing else in the package holds one: a stage of the pipeline is
 * given commits and works in commits, deriving whatever numbering it needs for itself.
 */
class CommitGraph private constructor(private val core: DenseGraph) {

    /** Number of commits; every commit's index falls in `0 until size`. */
    val size: Int get() = core.size

    /** The input repositories, in the order they were read. */
    val sources: List<Source> = core.sourceNames.mapIndexed { index, name -> Source(this, index, name) }

    private val handles: Array<Commit> = Array(core.size) { Commit(this, it) }

    /** Every commit of every input repository. Position in this list is that commit's index. */
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
    fun topologicalOrder(): List<Commit> = topoOrder(commits) { it.parents }

    /**
     * Interleaves the strands' mainlines into the single braid the output's history is built around.
     *
     * @param heads mainline tips, one per input repository. Two heads that share a tail contribute
     *   each commit once; a duplicate would later become a commit that is its own predecessor.
     * @param interleaveTips commits whose ancestry is allowed to delay a braid commit — the refs named
     *   by `--interleave-ref`, already resolved. Empty by default; see [BraidInterleave] for what
     *   widening the scope trades away.
     */
    fun braid(heads: List<Commit>, interleaveTips: List<Commit> = emptyList()): Braid {
        // Named for this graph or not at all. The pass orders the commits of this graph, so one from
        // another would contribute its own first-parent chain to nothing and be dropped without a
        // word — a wrong braid rather than a refused one.
        for (head in heads) head.indexIn(this, "head")
        for (tip in interleaveTips) tip.indexIn(this, "interleave tip")

        return Braid(this, BraidInterleave.compute(commits, heads, interleaveTips) { it.parents })
    }

    internal fun commitAt(commit: Int): Commit = handles[commit]

    internal fun commitsAt(indices: IntArray): List<Commit> = indices.map { handles[it] }

    // One row of the storage, for the [Commit] that names it. A commit is a handle onto this graph,
    // so it reads through the graph rather than holding the arrays itself — which is what lets the
    // storage type stay private to this file.
    internal fun idAt(commit: Int): String = core.ids[commit]
    internal fun timeAt(commit: Int): Long = core.time[commit]
    internal fun sourceAt(commit: Int): Int = core.source[commit]
    internal fun parentsAt(commit: Int): List<Commit> = commitsAt(core.edges[commit])

    companion object {
        /**
         * Builds a graph from the dense arrays, which are copied and shape-checked on the way in.
         *
         * This is the only door: the storage type is private to this file, so a caller — the builder,
         * or a test standing one up by hand — passes the arrays and never names it.
         */
        internal fun of(
            parents: Array<IntArray>,
            sourceIndex: IntArray,
            orderingTime: LongArray,
            commitIds: Array<String>,
            sourceNames: List<String>,
        ): CommitGraph =
            CommitGraph(DenseGraph.of(parents, sourceIndex, orderingTime, commitIds, sourceNames))
    }
}

/**
 * The commit graph as [CommitGraph] stores it: parallel arrays indexed by a dense commit number.
 *
 * A [Commit] is a handle onto one row of these, which is what keeps a history of 14 000 commits to a
 * handful of arrays rather than an object per edge. This is storage and nothing else, and its reach
 * is [CommitGraph] and the commits that graph hands out: every pass takes commits and derives the
 * numbering it works in for itself. Nothing outside this package can name the type either.
 *
 * Immutable in use: the arrays are copied on the way in and nothing writes into them afterwards. That
 * matters because two public objects can share the same instance — a [Braid] and the [CommitGraph] it
 * came from do.
 */
private class DenseGraph private constructor(
    val edges: Array<IntArray>,
    val source: IntArray,
    val time: LongArray,
    val ids: Array<String>,
    val sourceNames: List<String>,
) {
    val size: Int get() = edges.size

    /** `<repository>/<id>`, for diagnostics. The same text [Commit.toString] gives. */
    fun describe(commit: Int): String = "${sourceNames[source[commit]]}/${ids[commit]}"

    companion object {
        /**
         * Copies the arrays in and checks the shape: matching lengths, every source index and every
         * parent index in range, no commit its own parent, no parent listed twice.
         *
         * Acyclicity is deliberately *not* checked. A graph read out of a repository is a DAG by
         * definition, and the one place where that can genuinely break is reparenting, which checks
         * explicitly — see [requireAcyclic].
         */
        fun of(
            parents: Array<IntArray>,
            sourceIndex: IntArray,
            orderingTime: LongArray,
            commitIds: Array<String>,
            sourceNames: List<String>,
        ): DenseGraph {
            val size = parents.size
            require(sourceIndex.size == size) {
                "sourceIndex has ${sourceIndex.size} entries, expected $size"
            }
            require(orderingTime.size == size) {
                "orderingTime has ${orderingTime.size} entries, expected $size"
            }
            require(commitIds.size == size) {
                "commitIds has ${commitIds.size} entries, expected $size"
            }

            val graph = DenseGraph(
                Array(size) { parents[it].copyOf() },
                sourceIndex.copyOf(),
                orderingTime.copyOf(),
                commitIds.copyOf(),
                sourceNames,
            )

            for (commit in 0 until size) {
                require(graph.source[commit] in sourceNames.indices) {
                    "commit #$commit has source index ${graph.source[commit]}, " +
                        "expected 0..${sourceNames.size - 1}"
                }
                val parentsOfCommit = graph.edges[commit]
                for (i in parentsOfCommit.indices) {
                    val parent = parentsOfCommit[i]
                    require(parent in 0 until size) {
                        "commit #$commit has parent index $parent, expected 0..${size - 1}"
                    }
                    require(parent != commit) { "commit ${graph.describe(commit)} is its own parent" }
                    for (j in 0 until i) {
                        require(parentsOfCommit[j] != parent) {
                            "commit ${graph.describe(commit)} lists parent " +
                                "${graph.describe(parent)} twice"
                        }
                    }
                }
            }
            return graph
        }
    }
}
