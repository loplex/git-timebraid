package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MergePlanTest {

    @Test
    fun `plans two interleaved repositories`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")
        val heads = spec.commits("a3", "b2")

        val plan = spec.graph.braid(heads).plan(spec.subdirs())

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), spec.names(plan.braid))
        assertEquals(
            listOf("a1", "b1", "a2", "b2", "a3"),
            plan.commits.map { it.commit.id },
        )
        PlanInvariants.assertAll(plan, heads, "interleaved")
    }

    @Test
    fun `accumulates the content of every repository along the braid`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")
        val plan = spec.graph.braid(spec.commits("a3", "b2")).plan(spec.subdirs())

        // At b2 the world is: A as of a2, B as of b2 — the state at that moment, not an approximation.
        assertEquals(listOf("a2", "b2"), contentNames(spec, plan, "b2"))
        // Before B existed at all, only A is present.
        assertEquals(listOf("a1"), contentNames(spec, plan, "a1"))
        assertEquals(listOf("a1", "b1"), contentNames(spec, plan, "b1"))
    }

    @Test
    fun `a branch that exists in one repository still carries every repository`() {
        // f1 and f2 are a side branch of A cut after b1 was already braided in.
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 ; f1(a2)@60 <- f2@70 | B: b1@20 <- b2@40")
        val plan = spec.graph.braid(spec.commits("a2", "b2")).plan(spec.subdirs())

        assertEquals(listOf("f2", "b1"), contentNames(spec, plan, "f2"))
        // B is frozen at whatever it was when the branch was cut, A follows the branch.
        assertEquals(listOf("a2", "b1"), contentNames(spec, plan, "a2"))
    }

    @Test
    fun `a merge on the braid is planned with three parents`() {
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@40 | B: b1@20 <- b2@30 ; f1(b1)@25 <- b3(b2,f1)@50"
        )
        val heads = spec.commits("a2", "b3")

        val plan = spec.graph.braid(heads).plan(spec.subdirs())

        assertEquals(listOf("a2", "b2", "f1"), spec.names(plan.parentsOf(spec.commit("b3"))))
        PlanInvariants.assertAll(plan, heads, "merge on the braid")
        assertTrue(plan.summary().contains("3 -> 1"), plan.summary())
    }

    @Test
    fun `one repository may be placed at the root`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 | B: b1@20")
        val plan = spec.graph.braid(spec.commits("a2", "b1")).plan(spec.subdirs(null, "webui"))

        assertNull(plan.subdirOf(spec.commit("a1")))
        assertEquals("webui", plan.subdirOf(spec.commit("b1")))
        assertTrue(plan.summary().contains("A -> <root>"), plan.summary())
    }

    @Test
    fun `rejects two repositories placed in the same subdirectory`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        val failure = assertThrows<IllegalArgumentException> {
            spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs("shared", "shared"))
        }

        assertTrue(failure.message!!.contains("same subdirectory"))
    }

    @Test
    fun `rejects more than one repository at the root`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        assertThrows<IllegalArgumentException> {
            spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs(null, null))
        }
    }

    @Test
    fun `accepts a nested subdirectory path`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        val plan = spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs("libs/a", "apps/b"))

        assertEquals("libs/a", plan.subdirOf(spec.commit("a1")))
        assertEquals("apps/b", plan.subdirOf(spec.commit("b1")))
    }

    @Test
    fun `rejects a subdirectory path with a segment the output cannot hold`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        val unusable = listOf(
            "..", ".", " ", "a//b", "/a", "a/", "a/../b", "a/./b", ".git", ".GIT", "libs/.git/a", "libs/.Git/a",
        )
        for (subdir in unusable) {
            assertThrows<IllegalArgumentException>("'$subdir' should not be a usable subdirectory") {
                spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs(subdir, "B"))
            }
        }
    }

    @Test
    fun `rejects one repository placed inside another's subdirectory`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        // One level down as well as at the top: the containing destination can sit at any depth.
        for ((outer, inner) in listOf("libs" to "libs/b", "libs/a" to "libs/a/b")) {
            val error = assertThrows<IllegalArgumentException> {
                spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs(outer, inner))
            }
            assertTrue(error.message!!.contains("one contains the other"), error.message)
        }
    }

    @Test
    fun `rejects git's own directory as a subdirectory, and only that name`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        for (subdir in listOf(".git", ".GIT", ".Git")) {
            assertThrows<IllegalArgumentException>("'$subdir' should not be a usable subdirectory") {
                spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs(subdir, "B"))
            }
        }
        for (subdir in listOf("git", ".github", ".gitx")) {
            val plan = spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs(subdir, "B"))
            assertEquals(subdir, plan.subdirOf(spec.commit("a1")))
        }
    }

    @Test
    fun `rejects a set of subdirectories that does not cover the repositories`() {
        val spec = GraphSpec.parse("A: a1@10 | B: b1@20")

        assertThrows<IllegalArgumentException> {
            spec.graph.braid(spec.commits("a1", "b1")).plan(spec.subdirs("A"))
        }
    }

    @Test
    fun `renders a plan that can simply be diffed`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 | B: b1@20 <- b2@40")
        val plan = spec.graph.braid(spec.commits("a2", "b2")).plan(spec.subdirs())

        assertEquals(
            """
            repositories:
              A -> A/
              B -> B/
            commits: 4 (braid: 4)
            parent counts: 0 -> 1, 1 -> 1, 2 -> 2

            000000 * A/a1 @10 parents=[] content=[A=a1]
            000001 * B/b1 @20 parents=[A/a1] content=[A=a1, B=b1]
            000002 * A/a2 @30 parents=[B/b1, A/a1] content=[A=a2, B=b1]
            000003 * B/b2 @40 parents=[A/a2, B/b1] content=[A=a2, B=b2]
            """.trimIndent() + "\n",
            plan.render(),
        )
    }

    @Test
    fun `marks commits that are not on the braid`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 ; f1(a1)@20")
        val plan = spec.graph.braid(spec.commits("a2")).plan(spec.subdirs())

        assertTrue(plan.render().contains("   A/f1"), plan.render())
        assertTrue(plan.render().contains(" * A/a2"), plan.render())
    }

    private fun contentNames(spec: GraphSpec, plan: MergePlan, name: String): List<String> =
        plan.contentOf(spec.commit(name)).values.map { it.id }
}
