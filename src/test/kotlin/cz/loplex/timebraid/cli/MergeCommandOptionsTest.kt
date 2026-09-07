package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** The outer CLI options: cloning a remote input, --no-bare, --keep-remotes, the prefixes, -q/-v. */
class MergeCommandOptionsTest {

    @TempDir
    lateinit var tmp: Path

    private fun corpus() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = Instant.parse("2021-01-01T09:00:00Z"))
            val a2 = repo.commit("a2", parents = listOf(a1), at = Instant.parse("2021-01-01T11:00:00Z"))
            repo.branch("main", a2)
            repo.lightweightTag("v1", a2)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1", at = Instant.parse("2021-01-01T10:00:00Z")))
        }
    }

    private fun run(vararg args: String) =
        MergeCommand().test(args.toList()).also { assertEquals(0, it.statusCode, it.output) }

    @Test
    fun `--no-bare checks out a working tree`() {
        corpus()
        val out = tmp.resolve("merged")

        run(
            "-o", out.toString(), "--no-bare",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        assertTrue(out.resolve(".git").isDirectory(), "expected a non-bare repository")
        assertTrue(out.resolve("backend/f").exists(), "backend subtree not checked out")
        assertTrue(out.resolve("webui/f").exists(), "webui subtree not checked out")
    }

    @Test
    fun `--keep-remotes adds each input as a remote of the output`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(), "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repo ->
            assertTrue(repo.remoteNames.containsAll(setOf("backend", "webui")), repo.remoteNames.toString())
            assertTrue(
                repo.refDatabase.getRefsByPrefix("refs/remotes/backend/").isNotEmpty(),
                "backend refs were not fetched",
            )
            assertTrue(
                repo.refDatabase.getRefsByPrefix("refs/remotes/webui/").isNotEmpty(),
                "webui refs were not fetched",
            )
        }
    }

    @Test
    fun `--keep-remotes resolves a relative input into an absolute remote URL`() {
        corpus()
        val out = tmp.resolve("merged.git")
        val cwd = Path.of("").toAbsolutePath()
        // A relative path between the two only exists when they share a filesystem root, which on
        // Windows they need not.
        assumeTrue(cwd.root == tmp.root, "temp directory is on another root than the working directory")
        val backend = cwd.relativize(tmp.resolve("backend.git")).toString()
        val webui = cwd.relativize(tmp.resolve("webui.git")).toString()
        assumeTrue(!Path.of(backend).isAbsolute, "relativize did not produce a relative path")

        run("-o", out.toString(), "--keep-remotes", backend, webui)

        // git stores a remote's URL verbatim and resolves it against the repository holding it, so
        // the relative path the caller typed has to be made absolute on the way in -- otherwise the
        // fetch looks for the input underneath the output and the whole run fails.
        FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repo ->
            for (name in listOf("backend", "webui")) {
                val url = repo.config.getString("remote", name, "url")
                assertTrue(Path.of(url).isAbsolute, "remote '$name' recorded a relative url: $url")
                assertTrue(
                    repo.refDatabase.getRefsByPrefix("refs/remotes/$name/").isNotEmpty(),
                    "$name refs were not fetched",
                )
            }
        }
    }

    @Test
    fun `a renamed input carries the new name into its tags and provenance`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            tmp.resolve("backend.git").toString() + "::legacy",
            tmp.resolve("webui.git").toString(),
        )

        SourceRepository.open(out).use { repo ->
            // The default tag prefix is "{repo}/", so the tag shows the name and not just where the
            // content was placed; the same name has to reach the provenance trailer.
            assertEquals(listOf("legacy/v1"), repo.tags().map { it.name })
            val messages = repo.readReachable(repo.branches().map { it.target }).map { it.message }
            assertTrue(messages.any { it.contains("repo=\"legacy\"") }, messages.toString())
            assertTrue(messages.none { it.contains("backend") }, messages.toString())
        }
    }

    @Test
    fun `--tag-prefix and --no-provenance shape the output`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            "--tag-prefix", "legacy-{repo}-",
            "--no-provenance",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        SourceRepository.open(out).use { repo ->
            assertEquals(listOf("legacy-backend-v1"), repo.tags().map { it.name })
            val messages = repo.readReachable(repo.branches().map { it.target }).map { it.message }
            assertTrue(messages.none { it.contains("[timebraid:") }, "provenance trailer was still written")
        }
    }

    @Test
    fun `a file URL input is cloned next to the output`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            "file://" + tmp.resolve("backend.git").toAbsolutePath(),
            tmp.resolve("webui.git").toString(),
        )

        val clone = tmp.resolve(".timebraid-clones/backend.git")
        assertTrue(RepositoryCache.FileKey.isGitRepository(clone.toFile(), FS.DETECTED), "input was not cloned")
        SourceRepository.open(out).use { repo ->
            assertTrue(repo.branches().any { it.name == "main" })
        }
    }

    @Test
    fun `--quiet silences progress, --verbose shows the git commands`() {
        corpus()

        val quiet = run(
            "-o", tmp.resolve("q.git").toString(), "--quiet", "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )
        assertFalse(quiet.output.contains("git-timebraid:"), quiet.output)

        val verbose = run(
            "-o", tmp.resolve("v.git").toString(), "--verbose", "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )
        assertTrue(verbose.output.contains("git-timebraid:"), verbose.output)
        assertTrue(verbose.output.contains("remote add"), verbose.output)
    }
}
