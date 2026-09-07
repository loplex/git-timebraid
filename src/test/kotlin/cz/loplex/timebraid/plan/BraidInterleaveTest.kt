package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins down the three properties [BraidInterleave]'s doc claims, and the one place where interleaving
 * over mainline chains alone visibly differs from a pass over the whole graph.
 *
 * A k-way merge over per-repository first-parent chains never compares distant commits at all, so
 * there is no comparator that could be non-transitive — ancestry along a chain is respected by
 * construction rather than by a comparison that has to stay consistent. What it deliberately does not
 * do is track a merge commit's *other* parents, which a plain topological pass over the whole graph
 * ([TopoOrder]) does by construction, Kahn's algorithm requiring every parent ready and not only the
 * first. The tests below demonstrate that divergence rather than assume it.
 */
class BraidInterleaveTest {

    @Test
    fun `agrees with a whole-graph interleave on two ordinary linear repositories`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")
        val heads = spec.ids("a3", "b2")

        val wholeGraph = WholeGraphBraid.compute(spec.graph, heads)
        val interleaved = BraidInterleave.compute(spec.graph, heads)

        assertEquals(spec.names(wholeGraph), spec.names(interleaved))
    }

    @Test
    fun `never reorders a repository's own chain, even where its timestamps run backwards`() {
        // A's own chain runs backwards in time. A k-way merge only ever pops from the front of A's
        // own queue, so nothing can move a1/a2 relative to each other regardless of what their
        // timestamps say -- ancestry within one repository holds no matter how its timestamps behave.
        val spec = GraphSpec.parse("A: a1@50 <- a2@10 | B: b1@20 <- b2@40")
        val heads = spec.ids("a2", "b2")

        val order = BraidInterleave.compute(spec.graph, heads)

        assertTrue(respectsFirstParentAncestry(spec.graph, order))
    }

    @Test
    fun `does not track a merge's other parents, unlike a whole-graph pass`() {
        // f is a side branch merged into the mainline at m, timestamped *after* m itself -- a slow
        // review or clock skew can produce exactly this. A whole-graph pass holds m back until f has
        // been emitted, since Kahn's algorithm requires every parent, not only the first, to be
        // ready before a commit joins the ready set. A k-way merge over first-parent chains never
        // looks at f at all, so it places m purely by its own timestamp, one queue advancing
        // independently of the other -- and m's own time (30) is well before b2's (35), while
        // a whole-graph pass cannot emit m until f's chain (up to time 90) has drained, pushing m
        // past b2.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.ids("m", "b2")

        val wholeGraph = WholeGraphBraid.compute(spec.graph, heads)
        val interleaved = BraidInterleave.compute(spec.graph, heads)

        assertEquals(listOf("a1", "a2", "b1", "b2", "m"), spec.names(wholeGraph))
        assertEquals(listOf("a1", "a2", "b1", "m", "b2"), spec.names(interleaved))
    }

    @Test
    fun `still produces a 3-parent commit when reparented, whichever braid it came from`() {
        // Ignoring a merge's other parents while *scheduling* it is not the same as dropping those
        // parents from the output. BraidInterleave never touches parents(c) at all -- it only
        // returns positions -- so Reparenter sees the same original two parents on m either way and
        // prepends the same third one. Which ordering produced the braid only changes *what ends up
        // being* m's braid predecessor (b1 here vs b2 under a whole-graph pass), not whether m
        // still carries all of its original edges plus the braid edge.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.ids("m", "b2")

        val interleaved = BraidInterleave.compute(spec.graph, heads)
        val reparented = Reparenter.reparent(spec.graph, interleaved)

        assertEquals(3, reparented[spec.id("m")].size, "m should still gain a third parent")
        assertEquals(
            listOf("b1", "a2", "f"),
            spec.names(reparented[spec.id("m")]),
            "braid predecessor prepended, both original parents kept",
        )
    }

    @Test
    fun `agrees with a whole-graph interleave over a fuzz corpus of ordinary histories`() {
        // On a history where no commit is older than any of its parents (including a merge's second
        // parent), plain ascending time is already a valid topological order of the whole graph, so
        // a whole-graph ready-set never has to override time -- and a k-way merge, which only
        // compares queue fronts by time, reduces to the same thing restricted to the braid. Both
        // reasons converge on the same order; this corpus is where that is expected to hold.
        for (seed in 1..100) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = true)

            val wholeGraph = WholeGraphBraid.compute(corpus.graph, corpus.heads)
            val interleaved = BraidInterleave.compute(corpus.graph, corpus.heads)

            assertArrayEquals(wholeGraph, interleaved, "orders differ on seed $seed")
        }
    }

    @Test
    fun `reduces to a k-way merge of the mainline chains when no ref is opted in`() {
        // The default scope is the chains alone, and those are disjoint paths, so the ready set holds
        // each chain's front and the earliest wins -- which is a k-way merge, spelled out
        // independently in KWayBraid. This is the reduction the class's first two properties are
        // argued from, so it is asserted rather than trusted, on ordinary and skewed histories alike.
        for (seed in 1..100) {
            for (monotone in listOf(true, false)) {
                val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = monotone)
                assertArrayEquals(
                    KWayBraid.compute(corpus.graph, corpus.heads),
                    BraidInterleave.compute(corpus.graph, corpus.heads),
                    "seed $seed (monotone=$monotone) stopped reducing to a k-way merge",
                )
            }
        }
    }

    @Test
    fun `matches a whole-graph pass when every commit is opted in`() {
        // The far end of the scope: put everything in, and the braid has to agree with a plain
        // topological pass over the whole graph -- the behaviour this tool had before the braid and
        // the write order were separated, and what `--interleave-ref '*'` asks for.
        for (seed in 1..100) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = false)
            val everything = IntArray(corpus.graph.size) { it }
            assertArrayEquals(
                WholeGraphBraid.compute(corpus.graph, corpus.heads),
                BraidInterleave.compute(corpus.graph, corpus.heads, everything),
                "seed $seed diverges from a whole-graph pass with everything in scope",
            )
        }
    }

    @Test
    fun `an opted-in ref delays the merge that merges it in`() {
        // The middle of the spectrum, and the whole point of the option: f is timestamped after the
        // merge m that brings it in. By default m lands by its own time (30), before b2 (35). Opt f
        // in and m has to wait for it, which pushes m past B's history -- the caller trading the
        // no-future-edge property for having that branch's time taken into account.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.ids("m", "b2")

        val byDefault = BraidInterleave.compute(spec.graph, heads)
        val withF = BraidInterleave.compute(spec.graph, heads, spec.ids("f"))

        assertEquals(listOf("a1", "a2", "b1", "m", "b2"), spec.names(byDefault))
        assertEquals(listOf("a1", "a2", "b1", "b2", "m"), spec.names(withF))
    }

    @Test
    fun `opting in a ref that is already on a mainline chain changes nothing`() {
        // Its ancestors are in scope either way, so there is nothing new to wait for. Worth pinning:
        // a user naming the mainline itself, or a tag sitting on it, should not see the braid shift.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.ids("m", "b2")

        assertArrayEquals(
            BraidInterleave.compute(spec.graph, heads),
            BraidInterleave.compute(spec.graph, heads, spec.ids("a2", "m")),
        )
    }

    @Test
    fun `never gives a commit a cross-repository predecessor timestamped later than itself`() {
        // This is one specific, narrower property than "checking out a commit never shows you
        // another repository's future" -- it is only about the artificial braid EDGE this ordering
        // adds, not about everything a checkout can end up displaying. A k-way merge always dequeues
        // the current global minimum across all queue fronts, so whatever is dequeued immediately
        // before a commit from a DIFFERENT repository was, at that moment, competing directly against
        // this commit's own (unchanged) front value and lost -- guaranteeing its timestamp is <= this
        // commit's. Same-repository predecessors are exempt from (and irrelevant to) this check: their
        // relative order is forced by ancestry, not decided by time, and Reparenter treats an
        // already-original-parent predecessor as a no-op, so no new cross-repository fold happens
        // there anyway.
        //
        // This does NOT mean a checkout can never show future-dated content under this ordering.
        // A merge commit can already, in the *input*, carry content from a branch timestamped after
        // the merge itself -- and once that merge sits on the braid, every later commit that inherits
        // its subtree forward (an ordinary, unavoidable consequence of the accumulation rule, nothing
        // this ordering decides) shows that same content too. That is a fact about the input history,
        // not a property either ordering algorithm can fix; see the README's own caveat.
        for (seed in 1..300) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = false)
            val braid = BraidInterleave.compute(corpus.graph, corpus.heads)

            for (i in 1 until braid.size) {
                val commit = braid[i]
                val predecessor = braid[i - 1]
                if (corpus.graph.sourceOf(predecessor) == corpus.graph.sourceOf(commit)) continue

                assertTrue(
                    corpus.graph.timeOf(predecessor) <= corpus.graph.timeOf(commit),
                    "seed $seed: ${corpus.graph.describe(predecessor)}@${corpus.graph.timeOf(predecessor)} " +
                        "precedes ${corpus.graph.describe(commit)}@${corpus.graph.timeOf(commit)} from a " +
                        "different repository, but is timestamped later -- a future leak",
                )
            }
        }
    }

    /** Ancestry restricted to first-parent edges only -- the one relation a k-way merge tracks. */
    private fun respectsFirstParentAncestry(graph: CommitGraph, order: IntArray): Boolean {
        val position = IntArray(graph.size) { -1 }
        for ((index, commit) in order.withIndex()) position[commit] = index
        for (commit in order) {
            val firstParent = graph.firstParentOf(commit)
            if (firstParent == CommitGraph.NO_COMMIT || position[firstParent] == -1) continue
            if (position[firstParent] >= position[commit]) return false
        }
        return true
    }
}
