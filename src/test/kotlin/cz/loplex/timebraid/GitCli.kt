package cz.loplex.timebraid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A thin wrapper around the `git` command line, for the integration tests that check the output
 * repository the way a user would — `git fsck`, `git log --graph`, `git rev-list`. JGit does the
 * reading and writing, so on a machine without git on `PATH` a test that needs it only for such a
 * check either leaves the check out or is skipped as a whole. Not every test is of that kind: some
 * run git through the program itself, and those that do not skip themselves fail without it.
 */
object GitCli {

    /**
     * Whether `git --version` runs at all. A test that needs git guards what needs it with this,
     * or is skipped as a whole through [requireGit]; one that runs git through the program and
     * does neither fails without it.
     */
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
     * braided output legitimately leaves objects unreferenced, and two things put them there: the
     * inputs' own commits come in with the fetch, so they are present with no ref of the output's
     * pointing at them; and each annotated tag's original object arrives with them, and the writer
     * tags the braided commit afresh. Both are bloat `git gc` reclaims, not damage.
     */
    fun fsck(dir: Path) =
        assertEquals("", run(dir, "fsck", "--strict", "--no-progress", "--no-dangling"), "git fsck found problems")

    /** `git log --graph --oneline --all` — the human-readable shape of a small fixture. */
    fun graphLog(dir: Path): String = run(dir, "log", "--graph", "--oneline", "--all", "--no-color")
}
