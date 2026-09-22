package cz.loplex.timebraid.git

import cz.loplex.timebraid.GitCli
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
        GitCli.requireGit()
        val origin = tmp.resolve("origin.git")
        TestRepoBuilder.create(origin).use { it.branch("main", it.commit("first")) }

        val clone = tmp.resolve("clone.git")
        git.cloneMirror(origin.toString(), clone)

        assertTrue(RepositoryCache.FileKey.isGitRepository(clone.toFile(), FS.DETECTED))
        SourceRepository.open(clone).use { assertEquals(listOf("main"), it.branches().map { b -> b.name }) }
    }

    @Test
    fun `fetch brings a stale clone up to date`() {
        GitCli.requireGit()
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

    /**
     * The environment reaches the subprocess through `ProcessBuilder`, and a test JVM cannot alter its
     * own, so the policy is checked where it is decided rather than through a real `git` run. Both
     * halves matter: dropping too much would break the credential and transport setup that shelling
     * out to `git` exists to inherit.
     */
    @Test
    fun `only the variables that redirect git to another repository are dropped`() {
        val environment = mutableMapOf(
            // Redirecting: each one moves the repository out from under the command.
            "GIT_DIR" to "/elsewhere/.git",
            "GIT_WORK_TREE" to "/elsewhere",
            "GIT_COMMON_DIR" to "/elsewhere/.git",
            "GIT_INDEX_FILE" to "/elsewhere/.git/index",
            "GIT_OBJECT_DIRECTORY" to "/elsewhere/.git/objects",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES" to "/other/objects",
            "GIT_NAMESPACE" to "sandbox",
            // Kept: this is what shelling out to the user's own git is for.
            "GIT_SSH_COMMAND" to "ssh -i /home/me/.ssh/id_ed25519",
            "GIT_ASKPASS" to "/usr/bin/my-askpass",
            "GIT_CONFIG_GLOBAL" to "/home/me/.gitconfig",
            "SSH_AUTH_SOCK" to "/run/user/1000/ssh-agent",
            "HTTPS_PROXY" to "http://proxy.example:3128",
            "HOME" to "/home/me",
        )

        GitCommand.dropRedirectingVariables(environment)

        assertEquals(
            setOf(
                "GIT_SSH_COMMAND",
                "GIT_ASKPASS",
                "GIT_CONFIG_GLOBAL",
                "SSH_AUTH_SOCK",
                "HTTPS_PROXY",
                "HOME",
            ),
            environment.keys,
        )
    }

    @Test
    fun `a failing git command throws with its output attached`() {
        GitCli.requireGit()
        val failure = assertThrows<GitCommandException> {
            git.cloneMirror(tmp.resolve("does-not-exist.git").toString(), tmp.resolve("clone.git"))
        }
        assertTrue(failure.message!!.contains("clone --mirror"), failure.message)
    }
}
