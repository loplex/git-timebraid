package cz.loplex.timebraid.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pins down the three properties [BraidInterleave]'s doc claims, and the one place where interleaving
 * over mainline chains alone visibly differs from a pass over the whole graph.
 *
 * A k-way merge over per-repository first-parent chains never compares distant commits at all, so
 * there is no comparator that could be non-transitive — ancestry along a chain is respected by
 * construction rather than by a comparison that has to stay consistent. What it deliberately does not
 * do is track a merge commit's *other* parents, which a plain topological pass over the whole graph
 * ([topoOrder]) does by construction, Kahn's algorithm requiring every parent ready and not only the
 * first. The tests below demonstrate that divergence rather than assume it.
 */
class BraidInterleaveTest {

    @Test
    fun `a head of another graph is refused rather than quietly left out of the braid`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 | B: b1@20")
        val other = GraphSpec.parse("A: x1@10")

        val failure = assertThrows<IllegalArgumentException> {
            spec.graph.braid(listOf(spec.commit("a2"), other.commit("x1")))
        }

        assertTrue(failure.message!!.contains("head"), failure.message)
        assertTrue(failure.message!!.contains("A/x1"), failure.message)
    }

    @Test
    fun `an interleave tip of another graph is refused too`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 | B: b1@20")
        val other = GraphSpec.parse("A: x1@10")

        val failure = assertThrows<IllegalArgumentException> {
            spec.graph.braid(spec.commits("a2", "b1"), listOf(other.commit("x1")))
        }

        assertTrue(failure.message!!.contains("interleave tip"), failure.message)
    }

    @Test
    fun `agrees with a whole-graph interleave on two ordinary linear repositories`() {
        val spec = GraphSpec.parse("A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40")
        val heads = spec.commits("a3", "b2")

        val wholeGraph = WholeGraphBraid.compute(spec.graph, heads)
        val interleaved = spec.graph.braid(heads).commits

        assertEquals(spec.names(wholeGraph), spec.names(interleaved))
    }

    @Test
    fun `never reorders a repository's own chain, even where its timestamps run backwards`() {
        // A's own chain runs backwards in time. A k-way merge only ever pops from the front of A's
        // own queue, so nothing can move a1/a2 relative to each other regardless of what their
        // timestamps say — ancestry within one repository holds no matter how its timestamps behave.
        val spec = GraphSpec.parse("A: a1@50 <- a2@10 | B: b1@20 <- b2@40")
        val heads = spec.commits("a2", "b2")

        val order = spec.graph.braid(heads).commits

        assertTrue(respectsFirstParentAncestry(order))
    }

    @Test
    fun `does not track a merge's other parents, unlike a whole-graph pass`() {
        // f is a side branch merged into the mainline at m, timestamped *after* m itself — a slow
        // review or clock skew can produce exactly this. A whole-graph pass holds m back until f has
        // been emitted, since Kahn's algorithm requires every parent, not only the first, to be
        // ready before a commit joins the ready set. A k-way merge over first-parent chains never
        // looks at f at all, so it places m purely by its own timestamp, one queue advancing
        // independently of the other — and m's own time (30) is well before b2's (35), while
        // a whole-graph pass cannot emit m until f's chain (up to time 90) has drained, pushing m
        // past b2.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.commits("m", "b2")

        val wholeGraph = WholeGraphBraid.compute(spec.graph, heads)
        val interleaved = spec.graph.braid(heads).commits

        assertEquals(listOf("a1", "a2", "b1", "b2", "m"), spec.names(wholeGraph))
        assertEquals(listOf("a1", "a2", "b1", "m", "b2"), spec.names(interleaved))
    }

    @Test
    fun `still produces a 3-parent commit when reparented, whichever braid it came from`() {
        // Ignoring a merge's other parents while *scheduling* it is not the same as dropping those
        // parents from the output. BraidInterleave never touches parents(c) at all — it only
        // returns positions — so reparent sees the same original two parents on m either way and
        // prepends the same third one. Which ordering produced the braid only changes *what ends up
        // being* m's braid predecessor (b1 here vs b2 under a whole-graph pass), not whether m
        // still carries all of its original edges plus the braid edge.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.commits("m", "b2")

        val reparented = spec.graph.braid(heads).reparent()

        val parentsOfM = reparented.parentsOf(spec.commit("m"))
        assertEquals(3, parentsOfM.size, "m should still gain a third parent")
        assertEquals(
            listOf("b1", "a2", "f"),
            spec.names(parentsOfM),
            "braid predecessor prepended, both original parents kept",
        )
    }

    @Test
    fun `agrees with a whole-graph interleave over a fuzz corpus of ordinary histories`() {
        // On a history where no commit is older than any of its parents (including a merge's second
        // parent), plain ascending time is already a valid topological order of the whole graph, so
        // a whole-graph ready-set never has to override time — and a k-way merge, which only
        // compares queue fronts by time, reduces to the same thing restricted to the braid. Both
        // reasons converge on the same order; this corpus is where that is expected to hold.
        for (seed in 1..100) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = true)

            val wholeGraph = WholeGraphBraid.compute(corpus.graph, corpus.heads)
            val interleaved = corpus.graph.braid(corpus.heads).commits

            assertEquals(wholeGraph, interleaved, "orders differ on seed $seed")
        }
    }

    @Test
    fun `reduces to a k-way merge of the mainline chains when no ref is opted in`() {
        // The default scope is the chains alone, and those are disjoint paths, so the ready set holds
        // each chain's front and the earliest wins — which is a k-way merge, spelled out
        // independently in KWayBraid. This is the reduction the class's first two properties are
        // argued from, so it is asserted rather than trusted, on ordinary and skewed histories alike.
        for (seed in 1..100) {
            for (monotone in listOf(true, false)) {
                val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = monotone)
                assertEquals(
                    KWayBraid.compute(corpus.heads),
                    corpus.graph.braid(corpus.heads).commits,
                    "seed $seed (monotone=$monotone) stopped reducing to a k-way merge",
                )
            }
        }
    }

    @Test
    fun `matches a whole-graph pass when every commit is opted in`() {
        // The far end of the scope: put everything in, and the braid has to agree with a plain
        // topological pass over the whole graph — the behaviour this tool had before the braid and
        // the write order were separated, and what `--interleave-ref '*'` asks for while the
        // selection carries the mainline branches.
        for (seed in 1..100) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = false)
            val everything = corpus.graph.commits
            assertEquals(
                WholeGraphBraid.compute(corpus.graph, corpus.heads),
                corpus.graph.braid(corpus.heads, everything).commits,
                "seed $seed diverges from a whole-graph pass with everything in scope",
            )
        }
    }

    @Test
    fun `an opted-in ref delays the merge that merges it in`() {
        // The middle of the spectrum, and the whole point of the option: f is timestamped after the
        // merge m that brings it in. By default m lands by its own time (30), before b2 (35). Opt f
        // in and m has to wait for it, which pushes m past B's history — the caller trading the
        // no-future-edge property for having that branch's time taken into account.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.commits("m", "b2")

        val byDefault = spec.graph.braid(heads).commits
        val withF = spec.graph.braid(heads, spec.commits("f")).commits

        assertEquals(listOf("a1", "a2", "b1", "m", "b2"), spec.names(byDefault))
        assertEquals(listOf("a1", "a2", "b1", "b2", "m"), spec.names(withF))
    }

    @Test
    fun `opting in a braid commit with nothing off the braid behind it changes nothing`() {
        // a2's ancestry is a1 alone, which is on the braid already, so opting a2 in brings nothing
        // new into scope. That holds only for a commit whose whole ancestry is in scope already: m,
        // on the same chain, merged f, and opting m in moves the braid (the next test).
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.commits("m", "b2")

        assertEquals(
            spec.graph.braid(heads).commits,
            spec.graph.braid(heads, spec.commits("a2")).commits,
        )
    }

    @Test
    fun `opting in a merge on the braid brings what it merged into scope`() {
        // m is on the braid and is a merge, and f, the side it merged, is not in scope by default.
        // Naming m therefore has to mean what naming f means: the scope is the braid plus the
        // ancestry of whatever the caller opted in, a commit on the braid included.
        val spec = GraphSpec.parse("A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 | B: b1@25 <- b2@35")
        val heads = spec.commits("m", "b2")

        assertEquals(
            listOf("a1", "a2", "b1", "b2", "m"),
            spec.names(spec.graph.braid(heads, spec.commits("m")).commits),
        )
        assertEquals(
            spec.graph.braid(heads, spec.commits("f")).commits,
            spec.graph.braid(heads, spec.commits("m")).commits,
        )
    }

    @Test
    fun `opting in the mainline tip reaches what an earlier merge on its chain merged`() {
        // m merged f one commit below the tip a3, and nothing names f any more: its branch went
        // once it was merged, as a merged pull request's usually does. The ancestry of a3 is still
        // the whole of A's history, f included, so opting in the tip — the mainline branch, or a
        // tag on a3 — holds m back for f exactly as a pass over the whole graph does.
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 <- a3@95 | B: b1@25 <- b2@35"
        )
        val heads = spec.commits("a3", "b2")

        assertEquals(
            listOf("a1", "a2", "b1", "m", "b2", "a3"),
            spec.names(spec.graph.braid(heads).commits),
        )
        assertEquals(
            listOf("a1", "a2", "b1", "b2", "m", "a3"),
            spec.names(spec.graph.braid(heads, spec.commits("a3")).commits),
        )
        assertEquals(
            spec.names(WholeGraphBraid.compute(spec.graph, heads)),
            spec.names(spec.graph.braid(heads, spec.commits("a3")).commits),
        )
    }

    @Test
    fun `a tip off the braid reaches through the braid to what an earlier merge merged`() {
        // t forked from m, so its ancestry runs into the braid at m and on through it to f. Opting
        // t in has to mean what opting f in means; a walk that stopped where it met the braid would
        // bring in t alone and leave the braid as the default.
        val spec = GraphSpec.parse(
            "A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30 <- a3@95 ; t(m)@96 | B: b1@25 <- b2@35"
        )
        val heads = spec.commits("a3", "b2")

        assertEquals(
            listOf("a1", "a2", "b1", "b2", "m", "a3"),
            spec.names(spec.graph.braid(heads, spec.commits("t")).commits),
        )
        assertEquals(
            spec.graph.braid(heads, spec.commits("f")).commits,
            spec.graph.braid(heads, spec.commits("t")).commits,
        )
    }

    @Test
    fun `never gives a commit a cross-repository predecessor timestamped later than itself`() {
        // This is one specific, narrower property than "checking out a commit never shows you
        // another repository's future" — it is only about the artificial braid EDGE this ordering
        // adds, not about everything a checkout can end up displaying. A k-way merge always dequeues
        // the current global minimum across all queue fronts, so whatever is dequeued immediately
        // before a commit from a DIFFERENT repository was, at that moment, competing directly against
        // this commit's own (unchanged) front value and lost — guaranteeing its timestamp is <= this
        // commit's. Same-repository predecessors are exempt from (and irrelevant to) this check: their
        // relative order is forced by ancestry, not decided by time, and reparent treats an
        // already-original-parent predecessor as a no-op, so no new cross-repository fold happens
        // there anyway.
        //
        // This does NOT mean a checkout can never show future-dated content under this ordering.
        // A merge commit can already, in the *input*, carry content from a branch timestamped after
        // the merge itself — and once that merge sits on the braid, every later commit that inherits
        // its subtree forward (an ordinary, unavoidable consequence of the accumulation rule, nothing
        // this ordering decides) shows that same content too. That is a fact about the input history,
        // not a property either ordering algorithm can fix; see the caveat in doc/how-it-works.md.
        for (seed in 1..300) {
            val corpus = RandomGraphs.generate(seed, ancestryMonotoneTime = false)
            val braid = corpus.graph.braid(corpus.heads).commits

            for (i in 1 until braid.size) {
                val commit = braid[i]
                val predecessor = braid[i - 1]
                if (predecessor.source == commit.source) continue

                assertTrue(
                    predecessor.time <= commit.time,
                    "seed $seed: $predecessor@${predecessor.time} " +
                        "precedes $commit@${commit.time} from a " +
                        "different repository, but is timestamped later -- a future leak",
                )
            }
        }
    }

    /** Ancestry restricted to first-parent edges only — the one relation a k-way merge tracks. */
    private fun respectsFirstParentAncestry(order: List<Commit>): Boolean {
        val position = HashMap<Commit, Int>()
        for ((index, commit) in order.withIndex()) position[commit] = index
        for (commit in order) {
            val firstParent = commit.firstParent ?: continue
            val parentPosition = position[firstParent] ?: continue
            if (parentPosition >= position.getValue(commit)) return false
        }
        return true
    }
}
