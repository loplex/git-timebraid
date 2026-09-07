package cz.loplex.timebraid.plan

/**
 * The braid: the commits that form the output's single interleaved mainline, in the order they will
 * appear on it.
 *
 * Produced by [CommitGraph.braid] and by nothing else, which is the point of the type. A braid has to
 * name commits of one graph, each at most once — a repeated member would end up its own predecessor —
 * and holding it as a sequence of numbers meant re-establishing that on every use. Here it is true by
 * construction.
 *
 * This is the one temporal decision in the whole pipeline. Everything after it follows from the parent
 * edges [reparent] derives from this order.
 */
class Braid internal constructor(
    private val graph: CommitGraph,
    private val core: DenseGraph,
    /** The braid, in braid order. */
    val commits: List<Commit>,
) {
    /** Number of commits on the braid. */
    val size: Int get() = commits.size

    /**
     * Applies the parent rule, turning each braid step into a real parent edge.
     *
     * @throws CyclicGraphException if the braid order contradicts ancestry.
     */
    fun reparent(): ReparentedGraph {
        val order = graph.indicesOf(commits, "braid commit")
        return ReparentedGraph(graph, core, order, reparent(core, order))
    }

    /**
     * The complete plan for the output history — [reparent] followed by [ReparentedGraph.plan].
     *
     * @param subdirs where each input repository goes in the output, `null` for the one placed at
     *   the root. Every repository of the graph has to appear.
     */
    fun plan(subdirs: Map<Source, String?>): MergePlan = reparent().plan(subdirs)
}

/**
 * The history after the parent rule has been applied: the original edges, plus one edge from each
 * braid commit to its predecessor on the braid.
 *
 * By this point no temporal decision is left to make. The interleave was decided by [Braid] and is
 * baked into the edges, so any topological order of this graph writes a correct repository.
 */
class ReparentedGraph internal constructor(
    private val graph: CommitGraph,
    private val core: DenseGraph,
    private val braid: IntArray,
    private val parents: Array<IntArray>,
) {
    private val order: IntArray by lazy {
        graph.indicesOf(topoOrder(graph.commits, ::parentsOf), "write order")
    }

    /** Parents of [commit] after reparenting, first parent first. */
    fun parentsOf(commit: Commit): List<Commit> = graph.commitsAt(parents[commit.indexIn(graph)])

    /**
     * The write order: every commit exactly once, parents before children, so a commit is never
     * written before an object it references.
     *
     * @throws CyclicGraphException if reparenting produced a graph that is not a DAG.
     */
    fun writeOrder(): List<Commit> = graph.commitsAt(order)

    /**
     * The complete, deterministic description of the output repository's history.
     *
     * @param subdirs where each input repository goes in the output, `null` for the one placed at
     *   the root. Every repository of the graph has to appear.
     */
    fun plan(subdirs: Map<Source, String?>): MergePlan =
        MergePlan.create(graph, core, braid, order, parents, subdirs)
}
