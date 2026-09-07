package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommitGraphBuilderTest {

    @Test
    fun `hands out dense indices`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        val b = builder.addSource("B")

        assertEquals(0, builder.addCommit(a, "a1", 10))
        assertEquals(1, builder.addCommit(a, "a2", 20, listOf("a1")))
        assertEquals(2, builder.addCommit(b, "b1", 15))

        val graph = builder.build()
        assertEquals(3, graph.size)
        assertEquals(2, graph.sourceCount)
    }

    @Test
    fun `accepts a parent that is added later`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        // A log is normally walked newest first, so a commit names parents that are still unknown.
        builder.addCommit(a, "a2", 20, listOf("a1"))
        builder.addCommit(a, "a1", 10)

        val graph = builder.build()
        assertEquals("a1", graph.idOf(graph.firstParentOf(builder.indexOf(a, "a2"))))
    }

    @Test
    fun `the same commit id in two repositories is two commits`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        val b = builder.addSource("B")
        val inA = builder.addCommit(a, "5c1a9f2", 10)
        val inB = builder.addCommit(b, "5c1a9f2", 20)

        assertNotEquals(inA, inB)
        val graph = builder.build()
        assertEquals("A", graph.commits[inA].source.name)
        assertEquals("B", graph.commits[inB].source.name)
    }

    @Test
    fun `drops a duplicate parent, keeping the first occurrence`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a1", 10)
        builder.addCommit(a, "f1", 15, listOf("a1"))
        val merge = builder.addCommit(a, "m", 20, listOf("a1", "f1", "a1"))

        val graph = builder.build()
        assertEquals(listOf("a1", "f1"), graph.parentsOf(merge).map { graph.idOf(it) })
    }

    @Test
    fun `an unresolved parent is an incomplete history, not a silent gap`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a2", 20, listOf("a1"))

        val failure = assertThrows<IllegalStateException> { builder.build() }

        assertTrue(failure.message!!.contains("A/a1"))
        assertTrue(failure.message!!.contains("shallow"))
    }

    @Test
    fun `rejects the same commit added twice`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a1", 10)

        assertThrows<IllegalStateException> { builder.addCommit(a, "a1", 10) }
    }

    @Test
    fun `rejects a commit that is its own parent`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")

        assertThrows<IllegalArgumentException> { builder.addCommit(a, "a1", 10, listOf("a1")) }
    }

    @Test
    fun `indexOf reports an unknown commit rather than inventing one`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a1", 10)

        assertEquals(CommitGraph.NO_COMMIT, builder.indexOf(a, "nope"))
        assertEquals(1, builder.build().size)
    }
}
