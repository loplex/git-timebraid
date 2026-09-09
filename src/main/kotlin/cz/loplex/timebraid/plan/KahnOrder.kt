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

    private val adjacency = Adjacency.of(graph)

    /**
     * @return every node exactly once, parents before children.
     * @throws CyclicGraphException if the edges are not a DAG.
     */
    fun order(): List<T> {
        val size = graph.nodes.size
        val unemitted = adjacency.parentCounts.copyOf()
        val nodeAt = adjacency.nodeAt

        val ready = PriorityQueue(maxOf(1, size), readyFirst())
        for (node in graph.nodes) {
            if (unemitted[graph.indexOf(node)] == 0) ready.add(node)
        }

        val order = ArrayList<T>(size)
        while (ready.isNotEmpty()) {
            val node = ready.poll()
            order += node
            val at = graph.indexOf(node)
            for (edge in adjacency.childStarts[at] until adjacency.childStarts[at + 1]) {
                val child = adjacency.childTargets[edge]
                if (--unemitted[child] == 0) ready.add(nodeAt[child]!!)
            }
        }

        if (order.size != size) {
            // Whatever was left has an unemitted parent, which in a finite graph means a cycle. The
            // check walks the adjacency this walk already built rather than deriving its own.
            requireAcyclic(graph, adjacency)
            // Not worth a caller's wording: reaching this means the cycle check above disagrees with
            // the walk, which is a bug here rather than anything the caller did.
            error("${order.size} of $size nodes were ordered, but no cycle was found")
        }
        return order
    }

    private fun readyFirst(): Comparator<T> = Comparator { a, b ->
        val byPrecedence = precedence.compare(a, b)
        if (byPrecedence != 0) byPrecedence else graph.indexOf(a).compareTo(graph.indexOf(b))
    }
}
