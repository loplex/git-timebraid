package cz.loplex.timebraid.plan

/**
 * The two pieces both of the package's Kahn passes need: the edges pointing the other way, and the
 * rule for choosing among commits that are ready at the same moment.
 *
 * [CommitGraph] stores parents, because that is what a commit records; Kahn's algorithm walks from a
 * commit to the ones that depend on it, so it needs children. Building that adjacency is linear and
 * happens once per pass.
 */
internal class ChildEdges(val starts: IntArray, val targets: IntArray) {

    companion object {
        fun of(graph: CommitGraph): ChildEdges {
            val size = graph.size
            val starts = IntArray(size + 1)
            for (commit in 0 until size) {
                for (parent in graph.parentsOf(commit)) starts[parent + 1]++
            }
            for (commit in 0 until size) starts[commit + 1] += starts[commit]
            val cursor = starts.copyOf(size)
            val targets = IntArray(starts[size])
            for (commit in 0 until size) {
                for (parent in graph.parentsOf(commit)) targets[cursor[parent]++] = commit
            }
            return ChildEdges(starts, targets)
        }
    }
}

/**
 * Earliest timestamp first, ties broken on the dense index.
 *
 * The tie-break is not cosmetic: it makes the result a deterministic function of the input, so the
 * same graph gives byte-identical output in this run and in any other, and two distinct commits with
 * the same timestamp can never compare equal and collapse.
 */
internal fun earliestFirst(graph: CommitGraph): Comparator<Int> = Comparator { a, b ->
    val byTime = graph.timeOf(a).compareTo(graph.timeOf(b))
    if (byTime != 0) byTime else a.compareTo(b)
}
