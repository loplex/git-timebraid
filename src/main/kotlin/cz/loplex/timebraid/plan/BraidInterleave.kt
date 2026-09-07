package cz.loplex.timebraid.plan

import java.util.PriorityQueue

/**
 * Decides *the braid*: which commits form the output's single interleaved mainline, and in what order.
 *
 * The braid is the union of the input repositories' mainline first-parent chains. Their order is
 * decided by Kahn's algorithm with a priority queue — keep the commits whose parents have all been
 * emitted, always take the one with the earliest timestamp — run not over the whole graph but over a
 * **scope**: the mainline chains, plus the ancestors of whatever refs the caller opted in through
 * the `interleaveTips` parameter of [compute]. [Reparenter] then turns consecutive braid members
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
 *    edge this class hands to [Reparenter] can consequently never point into the future.
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
 * ([TopoOrder], run after reparenting), not of the braid.
 *
 * The default mechanism is the one this project's first prototype used in June 2021: each branch read
 * as its own `git log --topo-order`, those logs merged k-way.
 *
 * Note the invariant properties 1 and 2 rest on: **one chain per repository.** A repository contributes
 * exactly one because the mainline resolves to one branch tip per input. Two divergent-and-reconverging
 * chains of a single repository would break the argument and are out of scope, exactly as they were for
 * the original two-repository-only tool.
 */
object BraidInterleave {

    /**
     * @param heads mainline tips, one per input repository. Two heads that share a tail contribute
     *   each commit once; a duplicate would later become a commit that is its own predecessor.
     * @param interleaveTips commits whose ancestry is allowed to delay a braid commit — the refs named
     *   by `--interleave-ref`, already resolved. Empty by default, which is the mainline-chains-only
     *   scope described above.
     * @return the braid — the union of the heads' first-parent chains — in braid order.
     */
    fun compute(
        graph: CommitGraph,
        heads: IntArray,
        interleaveTips: IntArray = IntArray(0),
    ): IntArray {
        val onBraid = BooleanArray(graph.size)
        var braidSize = 0
        for (head in heads) {
            require(head in 0 until graph.size) { "head index $head is not a commit of this graph" }
            var commit = head
            while (commit != CommitGraph.NO_COMMIT && !onBraid[commit]) {
                onBraid[commit] = true
                braidSize++
                commit = graph.firstParentOf(commit)
            }
        }

        val inScope = onBraid.copyOf()
        var scopeSize = braidSize
        val pending = ArrayDeque<Int>()
        for (tip in interleaveTips) {
            require(tip in 0 until graph.size) { "tip index $tip is not a commit of this graph" }
            if (!inScope[tip]) {
                inScope[tip] = true
                scopeSize++
                pending.addLast(tip)
            }
        }
        while (pending.isNotEmpty()) {
            for (parent in graph.parentsOf(pending.removeLast())) {
                if (!inScope[parent]) {
                    inScope[parent] = true
                    scopeSize++
                    pending.addLast(parent)
                }
            }
        }

        val children = ChildEdges.of(graph)
        val unemitted = IntArray(graph.size)
        val ready = PriorityQueue(maxOf(1, scopeSize), earliestFirst(graph))
        for (commit in 0 until graph.size) {
            if (!inScope[commit]) continue
            unemitted[commit] = graph.parentsOf(commit).count { inScope[it] }
            if (unemitted[commit] == 0) ready.add(commit)
        }

        val braid = IntArray(braidSize)
        var next = 0
        var emitted = 0
        while (ready.isNotEmpty()) {
            val commit = ready.poll()
            emitted++
            if (onBraid[commit]) braid[next++] = commit
            for (i in children.starts[commit] until children.starts[commit + 1]) {
                val child = children.targets[i]
                if (inScope[child] && --unemitted[child] == 0) ready.add(child)
            }
        }

        if (emitted != scopeSize) {
            // Whatever was left has an unemitted parent, which in a finite graph means a cycle.
            requireAcyclic(Array(graph.size) { graph.parentsOf(it) }, graph::describe)
            error("$emitted of $scopeSize commits in scope were ordered, but no cycle was found")
        }
        return braid
    }
}
