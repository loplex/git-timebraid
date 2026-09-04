package cz.loplex.timebraid.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SourceRepositoryTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads branches, tags and commit data out of a bare repository`() {
        val built = TestRepoBuilder.create(tmp.resolve("backend.git"))
        val first = built.commit("first")
        val second = built.commit("second\n\nwith a body line", parents = listOf(first))
        val feature = built.commit("feature work", parents = listOf(first))
        built.branch("main", second)
        built.branch("feature", feature)
        built.lightweightTag("v1.0", second)
        built.annotatedTag("v2.0", feature)
        built.close()

        SourceRepository.open(tmp.resolve("backend.git")).use { repo ->
            assertEquals("backend", repo.name)
            assertEquals(listOf("feature", "main"), repo.branches().map { it.name })
            assertEquals(second, repo.resolveBranch("main"))
            assertNull(repo.resolveBranch("no-such-branch"))

            // Both tags peel to the commit; the annotated one does not leak its tag object.
            assertEquals(
                mapOf("v1.0" to second, "v2.0" to feature),
                repo.tags().associate { it.name to it.target },
            )

            val commits = repo.readReachable(listOf(second, feature)).associateBy { it.id }
            assertEquals(setOf(first, second, feature), commits.keys)
            assertEquals(listOf(first), commits.getValue(second).parents)
            assertEquals("second\n\nwith a body line", commits.getValue(second).message)
            assertTrue(
                commits.getValue(second).time(OrderBy.COMMITTER) >
                    commits.getValue(first).time(OrderBy.COMMITTER),
            )
        }
    }

    @Test
    fun `opens a repository that has a working tree`() {
        val built = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        val only = built.commit("only commit")
        built.branch("main", only)
        built.close()

        SourceRepository.open(tmp.resolve("webui")).use { repo ->
            assertEquals("webui", repo.name)
            assertEquals(only, repo.resolveBranch("main"))
            assertEquals(listOf("only commit"), repo.readReachable(listOf(only)).map { it.message })
        }
    }

    @Test
    fun `time follows the requested ident, author and committer read independently`() {
        val built = TestRepoBuilder.create(tmp.resolve("r.git"))
        // A rebased commit: written in 2020, landed in 2021.
        val rebased = built.commit(
            "rebased",
            at = java.time.Instant.parse("2021-06-01T00:00:00Z"),
            authorAt = java.time.Instant.parse("2020-06-01T00:00:00Z"),
        )
        built.branch("main", rebased)
        built.close()

        SourceRepository.open(tmp.resolve("r.git")).use { repo ->
            val commit = repo.readReachable(listOf(rebased)).single()
            assertEquals(
                java.time.Instant.parse("2020-06-01T00:00:00Z").epochSecond,
                commit.time(OrderBy.AUTHOR),
            )
            assertEquals(
                java.time.Instant.parse("2021-06-01T00:00:00Z").epochSecond,
                commit.time(OrderBy.COMMITTER),
            )
        }
    }
}
