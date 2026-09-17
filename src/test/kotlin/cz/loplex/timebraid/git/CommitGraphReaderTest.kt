package cz.loplex.timebraid.git

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
                CommitGraphReader.read(repos, OrderBy.COMMITTER, mainlineBranch = "develop")
            }
            assertTrue(error.message!!.contains("webui"))
        }
    }

    @Test
    fun `a branch filter narrows what is loaded but always keeps the mainline`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1")
            val a2 = repo.commit("a2", parents = listOf(a1))
            val side = repo.commit("side", parents = listOf(a1))
            repo.branch("main", a2)
            repo.branch("experiment", side)
            repo.annotatedTag("v1", a1)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1"))
        }

        open("backend", "webui").useAll { repos ->
            assertEquals(4, CommitGraphReader.read(repos, OrderBy.COMMITTER).graph.size)

            // "experiment" filtered out; a1 and a2 survive via the mainline (and the tag on a1).
            val narrowed = CommitGraphReader.read(repos, OrderBy.COMMITTER, branches = setOf("main"))
            assertEquals(3, narrowed.graph.size)
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
            repo.branch("feature/x", f)
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

            // A star spans path separators, so a prefix pattern reaches a nested branch name.
            assertEquals(setOf(f.name), idsFor("refs/heads/feature/*"))
            // Tags are refs too, and are matched under their own prefix.
            assertEquals(setOf(tagged.name), idsFor("refs/tags/v1.*"))
            // A short name matches nothing: patterns are against the full ref name on purpose.
            assertEquals(emptySet<String>(), idsFor("feature/x"))
            // A bare star is every ref, which puts the whole loaded graph in scope.
            assertEquals(setOf(f.name, tagged.name), idsFor("*"))
            // No pattern means the default scope, and nothing to resolve.
            assertEquals(emptySet<String>(), idsFor())
        }
    }
}
