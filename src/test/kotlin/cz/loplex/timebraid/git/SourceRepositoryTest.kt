package cz.loplex.timebraid.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

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

    @Test
    fun `a plain directory is not opened as the repository that encloses it`() {
        val built = TestRepoBuilder.create(tmp.resolve("outer"), bare = false)
        built.branch("main", built.commit("only commit"))
        built.close()
        val inside = tmp.resolve("outer/sub").createDirectories()

        // Without this, JGit's findGitDir walks up to outer/.git and opens that instead, so a
        // mistyped input silently becomes some other repository under the mistyped name.
        val refused = assertThrows<IllegalArgumentException> { SourceRepository.open(inside) }

        assertTrue(refused.message!!.contains("no git repository"), refused.message)
    }

    @Test
    fun `a working tree named by its own dot-git directory keeps the working tree's name`() {
        val built = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        val only = built.commit("only commit")
        built.branch("main", only)
        built.close()

        SourceRepository.open(tmp.resolve("webui/.git")).use { repo ->
            // Naming it after the last segment would strip ".git" down to nothing at all.
            assertEquals("webui", repo.name)
            assertEquals(only, repo.resolveBranch("main"))
        }
    }

    @Test
    fun `a path is named by where it points, not by how it was written`() {
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer/.")))
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("sub/../outer")))
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer/.git")))
        // A bare repository still loses the suffix of its own directory name.
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer.git")))
        // "." is the natural way to name the repository the shell is sitting in. The expected value
        // comes first, as everywhere above; the inspection reads defaultName(Path.of(".")) as the
        // more constant side and would have the two swapped, which would make a failure message lie.
        @Suppress("KotlinMisorderedAssertEqualsArguments")
        assertEquals(
            Path.of("").toAbsolutePath().fileName.toString(),
            SourceRepository.defaultName(Path.of(".")),
        )
    }
}
