package cz.loplex.timebraid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A thin wrapper around the `git` command line, for the integration tests that check the output
 * repository the way a user would — `git fsck`, `git log --graph`, `git rev-list`. The build never
 * needs git (JGit does the reading and writing), so a machine without it on `PATH` skips these tests
 * rather than failing them.
 */
object GitCli {

    /** Whether `git --version` runs at all; an integration test that needs git is skipped otherwise. */
    val available: Boolean by lazy {
        try {
            ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
        } catch (_: IOException) {
            false
        }
    }

    fun requireGit() = assumeTrue(available, "git is not on PATH")

    /** Runs `git <args>` in [dir], asserts it succeeded, and returns its combined output, trimmed. */
    fun run(dir: Path, vararg args: String): String {
        requireGit()
        val process = ProcessBuilder(listOf("git", *args))
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "git ${args.joinToString(" ")} did not finish")
        assertEquals(0, process.exitValue(), "git ${args.joinToString(" ")}:\n$output")
        return output
    }

    /**
     * `git fsck --strict` — asserts no corruption in [dir]. `--no-dangling` is passed because a
     * braided output legitimately leaves the inputs' own top-level trees unreferenced once
     * `--root-repo` splices their entries into a fresh root tree; that is bloat `git gc` reclaims,
     * not damage.
     */
    fun fsck(dir: Path) =
        assertEquals("", run(dir, "fsck", "--strict", "--no-progress", "--no-dangling"), "git fsck found problems")

    /** `git log --graph --oneline --all` — the human-readable shape of a small fixture. */
    fun graphLog(dir: Path): String = run(dir, "log", "--graph", "--oneline", "--all", "--no-color")
}
