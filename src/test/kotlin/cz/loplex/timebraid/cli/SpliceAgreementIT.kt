package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.GitCli
import cz.loplex.timebraid.git.BraidWriter
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/**
 * The splice check and the writer answer one question twice — whether an input can be placed where
 * its destination puts it — the check before anything is written into the output, the writer while
 * it builds each tree. This holds them to the same answer: over random layouts, a dry run is refused
 * exactly when the writer fails.
 *
 * The write is driven without the command line, because the command line runs the same check in
 * front of the writer: a check refusing a layout the writer would take refuses both runs alike, and
 * they agree. Given the plan on its own, as [write] gives it, the writer answers for itself, and a
 * check too strict disagrees with it as plainly as one too lenient.
 *
 * Each layout is platform at the output root and backend at `backend`, with commit times drawn at
 * random, so platform's commits may come before backend's first or after it; and with the entry that
 * can meet backend drawn at random too, in platform's trees.
 */
class SpliceAgreementIT {

    @TempDir
    lateinit var tmp: Path

    private val start = Instant.parse("2021-05-04T09:00:00Z")

    @Test
    fun `a dry run is refused exactly where the write fails`() {
        var refused = 0
        var written = 0
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            val dir = tmp.resolve("seed-$seed")
            // Distinct minutes, so the braid's order is the one drawn here and not a tie-break.
            val minutes = (0 until 60).shuffled(random).iterator()
            fun at() = start.plusSeconds(60L * minutes.next())

            fun repo(name: String, trees: List<Map<String, String>>) =
                TestRepoBuilder.create(dir.resolve("$name.git")).use { r ->
                    var parent: ObjectId? = null
                    val times = trees.map { at() }.sorted()
                    for ((tree, time) in trees.zip(times)) {
                        parent = r.commit(name, listOfNotNull(parent), files = tree, at = time)
                    }
                    r.branch("main", parent!!)
                }

            // platform's own backend/ only ever in its first commits, which makes it mostly gone
            // before backend begins. Mostly: the times are drawn from one pool for both, so a first
            // commit of platform's can still follow backend's first, and its backend/ is then a
            // collision, refused like any other. A layout whose backend/ is gone by the time backend
            // begins is the one a check reading platform's trees without the braid would refuse.
            val platformCommits = random.nextInt(1, 4)
            val strayUntil = random.nextInt(0, platformCommits + 1)
            repo("platform", List(platformCommits) {
                mapOf("README.md" to "p$it") + if (it >= strayUntil) emptyMap() else random.pick(
                    mapOf("backend/stray.txt" to "p"),
                    mapOf("backend" to "a file"),
                )
            })
            repo("backend", List(random.nextInt(1, 3)) { mapOf("src/Main.kt" to "a$it") })

            val inputs = listOf(
                "--root-repo", "platform",
                dir.resolve("platform.git").toString(),
                dir.resolve("backend.git").toString(),
            )
            val dry = MergeCommand().test(listOf("--dry-run") + inputs)
            val out = dir.resolve("out.git")
            val refusal = write(dir, out)

            assertEquals(
                dry.statusCode == 0,
                refusal == null,
                "seed $seed: the dry run and the write disagree\n" +
                    "dry run:\n${dry.output}\nwrite: ${refusal ?: "written"}",
            )
            if (refusal == null) {
                written++
                if (GitCli.available) GitCli.fsck(out)
            } else {
                refused++
            }
        }
        // Either answer alone would say nothing about the other: the draw has to reach both.
        assertTrue(refused > 0 && written > 0, "refused $refused, written $written of $SEEDS")
    }

    /**
     * The layout the dry run was given, written as the runner writes it but with no check in front:
     * read, planned with the same destinations, fetched, and braided.
     *
     * @return `null` when the output was written, or the writer's refusal.
     */
    private fun write(dir: Path, out: Path): String? {
        val destinations = mapOf("platform" to null, "backend" to "backend")
        val opened = destinations.keys.map { SourceRepository.open(dir.resolve("$it.git")) }
        try {
            val inputs = CommitGraphReader.read(opened, OrderBy.COMMITTER)
            val repoOf = inputs.sources.map { it.source }.zip(opened).toMap()
            val subdirs = inputs.graph.sources.associateWith { destinations.getValue(it.name) }
            val plan = inputs.graph.braid(inputs.heads).plan(subdirs)
            TargetRepository.create(out, inputs.mainlineBranch).use { target ->
                for (input in inputs.sources) {
                    target.fetchFrom(repoOf.getValue(input.source), input.readRefs)
                }
                try {
                    BraidWriter(target, repoOf, inputs, plan).write()
                } catch (e: IllegalArgumentException) {
                    return e.message ?: "refused"
                }
                target.dropFetchRefs()
            }
            return null
        } finally {
            opened.forEach { it.close() }
        }
    }

    private fun <T> Random.pick(vararg options: T): T = options[nextInt(options.size)]

    private companion object {
        const val SEEDS = 100
    }
}
