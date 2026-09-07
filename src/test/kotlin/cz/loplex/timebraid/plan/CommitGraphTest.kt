package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommitGraphTest {

    @Test
    fun `rejects a parent index outside the graph`() {
        val failure = assertThrows<IllegalArgumentException> {
            CommitGraph(
                parents = arrayOf(intArrayOf(), intArrayOf(7)),
                sourceIndex = intArrayOf(0, 0),
                orderingTime = longArrayOf(1, 2),
                commitIds = arrayOf("a1", "a2"),
                sourceNames = listOf("A"),
            )
        }
        assertTrue(failure.message!!.contains("parent index 7"))
    }

    @Test
    fun `rejects a commit that is its own parent`() {
        assertThrows<IllegalArgumentException> {
            CommitGraph(
                parents = arrayOf(intArrayOf(0)),
                sourceIndex = intArrayOf(0),
                orderingTime = longArrayOf(1),
                commitIds = arrayOf("a1"),
                sourceNames = listOf("A"),
            )
        }
    }

    @Test
    fun `rejects the same parent listed twice`() {
        val failure = assertThrows<IllegalArgumentException> {
            CommitGraph(
                parents = arrayOf(intArrayOf(), intArrayOf(0, 0)),
                sourceIndex = intArrayOf(0, 0),
                orderingTime = longArrayOf(1, 2),
                commitIds = arrayOf("a1", "a2"),
                sourceNames = listOf("A"),
            )
        }
        assertTrue(failure.message!!.contains("twice"))
    }

    @Test
    fun `copies the arrays it is given`() {
        val parents = arrayOf(intArrayOf(), intArrayOf(0))
        val times = longArrayOf(1, 2)
        val graph = CommitGraph(parents, intArrayOf(0, 0), times, arrayOf("a1", "a2"), listOf("A"))

        parents[1][0] = 1
        times[0] = 99

        assertEquals(0, graph.firstParentOf(1))
        assertEquals(1L, graph.timeOf(0))
    }

    @Test
    fun `first parent of a root commit is NO_COMMIT`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@20")
        assertEquals(CommitGraph.NO_COMMIT, spec.graph.firstParentOf(spec.id("a1")))
        assertEquals(spec.id("a1"), spec.graph.firstParentOf(spec.id("a2")))
    }

    @Test
    fun `withParents keeps identity and timestamps and replaces only the edges`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 | B: b1@15")
        val braided = spec.graph.withParents(
            arrayOf(intArrayOf(), intArrayOf(spec.id("b1"), spec.id("a1")), intArrayOf(spec.id("a1")))
        )

        assertEquals("a2", braided.idOf(spec.id("a2")))
        assertEquals(20L, braided.timeOf(spec.id("a2")))
        assertEquals("B", braided.commits[spec.id("b1")].source.name)
        assertEquals(listOf("b1", "a1"), spec.names(braided.parentsOf(spec.id("a2"))))
    }

    @Test
    fun `requireAcyclic names the commits of the cycle it finds`() {
        // a1 -> a2 -> a3 -> a1
        val parents = arrayOf(intArrayOf(2), intArrayOf(0), intArrayOf(1))
        val names = arrayOf("a1", "a2", "a3")

        val failure = assertThrows<CyclicGraphException> {
            requireAcyclic(parents) { names[it] }
        }

        assertTrue(failure.message!!.startsWith("cycle in the parent chain:"))
        for (name in names) assertTrue(failure.message!!.contains(name), failure.message)
    }

    @Test
    fun `requireAcyclic accepts a deep chain without exhausting the stack`() {
        val depth = 200_000
        val parents = Array(depth) { if (it == 0) intArrayOf() else intArrayOf(it - 1) }

        requireAcyclic(parents)
    }
}
