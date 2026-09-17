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
 * two parents, and a commit that was already a two-parent merge in its own repository ends up with
 * three. That third parent is the price of the guarantee: drop it and `git merge-base` and every
 * "when did this diverge" question start lying.
 *
 * When `pred` is already a parent — the common case of two consecutive commits from the same
 * repository — nothing is added, which is also what keeps a parent from being listed twice.
 *
 * @param graph the commits the rule applies to, under their original edges.
 * @param braid the mainline in braid order, as produced by [BraidInterleave].
 * @return the new parent list of every commit.
 * @throws CyclicGraphException if the braid order contradicts ancestry.
 */
internal fun reparent(graph: CommitGraph, braid: List<Commit>): ParentEdges {
    val commits = graph.commits
    val rows = Array(commits.size) { index -> graph.parentsOf(commits[index]) }

    for (i in 1 until braid.size) {
        val commit = braid[i]
        val predecessor = braid[i - 1]
        val original = rows[commit.position]
        if (predecessor !in original) {
            rows[commit.position] = ArrayList<Commit>(original.size + 1).apply {
                add(predecessor)
                addAll(original)
            }
        }
    }

    // Fail loudly rather than write a broken repository: a braid order that respects ancestry
    // cannot produce a cycle here, so a cycle means the order itself was wrong.
    requireAcyclic(graph.withParents { rows[graph.indexOf(it)] })

    return ParentEdges(commits, rows)
}

/**
 * One parent list per commit of one graph, addressed by the position the commit carries.
 *
 * A commit of a built graph already knows where it sits, so this is an array read where a map keyed
 * by commits would hash. The commit found at that position is checked to be the one asked about,
 * which is what refuses a commit of another graph — the same refusal a map gave by missing, and a
 * reference comparison rather than a lookup.
 */
internal class ParentEdges(
    private val nodes: List<Commit>,
    private val rows: Array<List<Commit>>,
) {
    operator fun get(commit: Commit): List<Commit> = rows[positionOf(commit)]

    private fun positionOf(commit: Commit): Int {
        val at = commit.position
        if (at !in rows.indices || nodes[at] !== commit) {
            error("$commit is not one of the commits these edges were derived for")
        }
        return at
    }
}
