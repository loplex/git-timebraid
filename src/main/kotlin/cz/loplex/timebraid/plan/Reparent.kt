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
 *
 * @param graph the commits the rule applies to, under their original edges.
 * @param braid the mainline in braid order, as produced by [BraidInterleave].
 * @return the new parent list of every commit.
 * @throws CyclicGraphException if the braid order contradicts ancestry.
 */
internal fun reparent(graph: CommitGraph, braid: List<Commit>): Map<Commit, List<Commit>> {
    val parents = LinkedHashMap<Commit, List<Commit>>(graph.commits.size * 2)
    for (commit in graph.commits) parents[commit] = graph.parentsOf(commit)

    for (i in 1 until braid.size) {
        val commit = braid[i]
        val predecessor = braid[i - 1]
        val original = parents.getValue(commit)
        if (predecessor !in original) parents[commit] = listOf(predecessor) + original
    }

    // Fail loudly rather than write a broken repository: a braid order that respects ancestry
    // cannot produce a cycle here, so a cycle means the order itself was wrong.
    requireAcyclic(graph.withParents(parents::getValue))

    return parents
}
