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
        val names = listOf("a1", "a2", "a3")
        // a1 -> a2 -> a3 -> a1
        val parents = mapOf("a1" to listOf("a3"), "a2" to listOf("a1"), "a3" to listOf("a2"))

        val failure = assertThrows<CyclicGraphException> {
            requireAcyclic(graphOf(names) { parents.getValue(it) }.numbered())
        }

        assertTrue(failure.message!!.startsWith("cycle in the parent chain:"))
        for (name in names) assertTrue(failure.message!!.contains(name), failure.message)
    }

    @Test
    fun `requireAcyclic accepts a deep chain without exhausting the stack`() {
        val depth = 200_000
        val chain = (0 until depth).toList()

        requireAcyclic(graphOf(chain) { if (it == 0) emptyList() else listOf(it - 1) }.numbered())
    }
}
