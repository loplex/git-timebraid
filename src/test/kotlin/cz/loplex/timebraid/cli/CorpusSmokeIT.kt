package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.GitCli
import cz.loplex.timebraid.git.OutputRepo
import cz.loplex.timebraid.git.SourceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * A smoke run of the whole CLI over a large real-world corpus kept outside this tree.
 * Opt-in: point `-Dtimebraid.corpus` at a directory of bare clones.
 *
 * ```
 * mvn -q verify -Dit.test=CorpusSmokeIT -Dtimebraid.corpus=/path/to/bare-clones
 * ```
 *
 * The synthetic fixtures in [BraidPipelineIT] pin the behaviour; this one only checks that the same
 * pipeline survives contact with fourteen thousand real commits: it fscks clean, every provenance
 * edge is present, and the two defects the rewrite exists to fix (three-parent commits, tags) show
 * up in the output.
 */
@EnabledIfSystemProperty(named = "timebraid.corpus", matches = ".+")
class CorpusSmokeIT {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the whole pipeline survives the real corpus`() {
        GitCli.requireGit()
        val corpus = Path.of(System.getProperty("timebraid.corpus"))
        val repos = corpus.listDirectoryEntries("*.git").filter { it.isDirectory() }.sorted()
        assertTrue(repos.isNotEmpty()) { "no *.git directories under $corpus" }

        val out = tmp.resolve("merged.git")
        val result = MergeCommand().test(
            buildList {
                add("-o"); add(out.toString())
                repos.forEach { add(it.toString()) }
            }
        )
        assertEquals(0, result.statusCode, result.output)
        println(result.output)

        GitCli.fsck(out)

        // Every original parent edge is present in the rewrite.
        OutputRepo.assertEveryOriginalEdgePreserved(out)

        // A three-parent commit — a merge that also sits on the braid — is exactly what the prototype
        // could never produce.
        val threeParent = GitCli.run(out, "rev-list", "--all", "--parents")
            .lineSequence().count { it.trim().split(" ").size - 1 == 3 }
        assertTrue(threeParent > 0, "expected at least one three-parent commit")

        SourceRepository.open(out).use { repo ->
            assertTrue(repo.tags().isNotEmpty(), "no tags were written")
            assertTrue(repo.branches().any { it.name == "main" }, "no mainline branch")
        }
    }
}
