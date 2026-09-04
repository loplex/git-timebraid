package cz.loplex.timebraid.plan

/**
 * Applies the parent rule: for every commit `c` on the braid, with `pred` the commit before it in
 * the braid,
 *
 * ```
 * c is not on the braid          ->  parents'(c) = parents(c)             unchanged
 * pred is already a parent of c  ->  parents'(c) = parents(c)             unchanged
 * otherwise                      ->  parents'(c) = [pred] + parents(c)    braided edge prepended
 * ```
 *
 * The rule **adds**, it never replaces. The braided edge is prepended, so it becomes the first
 * parent and `git log --first-parent` walks the braid, while every original edge stays exactly where
 * it was. An ordinary commit whose predecessor comes from another repository therefore ends up with
 * two parents, and a commit that was already a merge in its own repository ends up with three. That
 * third parent is the price of the guarantee: drop it and `git merge-base` and every "when did this
 * diverge" question start lying.
 *
 * When `pred` is already a parent — the common case of two consecutive commits from the same
 * repository — nothing is added, which is also what keeps a parent from being listed twice.
 */
object Reparenter {

    /**
     * @param braid the mainline in braid order, as produced by [BraidInterleave].
     * @return new parent lists for every commit, indexed exactly like the graph.
     * @throws CyclicGraphException if the braid order contradicts ancestry.
     */
    fun reparent(graph: CommitGraph, braid: IntArray): Array<IntArray> {
        val parents = Array(graph.size) { graph.parentsOf(it).copyOf() }

        for (i in 1 until braid.size) {
            val commit = braid[i]
            val predecessor = braid[i - 1]
            val original = parents[commit]
            if (!original.holds(predecessor)) {
                parents[commit] = IntArray(original.size + 1).also {
                    it[0] = predecessor
                    original.copyInto(it, destinationOffset = 1)
                }
            }
        }

        // Fail loudly rather than write a broken repository: a braid order that respects ancestry
        // cannot produce a cycle here, so a cycle means the order itself was wrong.
        requireAcyclic(parents, graph::describe)
        return parents
    }

    private fun IntArray.holds(value: Int): Boolean {
        for (element in this) if (element == value) return true
        return false
    }
}
