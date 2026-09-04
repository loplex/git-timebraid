package cz.loplex.timebraid.plan

/**
 * Decides *the braid*: which commits form the output's single interleaved mainline, and in what order.
 *
 * The braid is the union of the input repositories' mainline first-parent chains, interleaved by time.
 * One queue per repository holds that repository's chain oldest-first; the next braid commit is always
 * the queue whose front carries the earliest timestamp. [Reparenter] then turns consecutive braid
 * members into parent edges, which is what makes `git log --first-parent` walk the braid.
 *
 * Three properties follow from the mechanism rather than from a comparator that would have to be
 * trusted to stay consistent:
 *
 * 1. **One repository's own chain is never reordered.** A queue is drained from the front only, so two
 *    commits of the same chain keep their order whatever their timestamps say — which a rebase or a
 *    skewed clock makes routine. Ancestry along a mainline therefore holds by construction.
 * 2. **A cross-repository predecessor is never timestamped later than the commit it precedes.** When a
 *    commit's predecessor comes from another queue, that commit was already at the front of its own
 *    queue when the predecessor was taken — queues only shrink from the front, and a different queue
 *    was the one that shrank — so the predecessor won a direct comparison against it. The artificial
 *    time edge this class hands to [Reparenter] can consequently never point into the future.
 * 3. **No branch outside the mainlines can shift the interleave.** Only first-parent chains are
 *    tracked, so a side branch merged into a mainline — however inconveniently timestamped, however
 *    insignificant — cannot move where two repositories' mainlines meet. This is deliberate: a merge
 *    takes its braid place at its own recorded time, which under the default `--order-by committer` is
 *    exactly the moment that branch landed.
 *
 * Property 3 is a scheduling decision, never a correctness one. Waiting for a merge's every parent —
 * what a plain topological pass over the whole graph would do — buys no guarantee this tool makes:
 * cross-repository pairs have no ancestry relation at all (the inputs are independent histories), and
 * same-repository braid members are ordered by property 1, so a braid built here can never close a
 * cycle when reparented. Every original edge, including a merged-in branch's, is preserved regardless;
 * ancestry is a property of the *write* order ([TopoOrder], run after reparenting), not of the braid.
 *
 * The mechanism is the one this project's first prototype used in June 2021: each branch read as its
 * own `git log --topo-order`, those logs merged k-way.
 *
 * Note the invariant properties 1 and 2 rest on: **one queue per repository.** Both hold per queue, and
 * a repository contributes exactly one because the mainline resolves to one branch tip per input. Two
 * divergent-and-reconverging chains of a single repository would break the argument and are out of
 * scope, exactly as they were for the original two-repository-only tool.
 */
object BraidInterleave {

    /**
     * @param heads mainline tips, one per input repository. Two heads that share a tail contribute
     *   each commit once; a duplicate would later become a commit that is its own predecessor.
     * @return the braid — the union of the heads' first-parent chains — in braid order.
     */
    fun compute(graph: CommitGraph, heads: IntArray): IntArray {
        val claimed = BooleanArray(graph.size)
        val queues = ArrayList<ArrayDeque<Int>>()
        for (head in heads) {
            require(head in 0 until graph.size) { "head index $head is not a commit of this graph" }
            val chain = ArrayDeque<Int>()
            var commit = head
            while (commit != CommitGraph.NO_COMMIT && !claimed[commit]) {
                claimed[commit] = true
                chain.addFirst(commit)
                commit = graph.firstParentOf(commit)
            }
            if (chain.isNotEmpty()) queues.add(chain)
        }

        val result = IntArray(queues.sumOf { it.size })
        var next = 0
        while (queues.isNotEmpty()) {
            var oldest = 0
            var oldestTime = graph.timeOf(queues[0].first())
            for (i in 1 until queues.size) {
                val time = graph.timeOf(queues[i].first())
                if (time < oldestTime) {
                    oldestTime = time
                    oldest = i
                }
            }
            result[next++] = queues[oldest].removeFirst()
            if (queues[oldest].isEmpty()) queues.removeAt(oldest)
        }
        return result
    }
}
