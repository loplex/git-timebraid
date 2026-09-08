package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommitGraphBuilderTest {

    @Test
    fun `hands out the commits it then builds the graph from`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        val b = builder.addSource("B")

        val a1 = builder.addCommit(a, "a1", 10)
        val a2 = builder.addCommit(a, "a2", 20, listOf("a1"))
        val b1 = builder.addCommit(b, "b1", 15)

        val graph = builder.build()
        assertEquals(3, graph.size)
        assertEquals(2, graph.sources.size)
        assertEquals(listOf(a1, a2, b1), graph.commits)
    }

    @Test
    fun `accepts a parent that is added later`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        // A log is normally walked newest first, so a commit names parents that are still unknown.
        val a2 = builder.addCommit(a, "a2", 20, listOf("a1"))
        builder.addCommit(a, "a1", 10)

        builder.build()
        assertEquals("a1", a2.firstParent?.id)
    }

    @Test
    fun `the same commit id in two repositories is two commits`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        val b = builder.addSource("B")
        val inA = builder.addCommit(a, "5c1a9f2", 10)
        val inB = builder.addCommit(b, "5c1a9f2", 20)

        assertNotEquals(inA, inB)
        builder.build()
        assertEquals("A", inA.source.name)
        assertEquals("B", inB.source.name)
    }

    @Test
    fun `drops a duplicate parent, keeping the first occurrence`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a1", 10)
        builder.addCommit(a, "f1", 15, listOf("a1"))
        val merge = builder.addCommit(a, "m", 20, listOf("a1", "f1", "a1"))

        builder.build()
        assertEquals(listOf("a1", "f1"), merge.parents.map { it.id })
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
    fun `a commit named only as a parent has no data of its own`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        val a2 = builder.addCommit(a, "a2", 20, listOf("a1"))

        val failure = assertThrows<IllegalStateException> { a2.firstParent!!.time }

        assertTrue(failure.message!!.contains("A/a1"))
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
    fun `find reports an unknown commit rather than inventing one`() {
        val builder = CommitGraphBuilder()
        val a = builder.addSource("A")
        builder.addCommit(a, "a1", 10)

        assertNull(builder.find(a, "nope"))
        assertEquals(1, builder.build().size)
    }

    @Test
    fun `a repository of another builder is refused rather than silently interned`() {
        val builder = CommitGraphBuilder()
        val elsewhere = CommitGraphBuilder().addSource("A")

        assertThrows<IllegalArgumentException> { builder.addCommit(elsewhere, "a1", 10) }
    }
}
