package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The contracts a [MergePlan] has to satisfy whatever history it was built from. Stated once here
 * and asserted both on the hand-written fixtures and over the fuzz corpus.
 */
object PlanInvariants {

    fun assertAll(plan: MergePlan, heads: List<Commit>, label: String) {
        assertWriteOrder(plan, label)
        assertOriginalEdgesKept(plan, label)
        assertNoDuplicateParents(plan, label)
        assertBraidIsTheFirstParentChains(plan, heads, label)
        assertBraidEdges(plan, label)
        assertAccumulation(plan, label)
    }

    /** Every commit appears exactly once, and after all of its parents. */
    fun assertWriteOrder(plan: MergePlan, label: String) {
        assertEquals(plan.graph.size, plan.commits.size, "$label: plan does not cover the graph")

        val written = HashSet<Commit>()
        for (planned in plan.commits) {
            assertTrue(planned.commit !in written, "$label: ${planned.commit} planned twice")
            for (parent in planned.parents) {
                assertTrue(
                    parent in written,
                    "$label: ${planned.commit} is written before its parent $parent",
                )
            }
            written += planned.commit
        }
    }

    /** The rule adds parents and never removes one. */
    fun assertOriginalEdgesKept(plan: MergePlan, label: String) {
        for (commit in plan.graph.commits) {
            val now = plan.parentsOf(commit)
            for (parent in commit.parents) {
                assertTrue(parent in now, "$label: $commit lost its original parent $parent")
            }
            assertTrue(
                now.size - commit.parents.size in 0..1,
                "$label: $commit gained more than one parent",
            )
        }
    }

    fun assertNoDuplicateParents(plan: MergePlan, label: String) {
        for (commit in plan.graph.commits) {
            val parents = plan.parentsOf(commit)
            assertEquals(parents.size, parents.toSet().size, "$label: duplicate parent on $commit")
        }
    }

    /** The braid covers exactly the union of the heads' first-parent chains, without duplicates. */
    fun assertBraidIsTheFirstParentChains(plan: MergePlan, heads: List<Commit>, label: String) {
        val expected = LinkedHashSet<Commit>()
        for (head in heads) {
            var commit: Commit? = head
            while (commit != null && expected.add(commit)) {
                commit = commit.firstParent
            }
        }

        assertEquals(expected.size, plan.braid.size, "$label: braid has duplicates or gaps")
        assertEquals(expected, plan.braid.toSet(), "$label: braid covers the wrong commits")
        for (commit in plan.graph.commits) {
            assertEquals(
                commit in expected,
                plan.isOnBraid(commit),
                "$label: wrong braid membership for $commit",
            )
        }
    }

    /** Each braid commit has its braid predecessor as a parent, and it is the first one. */
    fun assertBraidEdges(plan: MergePlan, label: String) {
        for (i in 1 until plan.braid.size) {
            val commit = plan.braid[i]
            val predecessor = plan.braid[i - 1]
            assertEquals(
                predecessor,
                plan.parentsOf(commit)[0],
                "$label: $commit does not follow $predecessor",
            )
        }
    }

    /**
     * The accumulation invariant, and with it the whole promise of the tool: a commit's content map
     * is its first parent's map with its own repository's entry replaced by itself. Checked
     * symbolically, without a single git object.
     */
    fun assertAccumulation(plan: MergePlan, label: String) {
        for (commit in plan.graph.commits) {
            val expected = when (val firstParent = plan.parentsOf(commit).firstOrNull()) {
                null -> LinkedHashMap()
                else -> LinkedHashMap(plan.contentOf(firstParent))
            }
            expected[commit.source] = commit

            assertEquals(expected, plan.contentOf(commit), "$label: wrong content map at $commit")
        }
    }
}
