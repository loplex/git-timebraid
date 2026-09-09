package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/** The CLI paths that stop short of writing: `--dry-run`, `--plan-out`, and the option checks. */
class MergeCommandDryRunTest {

    @TempDir
    lateinit var tmp: Path

    private fun path(name: String) = tmp.resolve(name).toString()

    private fun corpus() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = java.time.Instant.parse("2021-01-01T09:00:00Z"))
            val a2 = repo.commit("a2", parents = listOf(a1), at = java.time.Instant.parse("2021-01-01T11:00:00Z"))
            repo.branch("main", a2)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1", at = java.time.Instant.parse("2021-01-01T10:00:00Z")))
        }
    }

    @Test
    fun `dry run prints a plan and writes it to the plan-out file`() {
        corpus()
        val planFile = tmp.resolve("plan.txt")

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                "--plan-out", planFile.toString(),
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("mainline branch: main"), result.output)
        assertTrue(result.output.contains("commits: 3 (braid: 3)"), result.output)

        val plan = planFile.readText()
        // Braided in time order: a1 (09:00), b1 (10:00), a2 (11:00).
        val braidLines = plan.lines().filter { " * " in it }.map { it.substringAfter(" * ").substringBefore(" @") }
        assertEquals(listOf("backend/", "webui/", "backend/"), braidLines.map { it.substringBefore("/") + "/" })
    }

    @Test
    fun `writing needs an output directory`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("-o/--output is required"), result.output)
    }

    @Test
    fun `-o writes the output repository and reports what it wrote`() {
        corpus()
        val out = tmp.resolve("merged.git")

        val result = MergeCommand().test(
            listOf(
                "-o", out.toString(),
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("wrote 3 commits"), result.output)
        assertTrue(result.output.contains("HEAD -> main"), result.output)
        SourceRepository.open(out).use { repo ->
            assertEquals(listOf("main"), repo.branches().map { it.name })
        }
    }

    @Test
    fun `a subdirectory override and --root-repo are honoured`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                "--root-repo", "backend",
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString() + "::=ui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> <root>"), result.output)
        // "::=ui" places the content; the repository is still called webui.
        assertTrue(result.output.contains("webui -> ui/"), result.output)
    }

    @Test
    fun `two inputs whose directories share a name are told apart by naming them`() {
        for (side in listOf("a", "b")) {
            TestRepoBuilder.create(tmp.resolve("$side/proj.git")).use { repo ->
                repo.branch("main", repo.commit("$side commit"))
            }
        }
        val shared = listOf(tmp.resolve("a/proj.git").toString(), tmp.resolve("b/proj.git").toString())

        val collided = MergeCommand().test(listOf("--dry-run") + shared)
        assertEquals(1, collided.statusCode, collided.output)
        assertTrue(collided.output.contains("same repository name"), collided.output)

        val named = MergeCommand().test(
            listOf("--dry-run", shared[0] + "::proj-a", shared[1] + "::proj-b"),
        )
        assertEquals(0, named.statusCode, named.output)
        assertTrue(named.output.contains("proj-a -> proj-a/"), named.output)
        assertTrue(named.output.contains("proj-b -> proj-b/"), named.output)
    }

    @Test
    fun `an equals sign is part of the location unless a separator precedes it`() {
        // `=<subdir>` lives inside the suffix, so a location holding a `=` needs no escaping and no
        // guard: with no `::` in the argument, the whole of it is the location.
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/a=b/c"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("/nonexistent/a=b/c"), result.output)
    }

    @Test
    fun `a location holding the separator is ended by a bare one`() {
        // The remedy the parser offers, and the only thing a user has to know: append `::`.
        corpus()
        val holding = tmp.resolve("odd::name")
        tmp.resolve("backend.git").toFile().renameTo(holding.toFile())

        val result = MergeCommand().test(listOf("--dry-run", "$holding::", path("webui.git")))

        assertEquals(0, result.statusCode, result.output)
        // The name is derived from the whole last segment, separator and all.
        assertTrue(result.output.contains("odd::name -> odd::name/"), result.output)
    }

    @Test
    fun `a location holding the separator without the remedy is refused, and the message says how`() {
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/a::b/c"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("is not a usable name"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a directory whose own name holds the separator is named in the refusal`() {
        // The case the grammar cannot diagnose by itself: the part after `::` is a perfectly usable
        // name, so nothing in the parse looks wrong and the run would fail naming only the location
        // it was cut down to.
        val holding = tmp.resolve("odd::name")
        holding.createDirectories()

        val result = MergeCommand().test(listOf("--dry-run", holding.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("its name holds a '::'"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `an IPv6 URL is refused with the remedy rather than read as a name`() {
        // Both spellings git accepts for a literal address carry a `::`, and no guard second-guesses
        // it any more: the argument is read as written and the way out is stated.
        for (url in listOf("https://[fe80::1]/repo.git", "git@[::1]:repo.git")) {
            val result = MergeCommand().test(listOf("--dry-run", url))

            assertEquals(1, result.statusCode, "$url\n${result.output}")
            assertTrue(result.output.contains("end the argument with '::'"), result.output)
        }
    }

    @Test
    fun `an empty name asks for the derived one`() {
        corpus()

        val result = MergeCommand().test(
            listOf("--dry-run", path("backend.git") + "::", path("webui.git") + "::=ui")
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> backend/"), result.output)
        assertTrue(result.output.contains("webui -> ui/"), result.output)
    }

    @Test
    fun `a colon in the name or the subdirectory is written with a backslash`() {
        corpus()

        val result = MergeCommand().test(
            listOf("--dry-run", path("backend.git") + "::odd\\:name", path("webui.git"))
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("odd:name -> odd:name/"), result.output)
    }

    @Test
    fun `a bare colon in the suffix is refused rather than reread`() {
        // What keeps the grammar provable: an encoded suffix holds no literal `::`, so the last one
        // in the argument is always the separator.
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::odd:name"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("has to be written"), result.output)
    }

    @Test
    fun `an equals sign in a name or a subdirectory is escaped too`() {
        corpus()

        val result = MergeCommand().test(
            listOf("--dry-run", path("backend.git") + "::odd\\=name=libs/a\\=b", path("webui.git"))
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("odd=name -> libs/a=b/"), result.output)
    }

    @Test
    fun `an equals sign with nothing after it is refused`() {
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::name="))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("names no subdirectory"), result.output)
    }

    @Test
    fun `a nested subdirectory places an input below the output root`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                tmp.resolve("backend.git").toString() + "::=libs/backend",
                tmp.resolve("webui.git").toString() + "::=apps/webui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
        assertTrue(result.output.contains("webui -> apps/webui/"), result.output)
    }

    @Test
    fun `a subdirectory with an unusable segment is refused before anything is read`() {
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/backend.git::=libs//backend"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("not a usable subdirectory"), result.output)
    }

    @Test
    fun `a file scheme over a Windows path is still named by its last segment`() {
        // Concatenating `file://` with an absolute path is how a caller spells a local repository as
        // a URL, and on Windows the result carries a drive letter and backslashes. The name is the
        // directory the clone goes in, so reading it from the colon of `C:` would take the clone out
        // of the clone root and into whatever `\repos\backend` resolves to.
        val result = MergeCommand().test(listOf("--dry-run", "file://C:\\repos\\backend.git"))

        assertEquals(1, result.statusCode, result.output)
        val target = result.output.substringAfter("into ").lineSequence().first().trim()
        assertEquals("backend.git", Path.of(target).fileName.toString(), result.output)
    }

    @Test
    fun `a location whose last segment is no name at all is refused`() {
        for (location in listOf("https://example.invalid/repo/..", "https://example.invalid/a/b/.")) {
            val result = MergeCommand().test(listOf("--dry-run", location))

            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains("is not a usable name"), result.output)
        }
    }

    @Test
    fun `a location the platform cannot spell as a path is a usage error`() {
        // A NUL is the one character no filesystem here accepts; Windows also rejects a colon
        // outside a drive letter, which is how `a::b` gets in. Either way the report is the user's
        // typo, not an exception out of the parser.
        val result = MergeCommand().test(listOf("--dry-run", "no\u0000such"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("is not a usable path"), result.output)
    }

    @Test
    fun `a name and a subdirectory are set independently of each other`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString() + "::frontend=ui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("frontend -> ui/"), result.output)
    }
}
