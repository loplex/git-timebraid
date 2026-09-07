package cz.loplex.timebraid.plan

/**
 * Orders every commit of a graph so that **every parent comes before its child**, preferring the
 * earliest timestamp among the commits that are ready at any moment.
 *
 * In the pipeline this is the **write order**, and it runs on the *braided* history — the graph
 * [Reparenter] returns, in which every braid edge is already a real parent edge. By that point no
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
 * deterministic function of the input: same commits in, byte-identical order out, in this run and
 * in any other. Preferring time
 * costs nothing here and keeps a plan dump (`--plan-out`) readable, roughly chronological rather than
 * arbitrary.
 */
internal object TopoOrder {

    /**
     * @param commits the nodes to order; every parent [parentsOf] names has to be among them.
     * @param parentsOf the edges to order by, which are not always the commits' own — the write
     *   order runs on the braided history, where a braid edge is a parent like any other.
     * @return every commit exactly once, parents before children.
     * @throws CyclicGraphException if the graph is not a DAG.
     */
    fun compute(commits: List<Commit>, parentsOf: (Commit) -> List<Commit>): List<Commit> =
        KahnOrder(commits, parentsOf, compareBy(Commit::time)) {
            "$it is named as a parent but is not one of the commits to order"
        }.order()
}
