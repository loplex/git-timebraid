package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReparentTest {

    @Test
    fun `prepends the braid edge when the predecessor comes from another repository`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")
        val parents = reparented(spec, "a3", "b2")

        // b1 follows a1 in time, and a1 is not one of its parents, so the edge is added.
        assertEquals(listOf("a1"), spec.names(parents[spec.commit("b1")]))
        // a2 follows b1, and keeps its original parent a1 behind the braid edge.
        assertEquals(listOf("b1", "a1"), spec.names(parents[spec.commit("a2")]))
        assertEquals(listOf("a2", "b1"), spec.names(parents[spec.commit("b2")]))
        assertEquals(listOf("b2", "a2"), spec.names(parents[spec.commit("a3")]))
    }

    @Test
    fun `adds nothing when the predecessor is already a parent`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 <- a3@30")
        val parents = reparented(spec, "a3")

        assertEquals(listOf("a1"), spec.names(parents[spec.commit("a2")]))
        assertEquals(listOf("a2"), spec.names(parents[spec.commit("a3")]))
        for (commit in spec.graph.commits) {
            assertEquals(commit.parents.size, parents[commit].size)
        }
    }

    @Test
    fun `the first commit of the braid keeps no braid edge`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 | B: b1@20")
        val parents = reparented(spec, "a2", "b1")

        assertEquals(emptyList<String>(), spec.names(parents[spec.commit("a1")]))
    }

    @Test
    fun `a merge on the braid gets three parents and loses no original edge`() {
        // b3 merges the feature branch f1 into b2; the commit before it in time comes from A.
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@40 | B: b1@20 <- b2@30 ; f1(b1)@25 <- b3(b2,f1)@50"
        )
        val parents = reparented(spec, "a2", "b3")

        assertEquals(listOf("a2", "b2", "f1"), spec.names(parents[spec.commit("b3")]))
        assertOriginalEdgesKept(spec.graph, parents)
    }

    @Test
    fun `commits off the braid keep their parents unchanged`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@40 ; f1(a1)@20 <- f2@30 | B: b1@25")
        val parents = reparented(spec, "a2", "b1")

        assertEquals(listOf("a1"), spec.names(parents[spec.commit("f1")]))
        assertEquals(listOf("f1"), spec.names(parents[spec.commit("f2")]))
    }

    @Test
    fun `never lists the same parent twice`() {
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@40 | B: b1@20 <- b2@30 ; f1(b1)@25 <- b3(b2,f1)@50"
        )
        val parents = reparented(spec, "a2", "b3")

        for (commit in spec.graph.commits) {
            val row = parents[commit]
            assertEquals(row.size, row.toSet().size, "duplicate parent on $commit")
        }
    }

    @Test
    fun `the result is still a DAG that can be ordered`() {
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@40 ; f1(a1)@20 <- m(a2,f1)@50 | B: b1@15 <- b2@30"
        )
        val parents = reparented(spec, "m", "b2")

        val order = topoOrder(spec.graph.withParents { parents[it] })

        assertEquals(spec.graph.size, order.size)
    }

    @Test
    fun `a braid order that contradicts ancestry fails loudly instead of writing a broken history`() {
        // Handed a braid in which a commit precedes its own ancestor — the shape any ordering that
        // does not respect ancestry produces — the braid edge from c to g closes a loop back through
        // p. Nothing may be written after that, so it has to be an exception and not a warning.
        val spec = GraphSpec.parse("A: g@10 <- p@20 <- c@30")

        val failure = assertThrows<CyclicGraphException> {
            reparent(spec.graph, spec.commits("c", "g"))
        }

        assertTrue(failure.message!!.contains("cycle in the parent chain"), failure.message)
        for (name in listOf("g", "p", "c")) {
            assertTrue(failure.message!!.contains("A/$name"), failure.message)
        }
    }

    private fun reparented(spec: GraphSpec, vararg heads: String): ParentEdges {
        val braid = BraidInterleave.compute(spec.graph, spec.commits(*heads), interleaveTips = emptyList())
        return reparent(spec.graph, braid)
    }

    private fun assertOriginalEdgesKept(graph: CommitGraph, parents: ParentEdges) {
        for (commit in graph.commits) {
            for (parent in commit.parents) {
                assertTrue(parent in parents[commit], "$commit lost its original parent $parent")
            }
        }
    }
}
