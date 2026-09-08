package cz.loplex.timebraid.plan

import java.util.PriorityQueue

/**
 * Kahn's algorithm over [graph], [precedence] deciding between the nodes that are ready at any
 * moment. Both passes of this package are this walk; they differ in which graph they hand it and
 * what they keep of the result, not in what it does.
 *
 * Keep the set of nodes whose parents have all been emitted, and always take the one
 * [precedence] puts first. Membership of that ready set means no ancestry relation connects those nodes —
 * if `x` were an ancestor of `y`, `y` could not have entered the set before `x` was emitted — so
 * parents-before-children holds by construction rather than by a comparator that has to be trusted
 * to be consistent.
 *
 * Ordering part of a graph is done by handing over that part: a [Graph] reports the nodes it has and
 * the edges between them, so a part reports the parents that are in it and the walk waits for
 * nothing outside it.
 *
 * The walk runs on [IndexedGraph.indexOf] — counting edges down wants flat arrays rather than a graph
 * of objects, and the numbering a caller already has spares it a map from node to slot. A caller
 * passes a graph and gets its nodes back.
 *
 * [precedence] chooses among the nodes that are ready, and only among those, so it decides which
 * valid order comes out and never whether the order is valid — an inconsistent comparator cannot
 * produce a child before its parent here. Ties break on the index a node carries, which is what makes
 * the result a deterministic function of the input: the same graph gives byte-identical output in
 * this run and in any other, and two distinct nodes can never compare equal and collapse.
 */
internal class KahnOrder<T : Any>(
    private val graph: IndexedGraph<T>,
    private val precedence: Comparator<T>,
) {

    private val space = graph.indexSpace

    /** The node at each index, `null` where the index belongs to a node outside this graph. */
    private val nodeAt = MutableList<T?>(space) { null }

    /** Parents of every node, by index; `null` where this graph has no node at that index. */
    private val edges = arrayOfNulls<IntArray>(space)

    init {
        for (node in graph.nodes) {
            val at = graph.indexOf(node)
            nodeAt[at] = node
            val parents = graph.parentsOf(node)
            edges[at] = IntArray(parents.size) { graph.indexOf(parents[it]) }
        }
    }

    /**
     * @return every node exactly once, parents before children.
     * @throws CyclicGraphException if the edges are not a DAG.
     */
    fun order(): List<T> {
        val size = graph.nodes.size
        val children = childEdges()
        val unemitted = IntArray(space) { edges[it]?.size ?: 0 }

        val ready = PriorityQueue(maxOf(1, size), readyFirst())
        for (node in graph.nodes) {
            val at = graph.indexOf(node)
            if (unemitted[at] == 0) ready.add(at)
        }

        val order = ArrayList<T>(size)
        while (ready.isNotEmpty()) {
            val at = ready.poll()
            order += nodeAt[at]!!
            for (i in children.starts[at] until children.starts[at + 1]) {
                val child = children.targets[i]
                if (--unemitted[child] == 0) ready.add(child)
            }
        }

        if (order.size != size) {
            // Whatever was left has an unemitted parent, which in a finite graph means a cycle.
            requireAcyclic(graph)
            // Not worth a caller's wording: reaching this means the cycle check above disagrees with
            // the walk, which is a bug here rather than anything the caller did.
            error("${order.size} of $size nodes were ordered, but no cycle was found")
        }
        return order
    }

    private fun readyFirst(): Comparator<Int> = Comparator { a, b ->
        val byPrecedence = precedence.compare(nodeAt[a]!!, nodeAt[b]!!)
        if (byPrecedence != 0) byPrecedence else a.compareTo(b)
    }

    /**
     * The edges pointing the other way.
     *
     * A node records its parents, because that is what a commit stores; the walk goes from a node to
     * the ones that depend on it, so it needs children. Building that adjacency is linear.
     */
    private fun childEdges(): ChildEdges {
        val starts = IntArray(space + 1)
        for (at in 0 until space) {
            val parents = edges[at] ?: continue
            for (parent in parents) starts[parent + 1]++
        }
        for (at in 0 until space) starts[at + 1] += starts[at]
        val cursor = starts.copyOf(space)
        val targets = IntArray(starts[space])
        for (at in 0 until space) {
            val parents = edges[at] ?: continue
            for (parent in parents) targets[cursor[parent]++] = at
        }
        return ChildEdges(starts, targets)
    }

    private class ChildEdges(val starts: IntArray, val targets: IntArray)
}
