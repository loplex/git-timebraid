package cz.loplex.timebraid.plan

import java.util.PriorityQueue

/**
 * Kahn's algorithm over [nodes], earliest [priority] first among the nodes that are ready at any
 * moment. Both passes of this package are this walk; they differ in which nodes they hand it and
 * what they keep of the result, not in what it does.
 *
 * Keep the set of nodes whose parents have all been emitted, and always take the one with the
 * earliest priority. Membership of that ready set means no ancestry relation connects those nodes —
 * if `x` were an ancestor of `y`, `y` could not have entered the set before `x` was emitted — so
 * parents-before-children holds by construction rather than by a comparator that has to be trusted
 * to be consistent.
 *
 * Ordering part of a graph is done by handing over that part: [parentsOf] is asked only about the
 * nodes given, so a caller that wants a subset filters the parents it reports to that subset, and
 * the walk waits for nothing outside it.
 *
 * The dense numbering the walk runs on is derived here and never leaves. That is the point of the
 * type: it works in indices because counting edges down wants a flat `IntArray` per node rather than
 * a graph of objects, and nobody else has to. A caller passes nodes and gets nodes back.
 *
 * Ties on [priority] break on the position a node was given in, which makes the result a
 * deterministic function of the input: the same nodes in the same order give byte-identical output
 * in this run and in any other, and two distinct nodes of equal priority can never compare equal and
 * collapse.
 *
 * @param what names the nodes in a failure message.
 */
internal class KahnOrder<T : Any>(
    private val nodes: List<T>,
    parentsOf: (T) -> List<T>,
    private val priority: (T) -> Long,
    private val what: String = "nodes",
) {

    private val indices = HashMap<T, Int>(nodes.size * 2)

    /** Parents of every node, as indices into [nodes]. */
    private val edges: Array<IntArray>

    init {
        nodes.forEachIndexed { index, node -> indices[node] = index }
        edges = Array(nodes.size) { index ->
            val parents = parentsOf(nodes[index])
            IntArray(parents.size) { indexOf(parents[it]) }
        }
    }

    private fun indexOf(node: T): Int =
        indices[node] ?: error("$node is not among the $what this order was given")

    /**
     * @return every node exactly once, parents before children.
     * @throws CyclicGraphException if the edges are not a DAG.
     */
    fun order(): List<T> {
        val size = nodes.size
        val children = childEdges()
        val unemitted = IntArray(size) { edges[it].size }

        val ready = PriorityQueue(maxOf(1, size), earliestFirst())
        for (node in 0 until size) {
            if (unemitted[node] == 0) ready.add(node)
        }

        val order = ArrayList<T>(size)
        while (ready.isNotEmpty()) {
            val node = ready.poll()
            order += nodes[node]
            for (i in children.starts[node] until children.starts[node + 1]) {
                val child = children.targets[i]
                if (--unemitted[child] == 0) ready.add(child)
            }
        }

        if (order.size != size) {
            // Whatever was left has an unemitted parent, which in a finite graph means a cycle.
            requireAcyclic(edges) { nodes[it].toString() }
            error("${order.size} of $size $what were ordered, but no cycle was found")
        }
        return order
    }

    private fun earliestFirst(): Comparator<Int> = Comparator { a, b ->
        val byPriority = priority(nodes[a]).compareTo(priority(nodes[b]))
        if (byPriority != 0) byPriority else a.compareTo(b)
    }

    /**
     * The edges pointing the other way.
     *
     * A node records its parents, because that is what a commit stores; the walk goes from a node to
     * the ones that depend on it, so it needs children. Building that adjacency is linear.
     */
    private fun childEdges(): ChildEdges {
        val size = edges.size
        val starts = IntArray(size + 1)
        for (node in 0 until size) {
            for (parent in edges[node]) starts[parent + 1]++
        }
        for (node in 0 until size) starts[node + 1] += starts[node]
        val cursor = starts.copyOf(size)
        val targets = IntArray(starts[size])
        for (node in 0 until size) {
            for (parent in edges[node]) targets[cursor[parent]++] = node
        }
        return ChildEdges(starts, targets)
    }

    private class ChildEdges(val starts: IntArray, val targets: IntArray)
}
