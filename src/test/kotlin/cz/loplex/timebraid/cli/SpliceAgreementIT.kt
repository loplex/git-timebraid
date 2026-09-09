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
 * it builds each tree. This holds them to the same answer: over random three-level layouts, a dry
 * run is refused exactly when the writer fails.
 *
 * The write is driven without the command line, because the command line runs the same check in
 * front of the writer: a check refusing a layout the writer would take refuses both runs alike, and
 * they agree. Given the plan on its own, as [write] gives it, the writer answers for itself, and a
 * check too strict disagrees with it as plainly as one too lenient.
 *
 * Each layout is platform at the output root, libs at `libs` and backend at `libs/backend`, with
 * commit times drawn at random, so the middle repository may begin before backend or after it; and
 * with the entries a splice can meet drawn at random too, in platform's and in libs' trees. One
 * layout the draw seldom reaches on its own is fixed besides.
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

            // Mostly nothing in the way, so that a layout is not refused before the part that
            // matters is reached; and platform's own libs/ only ever in its first commits, which
            // makes it mostly gone before libs begins. Mostly: the times are drawn from one pool
            // for all three, so a first commit of platform's can still follow libs' first, and its
            // libs/ is then a collision at libs itself, refused like any other.
            val platformCommits = random.nextInt(1, 4)
            val strayUntil = random.nextInt(0, platformCommits + 1)
            repo("platform", List(platformCommits) {
                mapOf("README.md" to "p$it") + if (it >= strayUntil) emptyMap() else random.pick(
                    mapOf("libs/backend/stray.txt" to "p"),
                    mapOf("libs/backend/stray.txt" to "p"),
                    mapOf("libs/other.txt" to "p"),
                    mapOf("libs" to "a file"),
                )
            })
            repo("libs", List(random.nextInt(1, 3)) {
                mapOf("shared.txt" to "l$it") + random.pick(
                    emptyMap(),
                    emptyMap(),
                    emptyMap(),
                    mapOf("backend" to "a file"),
                    mapOf("backend/stray.txt" to "l"),
                )
            })
            repo("backend", List(random.nextInt(1, 3)) { mapOf("src/Main.kt" to "a$it") })

            val dry = MergeCommand().test(listOf("--dry-run") + inputs(dir))
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

    @Test
    fun `backend placed inside the root repository's own libs is refused by both`() {
        // The stretch the three levels exist for, which the draw above seldom reaches with nothing
        // else in the way: backend begins before libs, so it lands inside platform's own `libs/`,
        // and platform holds `libs/backend/` there until its second commit. Libs begins after both.
        val dir = tmp.resolve("fixed")
        TestRepoBuilder.create(dir.resolve("platform.git")).use { r ->
            val p1 = r.commit("p1", files = mapOf("README.md" to "p1", "libs/backend/stray.txt" to "p"), at = start)
            val p2 = r.commit("p2", listOf(p1), files = mapOf("README.md" to "p2"), at = start.plusSeconds(120))
            r.branch("main", p2)
        }
        TestRepoBuilder.create(dir.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("b1", files = mapOf("src/Main.kt" to "b1"), at = start.plusSeconds(60)))
        }
        TestRepoBuilder.create(dir.resolve("libs.git")).use { r ->
            r.branch("main", r.commit("l1", files = mapOf("shared.txt" to "l1"), at = start.plusSeconds(180)))
        }

        val dry = MergeCommand().test(listOf("--dry-run") + inputs(dir))
        val refusal = write(dir, dir.resolve("out.git"))

        assertEquals(1, dry.statusCode, dry.output)
        assertTrue(refusal != null, "the writer put backend over platform's own libs/backend/")
    }

    /** Platform at the output root, libs at `libs` and backend at `libs/backend`, spliced. */
    private fun inputs(dir: Path) = listOf(
        "--splice",
        "--root-repo", "platform",
        dir.resolve("platform.git").toString(),
        dir.resolve("libs.git").toString() + "::=libs",
        dir.resolve("backend.git").toString() + "::=libs/backend",
    )

    /**
     * The same agreement where what is in the way is a gitlink, with `--dissolve-submodules` drawn
     * too: a gitlink at the destination gives way under the flag, and one above it never does, since
     * no input's content can take the submodule's place on the way down.
     */
    @Test
    fun `a dry run is refused exactly where the write fails, with gitlinks in the way`() {
        val pinned = ObjectId.fromString("06df2481b3f0ad0e5d6d0f04ac4b5f0e0eaa1234")
        var refused = 0
        var written = 0
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            val dir = tmp.resolve("gitlinks-$seed")
            val minutes = (0 until 60).shuffled(random).iterator()

            fun repo(name: String, gitlinks: List<Map<String, ObjectId>>) =
                TestRepoBuilder.create(dir.resolve("$name.git")).use { r ->
                    var parent: ObjectId? = null
                    val times = gitlinks.map { start.plusSeconds(60L * minutes.next()) }.sorted()
                    for ((links, time) in gitlinks.zip(times)) {
                        val files = mapOf("$name.txt" to "$time")
                        parent = r.commit(name, listOfNotNull(parent), files, links, at = time)
                    }
                    r.branch("main", parent!!)
                }

            repo("platform", List(random.nextInt(1, 3)) {
                random.pick(emptyMap(), mapOf("libs" to pinned), mapOf("libs/backend" to pinned))
            })
            repo("libs", List(random.nextInt(1, 3)) {
                random.pick(emptyMap(), mapOf("backend" to pinned), mapOf("backend/x" to pinned))
            })
            repo("backend", List(random.nextInt(1, 3)) { emptyMap() })
            val dissolve = random.nextBoolean()

            val inputs = listOfNotNull(
                "--splice",
                "--dissolve-submodules".takeIf { dissolve },
                "--root-repo", "platform",
                dir.resolve("platform.git").toString(),
                dir.resolve("libs.git").toString() + "::=libs",
                dir.resolve("backend.git").toString() + "::=libs/backend",
            )
            val dry = MergeCommand().test(listOf("--dry-run") + inputs)
            val out = dir.resolve("out.git")
            val refusal = write(dir, out, dissolve)

            assertEquals(
                dry.statusCode == 0,
                refusal == null,
                "seed $seed (dissolve $dissolve): the dry run and the write disagree\n" +
                    "dry run:\n${dry.output}\nwrite: ${refusal ?: "written"}",
            )
            if (refusal == null) written++ else refused++
        }
        assertTrue(refused > 0 && written > 0, "refused $refused, written $written of $SEEDS")
    }

    /**
     * The layout the dry run was given, written as the runner writes it but with no check in front:
     * read, planned with `--splice` and the same destinations, fetched, and braided, dissolving a
     * gitlink at a destination when [dissolveSubmodules] says so.
     *
     * @return `null` when the output was written, or the writer's refusal.
     */
    private fun write(dir: Path, out: Path, dissolveSubmodules: Boolean = false): String? {
        val destinations = mapOf("platform" to null, "libs" to "libs", "backend" to "libs/backend")
        val opened = destinations.keys.map { SourceRepository.open(dir.resolve("$it.git")) }
        try {
            val inputs = CommitGraphReader.read(opened, OrderBy.COMMITTER)
            val repoOf = inputs.sources.map { it.source }.zip(opened).toMap()
            val subdirs = inputs.graph.sources.associateWith { destinations.getValue(it.name) }
            val plan = inputs.graph.braid(inputs.heads).plan(subdirs, splice = true)
            TargetRepository.create(out, inputs.mainlineBranch).use { target ->
                for (input in inputs.sources) {
                    target.fetchFrom(repoOf.getValue(input.source), input.readRefs)
                }
                try {
                    BraidWriter(target, repoOf, inputs, plan, dissolveSubmodules = dissolveSubmodules).write()
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
