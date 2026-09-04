package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TopoOrderTest {

    @Test
    fun `interleaves two repositories by time`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")

        val order = TopoOrder.compute(spec.graph)

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), spec.names(order))
    }

    @Test
    fun `is a permutation of the graph`() {
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@30 ; f1(a1)@20 <- m(a2,f1)@40 | B: b1@15 <- b2@35"
        )

        val order = TopoOrder.compute(spec.graph)

        assertEquals(spec.graph.size, order.size)
        assertEquals((0 until spec.graph.size).toSet(), order.toSet())
    }

    @Test
    fun `every parent precedes every child`() {
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@30 ; f1(a1)@20 <- m(a2,f1)@40 | B: b1@15 <- b2@35"
        )

        val order = TopoOrder.compute(spec.graph)

        assertParentsFirst(spec.graph, order)
    }

    @Test
    fun `orders a diamond by time between the two sides`() {
        val spec = GraphSpec.parse("A: r@0 <- x@30 ; y(r)@10 <- m(x,y)@40")

        val order = TopoOrder.compute(spec.graph)

        assertEquals(listOf("r", "y", "x", "m"), spec.names(order))
    }

    @Test
    fun `ancestry wins over a child that is older than its parent`() {
        // A rebase leaves the chain in place but its timestamps out of order.
        val spec = GraphSpec.parse("A: p@50 <- c@10 | B: b1@20")

        val order = TopoOrder.compute(spec.graph)

        assertEquals(listOf("b1", "p", "c"), spec.names(order))
        assertParentsFirst(spec.graph, order)
    }

    @Test
    fun `ancestry wins across two generations, not just for a direct parent`() {
        // Ancestry through an intermediate commit, with the timestamps running backwards over it.
        val spec = GraphSpec.parse("A: g@50 <- p@60 <- c@10")

        val order = TopoOrder.compute(spec.graph)

        assertEquals(listOf("g", "p", "c"), spec.names(order))
    }

    @Test
    fun `orders several roots by time`() {
        val spec = GraphSpec.parse("A: a1@30 ; z1@30 | B: b1@10")

        val order = TopoOrder.compute(spec.graph)

        assertParentsFirst(spec.graph, order)
        assertEquals("b1", spec.graph.idOf(order[0]))
    }

    @Test
    fun `breaks a timestamp tie on the commit index, so the result never depends on the run`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 | B: b1@10 <- b2@20")

        val first = TopoOrder.compute(spec.graph)
        val second = TopoOrder.compute(spec.graph)

        // a1 and b1 share a timestamp and no ancestry; the lower index goes first, in every run.
        assertEquals(listOf("a1", "b1", "a2", "b2"), spec.names(first))
        assertArrayEquals(first, second)
    }

    @Test
    fun `a cycle is reported, not silently truncated`() {
        val spec = GraphSpec.parse("A: a1(a2)@10 <- a2@20")

        assertThrows<CyclicGraphException> { TopoOrder.compute(spec.graph) }
    }

    private fun assertParentsFirst(graph: CommitGraph, order: IntArray) {
        val position = IntArray(graph.size)
        for ((index, commit) in order.withIndex()) position[commit] = index
        for (commit in 0 until graph.size) {
            for (parent in graph.parentsOf(commit)) {
                assertTrue(
                    position[parent] < position[commit],
                    "${graph.describe(parent)} must precede ${graph.describe(commit)}",
                )
            }
        }
    }
}
