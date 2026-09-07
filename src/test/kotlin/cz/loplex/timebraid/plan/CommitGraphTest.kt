package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommitGraphTest {

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
}
