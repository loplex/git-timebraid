package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.util.FS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The subprocess wrapper: it clones, refreshes a clone, and fails loudly. */
class GitCommandTest {

    @TempDir
    lateinit var tmp: Path

    private val git = GitCommand()

    @Test
    fun `clone --mirror produces a readable repository`() {
        val origin = tmp.resolve("origin.git")
        TestRepoBuilder.create(origin).use { it.branch("main", it.commit("first")) }

        val clone = tmp.resolve("clone.git")
        git.cloneMirror(origin.toString(), clone)

        assertTrue(RepositoryCache.FileKey.isGitRepository(clone.toFile(), FS.DETECTED))
        SourceRepository.open(clone).use { assertEquals(listOf("main"), it.branches().map { b -> b.name }) }
    }

    @Test
    fun `fetch brings a stale clone up to date`() {
        val origin = tmp.resolve("origin.git")
        val first = TestRepoBuilder.create(origin).use { repo ->
            repo.commit("first").also { repo.branch("main", it) }
        }

        val clone = tmp.resolve("clone.git")
        git.cloneMirror(origin.toString(), clone)

        val second = TestRepoBuilder.open(origin).use { repo ->
            repo.commit("second", parents = listOf(first)).also { repo.branch("main", it) }
        }

        git.fetch(clone)

        SourceRepository.open(clone).use { repo ->
            assertEquals(second.name, repo.resolveBranch("main")?.name)
            assertNotEquals(first.name, second.name)
        }
    }

    @Test
    fun `a failing git command throws with its output attached`() {
        val failure = assertThrows<GitCommandException> {
            git.cloneMirror(tmp.resolve("does-not-exist.git").toString(), tmp.resolve("clone.git"))
        }
        assertTrue(failure.message!!.contains("clone --mirror"), failure.message)
    }
}
