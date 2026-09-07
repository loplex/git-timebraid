package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommitGraphTest {

    @Test
    fun `rejects a parent index outside the graph`() {
        val failure = assertThrows<IllegalArgumentException> {
            graphOf(
                parents = arrayOf(intArrayOf(), intArrayOf(7)),
                sourceIndex = intArrayOf(0, 0),
                orderingTime = longArrayOf(1, 2),
                commitIds = arrayOf("a1", "a2"),
            )
        }
        assertTrue(failure.message!!.contains("parent index 7"))
    }

    @Test
    fun `rejects a commit that is its own parent`() {
        assertThrows<IllegalArgumentException> {
            graphOf(
                parents = arrayOf(intArrayOf(0)),
                sourceIndex = intArrayOf(0),
                orderingTime = longArrayOf(1),
                commitIds = arrayOf("a1"),
            )
        }
    }

    @Test
    fun `rejects the same parent listed twice`() {
        val failure = assertThrows<IllegalArgumentException> {
            graphOf(
                parents = arrayOf(intArrayOf(), intArrayOf(0, 0)),
                sourceIndex = intArrayOf(0, 0),
                orderingTime = longArrayOf(1, 2),
                commitIds = arrayOf("a1", "a2"),
            )
        }
        assertTrue(failure.message!!.contains("twice"))
    }

    @Test
    fun `copies the arrays it is given`() {
        val parents = arrayOf(intArrayOf(), intArrayOf(0))
        val times = longArrayOf(1, 2)
        val graph = graphOf(parents, intArrayOf(0, 0), times, arrayOf("a1", "a2"))

        parents[1][0] = 1
        times[0] = 99

        assertEquals(graph.commits[0], graph.commits[1].firstParent)
        assertEquals(1L, graph.commits[0].time)
    }

    @Test
    fun `a root commit has no first parent`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@20")
        assertNull(spec.commit("a1").firstParent)
        assertEquals(spec.commit("a1"), spec.commit("a2").firstParent)
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

    private fun graphOf(
        parents: Array<IntArray>,
        sourceIndex: IntArray,
        orderingTime: LongArray,
        commitIds: Array<String>,
        sourceNames: List<String> = listOf("A"),
    ) = CommitGraph.of(parents, sourceIndex, orderingTime, commitIds, sourceNames)
}
