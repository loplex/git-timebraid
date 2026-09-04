package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The contracts a [MergePlan] has to satisfy whatever history it was built from. Stated once here
 * and asserted both on the hand-written fixtures and over the fuzz corpus.
 */
object PlanInvariants {

    fun assertAll(plan: MergePlan, heads: IntArray, label: String) {
        assertWriteOrder(plan, label)
        assertOriginalEdgesKept(plan, label)
        assertNoDuplicateParents(plan, label)
        assertBraidIsTheFirstParentChains(plan, heads, label)
        assertBraidEdges(plan, label)
        assertAccumulation(plan, label)
    }

    /** Every commit appears exactly once, and after all of its parents. */
    fun assertWriteOrder(plan: MergePlan, label: String) {
        val graph = plan.graph
        assertEquals(graph.size, plan.commits.size, "$label: plan does not cover the graph")

        val written = BooleanArray(graph.size)
        for (planned in plan.commits) {
            val commit = planned.commit.index
            assertTrue(!written[commit], "$label: ${graph.describe(commit)} planned twice")
            for (parent in planned.parents) {
                assertTrue(
                    written[parent],
                    "$label: ${graph.describe(commit)} is written before its parent " +
                        graph.describe(parent),
                )
            }
            written[commit] = true
        }
    }

    /** The rule adds parents and never removes one. */
    fun assertOriginalEdgesKept(plan: MergePlan, label: String) {
        val graph = plan.graph
        for (commit in 0 until graph.size) {
            val now = plan.parentsOf(commit)
            for (parent in graph.parentsOf(commit)) {
                assertTrue(
                    now.any { it == parent },
                    "$label: ${graph.describe(commit)} lost its original parent " +
                        graph.describe(parent),
                )
            }
            assertTrue(
                now.size - graph.parentsOf(commit).size in 0..1,
                "$label: ${graph.describe(commit)} gained more than one parent",
            )
        }
    }

    fun assertNoDuplicateParents(plan: MergePlan, label: String) {
        for (commit in 0 until plan.graph.size) {
            val parents = plan.parentsOf(commit)
            assertEquals(
                parents.size,
                parents.toSet().size,
                "$label: duplicate parent on ${plan.graph.describe(commit)}",
            )
        }
    }

    /** The braid covers exactly the union of the heads' first-parent chains, without duplicates. */
    fun assertBraidIsTheFirstParentChains(plan: MergePlan, heads: IntArray, label: String) {
        val expected = LinkedHashSet<Int>()
        for (head in heads) {
            var commit = head
            while (commit != CommitGraph.NO_COMMIT && expected.add(commit)) {
                commit = plan.graph.firstParentOf(commit)
            }
        }

        assertEquals(expected.size, plan.braid.size, "$label: braid has duplicates or gaps")
        assertEquals(expected, plan.braid.toSet(), "$label: braid covers the wrong commits")
        for (commit in 0 until plan.graph.size) {
            assertEquals(
                commit in expected,
                plan.isOnBraid(commit),
                "$label: wrong braid membership for ${plan.graph.describe(commit)}",
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
                "$label: ${plan.graph.describe(commit)} does not follow " +
                    plan.graph.describe(predecessor),
            )
        }
    }

    /**
     * The accumulation invariant, and with it the whole promise of the tool: a commit's content map
     * is its first parent's map with its own repository's entry replaced by itself. Checked
     * symbolically, without a single git object.
     */
    fun assertAccumulation(plan: MergePlan, label: String) {
        val graph = plan.graph
        for (commit in 0 until graph.size) {
            val expected = when (val firstParent = plan.parentsOf(commit).firstOrNull()) {
                null -> IntArray(graph.sourceCount) { CommitGraph.NO_COMMIT }
                else -> plan.contentOf(firstParent).copyOf()
            }
            expected[graph.sourceOf(commit)] = commit

            assertEquals(
                expected.toList(),
                plan.contentOf(commit).toList(),
                "$label: wrong content map at ${graph.describe(commit)}",
            )
        }
    }
}
