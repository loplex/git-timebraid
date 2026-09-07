package cz.loplex.timebraid.plan

import java.util.PriorityQueue

/**
 * Kahn's algorithm over the nodes [inScope], earliest timestamp first among those ready at any
 * moment. Both passes of this package are this walk; they differ only in what they put in scope and
 * what they keep of the result — [TopoOrder] takes everything and keeps everything, while
 * [BraidInterleave] scopes it to the mainline chains and keeps those.
 *
 * Keep the set of nodes whose in-scope parents have all been emitted, and always take the one with
 * the earliest timestamp. Membership of that ready set means no ancestry relation connects those
 * nodes — if `x` were an ancestor of `y`, `y` could not have entered the set before `x` was emitted —
 * so parents-before-children holds by construction rather than by a comparator that has to be
 * trusted to be consistent.
 *
 * A parent outside the scope is not waited for, which is what lets a caller order part of a graph.
 *
 * @param scopeSize how many nodes [inScope] marks, which is the length of the result.
 * @param what names the nodes in the failure message.
 * @throws CyclicGraphException if the edges in scope are not a DAG.
 */
internal fun Numbering.readyOrder(inScope: BooleanArray, scopeSize: Int, what: String): IntArray {
    val children = ChildEdges.of(edges)
    val unemitted = IntArray(size)
    val ready = PriorityQueue(maxOf(1, scopeSize), earliestFirst(commits))
    for (commit in 0 until size) {
        if (!inScope[commit]) continue
        unemitted[commit] = edges[commit].count { inScope[it] }
        if (unemitted[commit] == 0) ready.add(commit)
    }

    val order = IntArray(scopeSize)
    var emitted = 0
    while (ready.isNotEmpty()) {
        val commit = ready.poll()
        order[emitted++] = commit
        for (i in children.starts[commit] until children.starts[commit + 1]) {
            val child = children.targets[i]
            if (inScope[child] && --unemitted[child] == 0) ready.add(child)
        }
    }

    if (emitted != scopeSize) {
        // Whatever was left has an unemitted parent, which in a finite graph means a cycle.
        requireAcyclic(edges, ::describe)
        error("$emitted of $scopeSize $what were ordered, but no cycle was found")
    }
    return order
}

/**
 * What [readyOrder] runs on: the nodes of one pass, numbered, with their edges as dense rows.
 *
 * A pass is handed commits and a rule for reading their parents; this is the representation it then
 * works in, because the walk counts edges down and wants a flat `IntArray` per node and a counter per
 * node rather than a graph of objects. Deriving that is one pass over the input.
 *
 * The numbering is the pass's own and never leaves it. That is the point of building it here rather
 * than being handed one: an index is a row of *this* table, so there is nothing to pair it with
 * wrongly, and a commit the pass was not given is named instead of being read as whatever sits at
 * the same position.
 */
internal class Numbering(val commits: List<Commit>, parentsOf: (Commit) -> List<Commit>) {

    private val indices = HashMap<Commit, Int>(commits.size * 2)

    /** Parents of every node, as indices into [commits]. */
    val edges: Array<IntArray>

    val size: Int get() = commits.size

    init {
        commits.forEachIndexed { index, commit -> indices[commit] = index }
        edges = Array(commits.size) { index ->
            val parents = parentsOf(commits[index])
            IntArray(parents.size) { indexOf(parents[it], "parent") }
        }
    }

    fun indexOf(commit: Commit, what: String): Int =
        indices[commit] ?: error("$what $commit is not among the commits this pass was given")

    fun indicesOf(commits: List<Commit>, what: String): IntArray =
        IntArray(commits.size) { indexOf(commits[it], what) }

    fun commitsAt(indices: IntArray): List<Commit> = indices.map { commits[it] }

    /** First parent of [commit], or [CommitGraph.NO_COMMIT] for a root. */
    fun firstParentOf(commit: Int): Int = edges[commit].firstOrNull() ?: CommitGraph.NO_COMMIT

    /** Names a node for an error message. */
    fun describe(commit: Int): String = commits[commit].toString()
}

/**
 * The edges pointing the other way.
 *
 * A commit records its parents, because that is what git stores; the walk goes from a commit to the
 * ones that depend on it, so it needs children. Building that adjacency is linear and happens once
 * per pass.
 */
private class ChildEdges(val starts: IntArray, val targets: IntArray) {

    companion object {
        fun of(edges: Array<IntArray>): ChildEdges {
            val size = edges.size
            val starts = IntArray(size + 1)
            for (commit in 0 until size) {
                for (parent in edges[commit]) starts[parent + 1]++
            }
            for (commit in 0 until size) starts[commit + 1] += starts[commit]
            val cursor = starts.copyOf(size)
            val targets = IntArray(starts[size])
            for (commit in 0 until size) {
                for (parent in edges[commit]) targets[cursor[parent]++] = commit
            }
            return ChildEdges(starts, targets)
        }
    }
}

/**
 * Earliest timestamp first, ties broken on the pass's own index.
 *
 * The tie-break is not cosmetic: it makes the result a deterministic function of the input, so the
 * same commits in the same order give byte-identical output in this run and in any other, and two
 * distinct commits with the same timestamp can never compare equal and collapse.
 */
private fun earliestFirst(commits: List<Commit>): Comparator<Int> = Comparator { a, b ->
    val byTime = commits[a].time.compareTo(commits[b].time)
    if (byTime != 0) byTime else a.compareTo(b)
}
