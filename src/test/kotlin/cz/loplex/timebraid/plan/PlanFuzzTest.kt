package cz.loplex.timebraid.plan

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The invariants of the planner, asserted over a hundred generated histories whose timestamps
 * deliberately run backwards across parent edges — the shape a rebase, a cherry-pick or a skewed
 * clock leaves behind, and the shape hand-written fixtures are least likely to cover.
 */
class PlanFuzzTest {

    @Test
    fun `holds every invariant over a fuzz corpus with inverted timestamps`() {
        for (seed in 1..100) {
            val corpus = RandomGraphs.generate(seed, repositories = 3, commitsPerRepository = 70)
            val subdirs = corpus.graph.sources.associateWith { it.name }

            val plan = corpus.graph.braid(corpus.heads).plan(subdirs)

            PlanInvariants.assertAll(plan, corpus.heads, "seed $seed")
        }
    }

    @Test
    fun `interleaves the repositories rather than concatenating them`() {
        // A corpus that came out sorted by repository would satisfy every invariant above and be
        // useless, so check that the braid actually alternates.
        for (seed in 1..20) {
            val corpus = RandomGraphs.generate(seed, repositories = 3, commitsPerRepository = 70)
            val plan = corpus.graph.braid(corpus.heads).plan(corpus.graph.sources.associateWith { it.name })

            val alternations = (1 until plan.braid.size).count {
                plan.braid[it].source != plan.braid[it - 1].source
            }

            assertTrue(alternations > plan.braid.size / 10, "seed $seed braids too little: $alternations")
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `plans a corpus the size of a real one`() {
        // The prototype's ordering was quadratic and a corpus this size took minutes of it. Nothing
        // here is timed to the millisecond, but a planner that regressed to that shape would not
        // finish inside the timeout.
        val corpus = RandomGraphs.generate(seed = 7, repositories = 3, commitsPerRepository = 5_000)

        val plan = corpus.graph.braid(corpus.heads).plan(corpus.graph.sources.associateWith { it.name })

        PlanInvariants.assertAll(plan, corpus.heads, "15k corpus")
    }

    @Test
    fun `stays deterministic`() {
        for (seed in 1..20) {
            val corpus = RandomGraphs.generate(seed)
            val subdirs = corpus.graph.sources.associateWith { it.name }

            val first = corpus.graph.braid(corpus.heads).plan(subdirs).render()
            val second = corpus.graph.braid(corpus.heads).plan(subdirs).render()

            assertTrue(first == second, "seed $seed produced two different plans")
        }
    }
}
