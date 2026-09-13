package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CommitGraphReaderTest {

    @TempDir
    lateinit var tmp: Path

    private fun open(vararg names: String): List<SourceRepository> =
        names.map { SourceRepository.open(tmp.resolve("$it.git")) }

    private inline fun <R> List<SourceRepository>.useAll(block: (List<SourceRepository>) -> R): R =
        try {
            block(this)
        } finally {
            forEach { it.close() }
        }

    @Test
    fun `assembles one graph from several repositories and auto-detects the mainline`() {
        lateinit var a2: String
        lateinit var b1: String
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1")
            a2 = repo.commit("a2", parents = listOf(a1)).name
            repo.branch("main", org.eclipse.jgit.lib.ObjectId.fromString(a2))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            b1 = repo.commit("b1").name
            repo.branch("main", org.eclipse.jgit.lib.ObjectId.fromString(b1))
        }

        open("backend", "webui").useAll { repos ->
            val braid = CommitGraphReader.read(repos, OrderBy.COMMITTER)

            assertEquals("main", braid.mainlineBranch)
            assertEquals(3, braid.graph.size)
            assertEquals(listOf("backend", "webui"), braid.graph.sources.map { it.name })
            assertEquals(a2, braid.heads[0].id)
            assertEquals(b1, braid.heads[1].id)

            val plan = braid.graph.braid(braid.heads).plan(braid.graph.sources.associateWith { it.name })
            assertEquals(3, plan.braid.size)
        }
    }

    @Test
    fun `falls back to master when main is absent in one repository`() {
        for (name in listOf("backend.git", "webui.git")) {
            TestRepoBuilder.create(tmp.resolve(name)).use { repo ->
                val c = repo.commit("c")
                if (name == "backend.git") repo.branch("main", c)
                repo.branch("master", c)
            }
        }

        open("backend", "webui").useAll { repos ->
            assertEquals("master", CommitGraphReader.read(repos, OrderBy.COMMITTER).mainlineBranch)
        }
    }

    @Test
    fun `prefers main over master where every repository carries both`() {
        for (name in listOf("backend.git", "webui.git")) {
            TestRepoBuilder.create(tmp.resolve(name)).use { repo ->
                val c = repo.commit("c")
                repo.branch("main", c)
                repo.branch("master", c)
            }
        }

        open("backend", "webui").useAll { repos ->
            assertEquals("main", CommitGraphReader.read(repos, OrderBy.COMMITTER).mainlineBranch)
        }
    }

    @Test
    fun `rejects an explicit mainline branch that is missing somewhere`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val c = repo.commit("c")
            repo.branch("main", c)
            repo.branch("develop", c)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("c"))
        }

        open("backend", "webui").useAll { repos ->
            val error = assertThrows<IllegalArgumentException> {
                CommitGraphReader.read(repos, OrderBy.COMMITTER, mainlineBranch = listOf("develop"))
            }
            assertTrue(error.message!!.contains("webui"))
        }
    }

    @Test
    fun `a ref selection narrows what is loaded, over branches and tags alike`() {
        lateinit var a2: org.eclipse.jgit.lib.ObjectId
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1")
            a2 = repo.commit("a2", parents = listOf(a1))
            val side = repo.commit("side", parents = listOf(a1))
            repo.branch("main", a2)
            repo.branch("experiment", side)
            repo.annotatedTag("v1.0", a1)
            // Deliberately on the side branch, so a pattern that keeps it has to drag `side` back in.
            repo.lightweightTag("v2.0", side)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1"))
        }

        open("backend", "webui").useAll { repos ->
            fun read(vararg patterns: String) =
                CommitGraphReader.read(repos, OrderBy.COMMITTER, refs = patterns.toList())

            // By the namespace each ref lands in, which is what the output actually holds.
            fun under(inputs: BraidInputs, namespace: String) = inputs.sources[0].refs
                .filter { it.namespace == namespace }
                .map { it.name }
                .sorted()
            fun branchesOf(inputs: BraidInputs) = under(inputs, Constants.R_HEADS)
            fun tagsOf(inputs: BraidInputs) = under(inputs, Constants.R_TAGS)

            // No pattern is every branch and tag, which is what a plain run does.
            val everything = read()
            assertEquals(4, everything.graph.size)
            assertEquals(listOf("experiment", "main"), branchesOf(everything))
            assertEquals(listOf("v1.0", "v2.0"), tagsOf(everything))

            // Naming one branch leaves out every ref not named — the tags included. This is what
            // -b could not do before the selection covered both kinds: `side` goes with `experiment`,
            // and both tags go too, `v2.0` although it sits on `side`.
            val mainOnly = read("refs/heads/main")
            assertEquals(3, mainOnly.graph.size)
            assertEquals(listOf("main"), branchesOf(mainOnly))
            assertEquals(emptyList<String>(), tagsOf(mainOnly))

            // Which is also how the old behaviour is written out, for a run that wants it back.
            val mainAndTags = read("refs/heads/main", "refs/tags/*")
            assertEquals(4, mainAndTags.graph.size)
            assertEquals(listOf("main"), branchesOf(mainAndTags))
            assertEquals(listOf("v1.0", "v2.0"), tagsOf(mainAndTags))

            // A selection that matches no branch at all still loads the mainline, since the braid
            // is built along it — it is simply not among the branches carried over by name.
            val tagsOnly = read("refs/tags/v1.*")
            assertEquals(3, tagsOnly.graph.size)
            assertEquals(emptyList<String>(), branchesOf(tagsOnly))
            assertEquals(listOf("v1.0"), tagsOf(tagsOnly))
            assertEquals(a2.name, tagsOnly.heads[0].id)
        }
    }

    @Test
    fun `a caret subtracts from whatever the rest of the patterns selected`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1")
            val a2 = repo.commit("a2", parents = listOf(a1))
            val side = repo.commit("side", parents = listOf(a1))
            repo.branch("main", a2)
            repo.branch("experiment", side)
            repo.annotatedTag("v1.0", a1)
            // On the side branch again, so it can be seen keeping `side` reachable on its own.
            repo.lightweightTag("v2.0", side)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1"))
            repo.branch("experiment", repo.commit("b-side"))
        }

        open("backend", "webui").useAll { repos ->
            fun read(vararg patterns: String) =
                CommitGraphReader.read(repos, OrderBy.COMMITTER, refs = patterns.toList())

            fun under(inputs: BraidInputs, index: Int, namespace: String) = inputs.sources[index].refs
                .filter { it.namespace == namespace }
                .map { it.name }
                .sorted()
            fun branchesOf(inputs: BraidInputs, index: Int = 0) = under(inputs, index, Constants.R_HEADS)
            fun tagsOf(inputs: BraidInputs, index: Int = 0) = under(inputs, index, Constants.R_TAGS)

            // The empty case underneath a subtraction is this option's own — every branch and tag — so
            // one pattern says "all but these" without the run naming a single thing it wants. That is
            // where this parts company with git, whose command line drops the configured refspec as
            // soon as one is named and so gives nothing back for subtractions alone.
            val allButExperiment = read("^refs/heads/experiment")
            assertEquals(listOf("main"), branchesOf(allButExperiment))
            assertEquals(listOf("v1.0", "v2.0"), tagsOf(allButExperiment))
            assertEquals(listOf("main"), branchesOf(allButExperiment, 1))

            // Subtraction is by ref name, and `side` is held by the tag rather than by the branch —
            // so dropping the branch alone leaves the commit, and dropping both takes it.
            // a1, a2 and side from backend — side only because the tag still holds it — plus
            // webui's b1. Its own `experiment` went with the unscoped pattern, b-side with it.
            assertEquals(4, allButExperiment.graph.size)
            val neither = read("^refs/heads/experiment", "^refs/tags/v2.*")
            assertEquals(3, neither.graph.size)
            assertEquals(listOf("v1.0"), tagsOf(neither))

            // Applied after the patterns that select, not instead of them.
            val branchesButOne = read("refs/heads/*", "^refs/heads/experiment")
            assertEquals(listOf("main"), branchesOf(branchesButOne))
            assertEquals(emptyList<String>(), tagsOf(branchesButOne))

            // The mark goes after the scope, in front of the refspec — so a subtraction narrows one
            // input and leaves the others carrying what they had.
            val scoped = read("backend::^refs/heads/experiment")
            assertEquals(listOf("main"), branchesOf(scoped))
            assertEquals(listOf("experiment", "main"), branchesOf(scoped, 1))

            // A '^' anywhere but in front is refused rather than read as part of a name: git allows
            // one nowhere in a ref, which is what makes the leading one decidable as a mark.
            val inside = assertThrows<IllegalArgumentException> { read("refs/heads/ex^periment") }
            assertTrue(inside.message!!.contains("'^'"), inside.message)

            // Nothing lands from a subtraction, so a destination would have nothing to name: git
            // refuses '^refs/heads/x:refs/tags/' as a refspec for the same reason.
            val destined = assertThrows<IllegalArgumentException> {
                read("^refs/heads/experiment:refs/tags/")
            }
            assertTrue(destined.message!!.contains("nothing to name"), destined.message)

            // A '^' opening the scope is named for what it always is — the mark written a scope too
            // early — rather than as an input called '^backend' that is not there.
            val misplaced = assertThrows<IllegalArgumentException> { read("^backend::refs/heads/experiment") }
            assertTrue(misplaced.message!!.contains("belongs in"), misplaced.message)
        }
    }

    @Test
    fun `committer order and author order can disagree on the interleaving`() {
        // backend authored before webui, but landed after it.
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            repo.branch(
                "main",
                repo.commit(
                    "a1",
                    at = java.time.Instant.parse("2021-03-01T00:00:00Z"),
                    authorAt = java.time.Instant.parse("2021-01-01T00:00:00Z"),
                ),
            )
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1", at = java.time.Instant.parse("2021-02-01T00:00:00Z")))
        }

        open("backend", "webui").useAll { repos ->
            fun firstOnBraid(orderBy: OrderBy): String {
                val braid = CommitGraphReader.read(repos, orderBy)
                val plan = braid.graph.braid(braid.heads).plan(braid.graph.sources.associateWith { it.name })
                val firstSha = plan.braid.first().id
                return if (firstSha == braid.heads[0].id) "backend" else "webui"
            }
            assertEquals("backend", firstOnBraid(OrderBy.AUTHOR))
            assertEquals("webui", firstOnBraid(OrderBy.COMMITTER))
        }
    }

    @Test
    fun `--interleave-ref patterns match full ref names, tags included`() {
        lateinit var f: org.eclipse.jgit.lib.ObjectId
        lateinit var tagged: org.eclipse.jgit.lib.ObjectId
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1")
            tagged = repo.commit("a2", parents = listOf(a1))
            f = repo.commit("f", parents = listOf(a1))
            repo.branch("main", tagged)
            repo.branch("feature/x/y", f)
            repo.lightweightTag("v1.0", tagged)
        }

        open("backend").useAll { repos ->
            fun idsFor(vararg patterns: String): Set<String> {
                val inputs = CommitGraphReader.read(
                    repos,
                    OrderBy.COMMITTER,
                    interleaveRefs = patterns.toList(),
                )
                return inputs.interleaveTips.map { it.id }.toSet()
            }

            // A star spans path separators, so a prefix pattern reaches a branch nested below it.
            assertEquals(setOf(f.name), idsFor("refs/heads/feature/*"))
            // Tags are refs too, and are matched under their own prefix.
            assertEquals(setOf(tagged.name), idsFor("refs/tags/v1.*"))
            // A short name is refused rather than quietly matching nothing: patterns are against the
            // full ref name on purpose, and a pattern that could never match is a mistake this
            // program can name where an empty result cannot be told from an input that has no such
            // ref.
            val short = assertThrows<IllegalArgumentException> { idsFor("feature/x/y") }
            assertTrue(short.message!!.contains("refs/"), short.message)
            // A bare star is every branch and every tag.
            assertEquals(setOf(f.name, tagged.name), idsFor("*"))
            // A '^' subtracts from it, which is the only way to write "broadly, except these".
            assertEquals(setOf(tagged.name), idsFor("*", "^refs/heads/feature/*"))
            // But it needs something to subtract from, and here the empty case is no ref at all —
            // so subtractions alone would resolve to nothing however they were meant, and are
            // refused instead of quietly matching nothing.
            val alone = assertThrows<IllegalArgumentException> { idsFor("^refs/heads/feature/*") }
            assertTrue(alone.message!!.contains("subtract"), alone.message)
            // No pattern means the default scope, and nothing to resolve.
            assertEquals(emptySet<String>(), idsFor())
        }
    }
}
