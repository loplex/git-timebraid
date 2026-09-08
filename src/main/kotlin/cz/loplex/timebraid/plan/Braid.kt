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
    fun reparent(): ReparentedGraph =
        ReparentedGraph(graph, commits, reparent(graph, commits))

    /**
     * The complete plan for the output history — [reparent] followed by [ReparentedGraph.plan].
     *
     * @param subdirs where each input repository goes in the output, `null` for the one placed at
     *   the root. Every repository of the graph has to appear.
     * @param splice whether one destination may lie inside another — see [MergePlan.create].
     */
    fun plan(subdirs: Map<Source, String?>, splice: Boolean = false): MergePlan =
        reparent().plan(subdirs, splice)
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
    private val braid: List<Commit>,
    private val parents: ParentEdges,
) {
    private val braided: IndexedGraph<Commit> = graph.withParents { parents[it] }

    private val order: List<Commit> by lazy { topoOrder(braided) }

    /** Parents of [commit] after reparenting, first parent first. */
    fun parentsOf(commit: Commit): List<Commit> = parents[commit]

    /**
     * The write order: every commit exactly once, parents before children, so a commit is never
     * written before an object it references.
     *
     * @throws CyclicGraphException if reparenting produced a graph that is not a DAG.
     */
    fun writeOrder(): List<Commit> = order

    /**
     * The complete, deterministic description of the output repository's history.
     *
     * @param subdirs where each input repository goes in the output, `null` for the one placed at
     *   the root. Every repository of the graph has to appear.
     * @param splice whether one destination may lie inside another — see [MergePlan.create].
     */
    fun plan(subdirs: Map<Source, String?>, splice: Boolean = false): MergePlan =
        MergePlan.create(graph, braid, order, parents, subdirs, splice)
}
