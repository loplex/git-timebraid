package cz.loplex.timebraid.plan

/**
 * One input repository — one *strand*, in the vocabulary of the README.
 *
 * Belongs to the [CommitGraph] built from it, and there is exactly one instance per repository, so
 * two sources are the same repository precisely when they are the same object.
 */
class Source internal constructor(
    /** Name of the input repository. */
    val name: String,
) {
    override fun toString(): String = name
}

/**
 * One commit of one input repository.
 *
 * Carries its own data and names its parents directly, so a history is a graph of these and nothing
 * standing in for one. There is exactly one instance per commit, so identity answers "the same
 * commit", and a map keyed by commits is a map over one history.
 *
 * Two repositories may contain the same commit sha without interfering — the inputs are independent
 * histories — so [id] alone does not identify a commit. The pair with [source] does, and so does this
 * object.
 *
 * A commit comes into being the moment anything names it, which is usually as somebody's parent: a
 * repository log is walked newest first, so a commit is mentioned before it is reached. Its own data
 * arrives when [CommitGraphBuilder.addCommit] is given it, and until then reading [time] or [parents]
 * is an error rather than a blank. No commit of a built graph is in that state:
 * [CommitGraphBuilder.build] is exactly what refuses a history with a gap left in it.
 */
class Commit internal constructor(
    /** The input repository this commit came from. */
    val source: Source,
    /** Original commit sha in production, a short name in tests. */
    val id: String,
) {

    private var edges: List<Commit>? = null
    private var stamp: Long = 0

    /** Timestamp used to interleave the strands, as resolved by the reader according to `--order-by`. */
    val time: Long get() = if (edges != null) stamp else neverAdded()

    /** Parents in their original order, so the first entry is the first parent. */
    val parents: List<Commit> get() = edges ?: neverAdded()

    /** First parent, or `null` for a root commit. */
    val firstParent: Commit? get() = parents.firstOrNull()

    /** Whether the commit's own data has arrived, as against it having only been named as a parent. */
    internal val isAdded: Boolean get() = edges != null

    /** Gives the commit its data. The builder calls this once, when the commit's own turn comes. */
    internal fun define(time: Long, parents: List<Commit>) {
        stamp = time
        edges = parents
    }

    private fun neverAdded(): Nothing =
        error("$this was named as a parent but never added, so it has no data of its own")

    override fun toString(): String = "$source/$id"
}

/**
 * DAG of every commit of every input repository.
 *
 * This is the planner's entire input. It deliberately knows nothing about git: a commit's identity is
 * an opaque string supplied by whoever built the graph, and its timestamp is a single `Long` resolved
 * once by the reader. The planner never learns whether it got author or committer time, which keeps
 * that choice a one-line decision outside this package.
 *
 * The graph is barely more than what it was built from — its repositories and its commits, each in
 * the order they were read. The edges live on the commits themselves, so a stage of the pipeline is
 * given commits, works in commits, and derives whatever numbering it needs for itself; [KahnOrder] is
 * where that numbering is done and where it stays.
 *
 * Immutable once [CommitGraphBuilder.build] has returned it, which matters because two public objects
 * can share the same commits — a [Braid] and the [CommitGraph] it came from do.
 */
class CommitGraph internal constructor(
    /** The input repositories, in the order they were read. */
    val sources: List<Source>,
    /** Every commit of every input repository, in the order they were first named. */
    val commits: List<Commit>,
) {

    /** Number of commits. */
    val size: Int get() = commits.size

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
        for (head in heads) requireOwn(head, "head")
        for (tip in interleaveTips) requireOwn(tip, "interleave tip")

        return Braid(this, BraidInterleave.compute(commits, heads, interleaveTips) { it.parents })
    }

    /**
     * Refuses a commit that belongs to another graph.
     *
     * A repository is built into exactly one graph, so a commit is one of this graph's precisely when
     * its repository is — a handful of comparisons rather than a lookup against every commit there is.
     */
    private fun requireOwn(commit: Commit, what: String) {
        require(sources.any { it === commit.source }) {
            "$what $commit is not a commit of this graph"
        }
    }
}
