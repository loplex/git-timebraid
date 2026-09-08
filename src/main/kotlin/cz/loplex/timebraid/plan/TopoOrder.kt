package cz.loplex.timebraid.plan

/**
 * Orders every commit of a graph so that **every parent comes before its child**, preferring the
 * earliest timestamp among the commits that are ready at any moment.
 *
 * In the pipeline this is the **write order**, and it runs on the *braided* history — the graph
 * [reparent] returns, in which every braid edge is already a real parent edge. By that point no
 * temporal decision is left to make: the interleave was decided by [BraidInterleave] and is baked
 * into the edges, so any topological order of that graph writes a correct repository, and the tree of
 * a commit follows from its first parent rather than from where the walk happened to reach it. What
 * this pass has to guarantee is only that a commit is never written before one of its parents, since
 * a git object cannot reference an object that does not exist yet.
 *
 * The walk itself is [KahnOrder], shared with [BraidInterleave]; this pass is that walk over every
 * commit there is, keeping every one of them.
 *
 * Ties on the timestamp break on the position a commit was given in, which makes the result a
 * deterministic function of the input: same commits in, byte-identical order out, in this run and in
 * any other. Preferring time costs nothing here and keeps a plan dump (`--plan-out`) readable,
 * roughly chronological rather than arbitrary.
 *
 * @param graph the commits to order and the edges to order them by, which are not always the
 *   commits' own — the write order runs on the braided history, where a braid edge is a parent like
 *   any other.
 * @return every commit exactly once, parents before children.
 * @throws CyclicGraphException if the graph is not a DAG.
 */
internal fun topoOrder(graph: IndexedGraph<Commit>): List<Commit> =
    KahnOrder(graph, compareBy(Commit::time)).order()
