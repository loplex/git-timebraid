package cz.loplex.timebraid.plan

/**
 * A k-way merge of one queue per repository, written out independently of production code.
 *
 * [BraidInterleave] with no opted-in refs is supposed to *reduce* to this: its scope is then the
 * mainline chains alone, which are disjoint paths, so its ready set holds each chain's front and the
 * earliest wins. This spells out the same rule the other way — take whichever queue's front is oldest,
 * never look at anything but the fronts — so a test can assert the reduction instead of trusting the
 * reasoning about it.
 *
 * Kept in test scope on purpose: production has one mechanism, and a second implementation of the same
 * decision would only be somewhere for the two to drift apart. Here its whole job is to disagree if
 * production ever stops reducing to it.
 */
object KWayBraid {

    fun compute(heads: List<Commit>): List<Commit> {
        val claimed = HashSet<Commit>()
        val queues = ArrayList<ArrayDeque<Commit>>()
        for (head in heads) {
            val chain = ArrayDeque<Commit>()
            var commit: Commit? = head
            while (commit != null && claimed.add(commit)) {
                chain.addFirst(commit)
                commit = commit.firstParent
            }
            if (chain.isNotEmpty()) queues.add(chain)
        }

        val result = ArrayList<Commit>(queues.sumOf { it.size })
        while (queues.isNotEmpty()) {
            var oldest = 0
            var oldestTime = queues[0].first().time
            for (i in 1 until queues.size) {
                val time = queues[i].first().time
                if (time < oldestTime) {
                    oldestTime = time
                    oldest = i
                }
            }
            result.add(queues[oldest].removeFirst())
            if (queues[oldest].isEmpty()) queues.removeAt(oldest)
        }
        return result
    }
}
