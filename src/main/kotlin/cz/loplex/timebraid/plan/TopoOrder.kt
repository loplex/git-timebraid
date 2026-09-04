package cz.loplex.timebraid.plan

import java.util.PriorityQueue

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
 * The implementation is Kahn's algorithm with a priority queue: keep the set of commits whose parents
 * have all been emitted, and always take the one with the earliest timestamp. Membership of that
 * ready set means no ancestry relation connects those commits — if `x` were an ancestor of `y`, `y`
 * could not have entered the set before `x` was emitted — so ancestry holds by construction rather
 * than by a comparator that has to be trusted to be consistent.
 *
 * Ties on the timestamp break on the dense index, which makes the result a deterministic function of
 * the input: same graph in, byte-identical order out, in this run and in any other. Preferring time
 * costs nothing here and keeps a plan dump (`--plan-out`) readable, roughly chronological rather than
 * arbitrary.
 */
object TopoOrder {

    /**
     * @return every commit index exactly once, parents before children.
     * @throws CyclicGraphException if the graph is not a DAG.
     */
    fun compute(graph: CommitGraph): IntArray {
        val size = graph.size
        val children = ChildEdges.of(graph)
        val unemittedParents = IntArray(size) { graph.parentsOf(it).size }

        val ready = PriorityQueue(maxOf(1, size), earliestFirst(graph))
        for (commit in 0 until size) {
            if (unemittedParents[commit] == 0) ready.add(commit)
        }

        val order = IntArray(size)
        var emitted = 0
        while (ready.isNotEmpty()) {
            val commit = ready.poll()
            order[emitted++] = commit
            for (i in children.starts[commit] until children.starts[commit + 1]) {
                val child = children.targets[i]
                if (--unemittedParents[child] == 0) ready.add(child)
            }
        }

        if (emitted != size) {
            // Whatever was left has an unemitted parent, which in a finite graph means a cycle.
            requireAcyclic(Array(size) { graph.parentsOf(it) }, graph::describe)
            error("$emitted of $size commits ordered, but no cycle was found")
        }
        return order
    }

    private fun earliestFirst(graph: CommitGraph): Comparator<Int> = Comparator { a, b ->
        val byTime = graph.timeOf(a).compareTo(graph.timeOf(b))
        if (byTime != 0) byTime else a.compareTo(b)
    }
}

/**
 * Child adjacency in compressed form: [starts] slices [targets] per commit. Built once per ordering
 * pass, because [CommitGraph] stores parents and Kahn's algorithm walks the edges the other way.
 */
private class ChildEdges(val starts: IntArray, val targets: IntArray) {

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
