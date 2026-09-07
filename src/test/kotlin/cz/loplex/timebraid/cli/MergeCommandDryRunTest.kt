package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

/** The CLI paths that stop short of writing: `--dry-run`, `--plan-out`, and the option checks. */
class MergeCommandDryRunTest {

    @TempDir
    lateinit var tmp: Path

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
                tmp.resolve("webui.git").toString() + "=ui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> <root>"), result.output)
        // "=ui" places the content; the repository is still called webui.
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
    fun `a separator that belongs to the location is left there`() {
        // Both suffixes are recognised only before a bare word, so a path that happens to contain
        // one is not split behind the user's back.
        for (location in listOf("/nonexistent/a=b/c", "/nonexistent/a::b/c")) {
            val result = MergeCommand().test(listOf("--dry-run", location))

            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains(location), result.output)
        }
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
