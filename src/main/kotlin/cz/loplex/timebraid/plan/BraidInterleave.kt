package cz.loplex.timebraid.plan

/**
 * Decides *the braid*: which commits form the output's single interleaved mainline, and in what order.
 *
 * The braid is the union of the input repositories' mainline first-parent chains. Their order is
 * decided by Kahn's algorithm with a priority queue — keep the commits whose parents have all been
 * emitted, always take the one with the earliest timestamp — run not over the whole graph but over a
 * **scope**: the mainline chains, plus the ancestors of whatever refs the caller opted in through
 * the `interleaveTips` parameter of [compute]. [reparent] then turns consecutive braid members
 * into parent edges, which is what makes `git log --first-parent` walk the braid.
 *
 * **With no opted-in refs — the default — the scope is the mainline chains alone**, and since those are
 * disjoint paths the pass reduces exactly to a k-way merge of one queue per repository: the ready set
 * holds each chain's current front, and the earliest of them wins. Three properties follow from that
 * shape rather than from a comparator that would have to be trusted to stay consistent:
 *
 * 1. **One repository's own chain is never reordered.** Kahn emits a parent before its child, so two
 *    commits of one chain keep their order whatever their timestamps say — which a rebase or a skewed
 *    clock makes routine. Ancestry along a mainline therefore holds by construction.
 * 2. **A cross-repository predecessor is never timestamped later than the commit it precedes.** When a
 *    commit's predecessor comes from another chain, that commit was already in the ready set when the
 *    predecessor was taken, so the predecessor won a direct comparison against it. The artificial time
 *    edge this class hands to [reparent] can consequently never point into the future.
 * 3. **No branch outside the mainlines can shift the interleave.** Nothing off a mainline chain is in
 *    scope, so a side branch merged into a mainline — however inconveniently timestamped, however
 *    insignificant — cannot move where two repositories' mainlines meet. A merge takes its braid place
 *    at its own recorded time, which under the default `--order-by committer` is exactly the moment
 *    that branch landed.
 *
 * Property 3 is a scheduling decision, not a correctness one, which is why it can be opted out of.
 * Naming a ref through `--interleave-ref` puts its ancestors in scope, so a merge that merges that ref
 * in waits for it, and the merge can then land later than its own timestamp — the trade the caller is
 * choosing. Naming every ref reproduces a plain pass over the whole graph, which is what this tool did
 * before the braid and the write order were separated.
 *
 * Widening the scope stays safe whatever is named: cross-repository pairs have no ancestry relation at
 * all (the inputs are independent histories), same-repository braid members are ordered by property 1,
 * and neither depends on the scope — so a braid built here can never close a cycle when reparented.
 * Every original edge is preserved regardless; ancestry is a property of the *write* order
 * ([topoOrder], run after reparenting), not of the braid.
 *
 * The default mechanism is the one this project's first prototype used in June 2021: each branch read
 * as its own `git log --topo-order`, those logs merged k-way.
 *
 * Note the invariant properties 1 and 2 rest on: **one chain per repository.** A repository contributes
 * exactly one because the mainline resolves to one branch tip per input. Two divergent-and-reconverging
 * chains of a single repository would break the argument and are out of scope, exactly as they were for
 * the original two-repository-only tool.
 */
internal object BraidInterleave {

    /**
     * @param heads mainline tips, one per input repository. Two heads that share a tail contribute
     *   each commit once; a duplicate would later become a commit that is its own predecessor.
     * @param interleaveTips commits whose ancestry is allowed to delay a braid commit — the refs named
     *   by `--interleave-ref`, already resolved. Empty by default, which is the mainline-chains-only
     *   scope described above.
     * @param graph the commits to walk and the edges to walk them by, the commits' own in every
     *   present caller. Every head and tip has to be one of its nodes.
     * @return the braid — the union of the heads' first-parent chains — in braid order.
     */
    fun compute(
        graph: IndexedGraph<Commit>,
        heads: List<Commit>,
        interleaveTips: List<Commit>,
    ): List<Commit> {
        // The braid itself: each head's first-parent chain, up to wherever it meets one already
        // walked — the flag already being set is that meeting point.
        val onBraid = BooleanArray(graph.indexSpace)
        for (head in heads) {
            var commit: Commit? = head
            while (commit != null) {
                val at = graph.indexOf(commit)
                if (onBraid[at]) break
                onBraid[at] = true
                commit = graph.parentsOf(commit).firstOrNull()
            }
        }

        // Scope: the braid, plus everything the opted-in tips reach. A commit in scope but off the
        // braid is never written by this pass; it is here only so that it can delay one that is.
        val inScope = onBraid.copyOf()
        val pending = ArrayDeque<Commit>()
        for (tip in interleaveTips) {
            val at = graph.indexOf(tip)
            if (!inScope[at]) {
                inScope[at] = true
                pending.addLast(tip)
            }
        }
        while (pending.isNotEmpty()) {
            for (parent in graph.parentsOf(pending.removeLast())) {
                val at = graph.indexOf(parent)
                if (!inScope[at]) {
                    inScope[at] = true
                    pending.addLast(parent)
                }
            }
        }

        // The scope is a part of the graph and keeps its numbering, which is also what keeps the
        // walk's tie-break the same as it would be over the whole graph: for any two commits in
        // scope, their indices here and there rank them alike.
        val order = KahnOrder(Part(graph, inScope), compareBy(Commit::time)).order()

        return order.filter { onBraid[graph.indexOf(it)] }
    }
}

/**
 * The part of [whole] the interleave is decided over: the braid, plus whatever the opted-in tips
 * reach. Keeps the whole graph's numbering, so a commit has one position throughout the pipeline.
 */
private class Part(
    private val whole: IndexedGraph<Commit>,
    private val inScope: BooleanArray,
) : IndexedGraph<Commit> {

    override val nodes: List<Commit> = whole.nodes.filter { inScope[whole.indexOf(it)] }

    override val indexSpace: Int get() = whole.indexSpace

    override fun indexOf(node: Commit): Int = whole.indexOf(node)

    /** The edges within the part: a parent outside it waits for nothing and is not reported. */
    override fun parentsOf(node: Commit): List<Commit> =
        whole.parentsOf(node).filter { inScope[whole.indexOf(it)] }
}
