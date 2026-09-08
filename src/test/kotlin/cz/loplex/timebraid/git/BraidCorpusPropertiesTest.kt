package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.BraidInterleave
import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.WholeGraphBraid
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * The properties [BraidInterleave] promises, checked against real repositories rather than the
 * synthetic graphs the unit tests use — thousands of commits with timestamps that genuinely run
 * backwards here and there, which is what these properties exist to survive:
 *
 * 1. **The write order is topological over the braided history.** Every parent, original or braid,
 *    comes before its child, so no commit is ever written referring to an object that does not exist.
 * 2. **No braid edge points into the future.** Where a braid commit's predecessor comes from another
 *    repository, that predecessor is never timestamped later than the commit itself.
 *
 * It also reports, without asserting on it, how much the braid would differ if it were interleaved
 * over the whole graph instead of over the mainline chains alone ([WholeGraphBraid]) — the difference
 * being merges whose merged-in branch is timestamped after the merge itself. On the three-repository,
 * 14 387-commit history this was developed against, the two came out identical: 0 of 6929 braid
 * positions moved. That number is a measurement of one corpus, not a guarantee, which is exactly why
 * it is printed rather than asserted.
 *
 * Opt-in, because a corpus of large real repositories does not belong in this tree. Point
 * `-Dtimebraid.corpus` at a directory of bare clones:
 *
 * ```
 * mvn -q test -Dtest=BraidCorpusPropertiesTest -Dtimebraid.corpus=/path/to/bare-clones
 * ```
 */
@EnabledIfSystemProperty(named = "timebraid.corpus", matches = ".+")
class BraidCorpusPropertiesTest {

    @Test
    fun `the braid over a real corpus is writable in order and never points into the future`() {
        val dir = Path.of(System.getProperty("timebraid.corpus"))
        val repoDirs = dir.listDirectoryEntries("*.git").filter { it.isDirectory() }.sorted()
        assertTrue(repoDirs.isNotEmpty()) { "no *.git directories under $dir" }

        val braidInputs = repoDirs.map { SourceRepository.open(it) }.let { repos ->
            try {
                CommitGraphReader.read(repos, OrderBy.COMMITTER)
            } finally {
                repos.forEach { it.close() }
            }
        }
        val graph = braidInputs.graph

        // build() reparents and orders, and fails loudly on a braid that contradicts ancestry, so
        // getting a plan back at all is already part of what is being checked here.
        val plan = graph.braid(braidInputs.heads).plan(graph.sources.associateWith { it.name })
        val wholeGraph = WholeGraphBraid.compute(graph, braidInputs.heads)

        val futureEdges = futureBraidEdges(plan.braid)
        val report = buildString {
            appendLine("corpus: $dir")
            appendLine("repositories: ${graph.sources}")
            appendLine("commits: ${graph.size}, braid: ${plan.braid.size}")
            appendLine("write order is topological: ${isTopological(plan)}")
            appendLine("braid edges pointing into the future: ${futureEdges.size}")
            futureEdges.take(10).forEach { appendLine("  $it") }
            appendLine(interleavingDiff(plan.braid, wholeGraph))
        }
        println(report)

        assertTrue(isTopological(plan)) { report }
        assertTrue(futureEdges.isEmpty()) { report }
    }

    /** Every parent of every commit, after reparenting, precedes it in the plan's write order. */
    private fun isTopological(plan: MergePlan): Boolean {
        val position = HashMap<Commit, Int>()
        for ((index, planned) in plan.commits.withIndex()) position[planned.commit] = index
        for (planned in plan.commits) {
            for (parent in planned.parents) {
                if (position.getValue(parent) >= position.getValue(planned.commit)) return false
            }
        }
        return true
    }

    /**
     * Braid members whose predecessor comes from another repository and carries a later timestamp.
     * Same-repository predecessors are exempt: their order is forced by ancestry, not decided by
     * time, and there the braid adds no edge at all — the predecessor already is the first parent.
     */
    private fun futureBraidEdges(braid: List<Commit>): List<String> {
        val lines = ArrayList<String>()
        for (i in 1 until braid.size) {
            val commit = braid[i]
            val predecessor = braid[i - 1]
            if (predecessor.source == commit.source) continue
            if (predecessor.time > commit.time) {
                lines += "$predecessor @${predecessor.time} precedes " +
                    "$commit @${commit.time} but is later"
            }
        }
        return lines
    }

    private fun interleavingDiff(braid: List<Commit>, other: List<Commit>): String {
        if (other.size != braid.size) return "a whole-graph interleave covers a different commit set"
        val position = HashMap<Commit, Int>()
        for ((index, commit) in braid.withIndex()) position[commit] = index
        var moved = 0
        var maxShift = 0
        for ((index, commit) in other.withIndex()) {
            val shift = kotlin.math.abs(index - position.getValue(commit))
            if (shift != 0) moved++
            maxShift = maxOf(maxShift, shift)
        }
        return "a whole-graph interleave would move $moved / ${braid.size} braid positions " +
            "(largest shift: $maxShift)"
    }
}
