package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the writer: a plan becomes a repository git itself accepts.
 *
 * The corpus is the README's example plus the awkward parts — a merge on the braid, a side branch,
 * a branch that exists in only one input, a lightweight and an annotated tag:
 *
 * ```
 * backend   a1 09:00 <- a2 11:00 <- a3 15:00 (merge of f1)
 *                          \- f1 12:00 -/
 * webui     b1 10:00 <- b2 13:00
 * ```
 *
 * Braided by committer time the mainline is a1, b1, a2, b2, a3, so `a3` — already a merge at home —
 * is the three-parent case doc/how-it-works.md makes so much of.
 */
class BraidWriterTest {

    @TempDir
    lateinit var tmp: Path

    private fun at(hour: String) = Instant.parse("2021-05-04T$hour:00Z")

    private lateinit var original: Map<String, ObjectId>

    private fun corpus() {
        val ids = HashMap<String, ObjectId>()
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = at("09:00"))
            val a2 = repo.commit("a2\n\nwith a body line\n", parents = listOf(a1), at = at("11:00"))
            val f1 = repo.commit("f1", parents = listOf(a2), at = at("12:00"))
            val a3 = repo.commit("a3", parents = listOf(a2, f1), at = at("15:00"))
            repo.branch("main", a3)
            repo.branch("feature", f1)
            repo.lightweightTag("v1.0", a2)
            ids += mapOf("a1" to a1, "a2" to a2, "f1" to f1, "a3" to a3)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            // Authored before it was committed, so a swapped author and committer would show, and
            // in a zone of its own, so a writer that wrote every ident in UTC would show too.
            val b1 = repo.commit("b1", at = at("10:00"), authorAt = at("09:30"), zone = ZoneOffset.ofHours(2))
            val b2 = repo.commit("b2", parents = listOf(b1), at = at("13:00"))
            repo.branch("main", b2)
            repo.branch("esbuild-experiment", b1)
            repo.annotatedTag("v2.0", b2, message = "the second release\n", zone = ZoneOffset.ofHours(-5))
            ids += mapOf("b1" to b1, "b2" to b2)
        }
        original = ids
    }

    /** Runs the whole pipeline the way the CLI does, and returns the writer's summary. */
    private fun braid(
        out: Path,
        rootRepo: String? = null,
        subdirs: Map<String, String> = emptyMap(),
        options: WriteOptions = WriteOptions(),
    ): WriteSummary {
        val names = listOf("backend", "webui")
        val opened = names.map { SourceRepository.open(tmp.resolve("$it.git")) }
        try {
            val inputs = CommitGraphReader.read(opened, OrderBy.COMMITTER)
            val repoOf = inputs.sources.map { it.source }.zip(opened).toMap()
            val plan = inputs.graph.braid(inputs.heads).plan(inputs.graph.sources.associateWith { if (it.name == rootRepo) null else subdirs[it.name] ?: it.name })
            return TargetRepository.create(out, inputs.mainlineBranch).use { target ->
                // The same order the runner uses: the inputs' objects arrive by fetch, the braid is
                // written on top of them, and the refs the fetch needed are dropped afterwards.
                for (input in inputs.sources) {
                    target.fetchFrom(repoOf.getValue(input.source), input.readRefs)
                }
                val summary = BraidWriter(target, repoOf, inputs, plan, options).write()
                target.dropFetchRefs()
                summary
            }
        } finally {
            opened.forEach { it.close() }
        }
    }

    /** The output as plain data, keyed by the original sha recorded in the provenance trailer. */
    private class Output(val commits: List<SourceCommit>, val byOriginal: Map<String, SourceCommit>)

    private fun read(out: Path): Output {
        SourceRepository.open(out).use { repo ->
            val tips = repo.branches().map { it.target } + repo.tags().map { it.target }
            val commits = repo.readReachable(tips)
            return Output(commits, commits.associateBy { originalShaOf(it) })
        }
    }

    private fun originalShaOf(commit: SourceCommit): String =
        Regex("commit=([0-9a-f]{40})").find(commit.message)?.groupValues?.get(1)
            ?: error("no provenance trailer on: ${commit.message}")

    private fun new(out: Output, name: String): SourceCommit =
        out.byOriginal[original.getValue(name).name] ?: error("$name was not written")

    @Test
    fun `the braid is written with every original edge kept`() {
        corpus()
        val out = tmp.resolve("merged.git")
        val summary = braid(out)

        assertEquals(6, summary.commits)
        assertEquals(3, summary.branches)
        assertEquals(2, summary.tags)
        assertEquals("main", summary.head)

        val written = read(out)
        assertEquals(6, written.commits.size)

        // The three-parent case: a3 was a merge at home and its braid predecessor is b2.
        val a3 = new(written, "a3")
        assertEquals(
            listOf(new(written, "b2").id, new(written, "a2").id, new(written, "f1").id),
            a3.parents,
        )

        // An ordinary commit whose predecessor is in the other repository gains exactly one parent.
        assertEquals(listOf(new(written, "b1").id, new(written, "a1").id), new(written, "a2").parents)
        // The first commit of the braid keeps having no parent at all.
        assertEquals(emptyList<ObjectId>(), new(written, "a1").parents)
        // An off-braid commit is left exactly as it was.
        assertEquals(listOf(new(written, "a2").id), new(written, "f1").parents)
    }

    @Test
    fun `every original parent edge survives, checked through the provenance trailer`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)
        val written = read(out)

        for (commit in written.commits) {
            val trailer = Regex("parents=([0-9a-f,]*)]").find(commit.message)
                ?: error("no parent list in the trailer of: ${commit.message}")
            val originalParents = trailer.groupValues[1].split(",").filter { it.isNotEmpty() }
            for (parent in originalParents) {
                val rewritten = written.byOriginal[parent] ?: error("parent $parent was not written")
                assertTrue(
                    rewritten.id in commit.parents,
                    "the edge $parent -> ${originalShaOf(commit)} was lost",
                )
            }
        }
    }

    @Test
    fun `each commit carries the whole world as of its moment in the braid`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)
        val written = read(out)

        SourceRepository.open(out).use { repo ->
            fun entries(name: String) = repo.topLevelEntries(new(written, name).tree).associate {
                it.name to it.id
            }

            // b1 at 10:00 sees backend as of a1 and nothing else of its own history.
            assertEquals(setOf("backend", "webui"), entries("b1").keys)
            // The first commit of the braid has only its own repository.
            assertEquals(setOf("backend"), entries("a1").keys)
            // A side branch freezes the other strand where it was cut: f1 forks off a2, so webui
            // stays at b1 even though the braid went on to b2.
            assertEquals(setOf("backend", "webui"), entries("f1").keys)
            assertEquals(entries("b1")["webui"], entries("f1")["webui"])
            assertNotEquals(entries("a3")["webui"], entries("f1")["webui"])
        }

        // The subdirectory entry is the original tree object itself, not a copy of it.
        SourceRepository.open(tmp.resolve("webui.git")).use { source ->
            val b2Tree = source.readReachable(listOf(original.getValue("b2")))
                .single { it.id == original.getValue("b2") }.tree
            SourceRepository.open(out).use { repo ->
                val entry = repo.topLevelEntries(new(written, "a3").tree).single { it.name == "webui" }
                assertEquals(b2Tree, entry.id)
            }
        }
    }

    @Test
    fun `branches and tags are recreated, tags prefixed and annotations kept`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)
        val written = read(out)

        SourceRepository.open(out).use { repo ->
            assertEquals(
                listOf("esbuild-experiment", "feature", "main"),
                repo.branches().map { it.name },
            )
            assertEquals(new(written, "a3").id, repo.resolveBranch("main"))
            assertEquals(new(written, "f1").id, repo.resolveBranch("feature"))
            assertEquals(new(written, "b1").id, repo.resolveBranch("esbuild-experiment"))

            val tags = repo.tags().associateBy { it.name }
            assertEquals(setOf("backend/v1.0", "webui/v2.0"), tags.keys)
            assertEquals(new(written, "a2").id, tags.getValue("backend/v1.0").target)
            assertEquals(new(written, "b2").id, tags.getValue("webui/v2.0").target)

            // The lightweight tag stays lightweight; the annotated one keeps its message.
            assertEquals(null, tags.getValue("backend/v1.0").annotation)
            assertEquals("the second release\n", tags.getValue("webui/v2.0").annotation?.message)
        }
    }

    @Test
    fun `the subject prefix and the provenance trailer are attached without eating the body`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)
        val written = read(out)

        val a2 = new(written, "a2")
        assertEquals(
            "backend: a2\n\nwith a body line\n\n" +
                "[timebraid: repo=\"backend\" commit=${original.getValue("a2").name}" +
                " parents=${original.getValue("a1").name}]\n",
            a2.message,
        )
    }

    @Test
    fun `{subdir} in the subject prefix is the destination, and the name at the root`() {
        // backend lands in server/, so its name and its destination differ, which is what tells
        // the two placeholders apart; webui is the root repository, which has no subdirectory.
        corpus()
        val out = tmp.resolve("merged.git")
        braid(
            out,
            rootRepo = "webui",
            subdirs = mapOf("backend" to "server"),
            options = WriteOptions(subjectPrefix = "{repo} in {subdir}: ", provenance = false),
        )

        SourceRepository.open(out).use { repo ->
            val messages = repo.readReachable(listOf(repo.resolveBranch("main")!!)).map { it.message }
            assertTrue(messages.contains("backend in server: a1"), messages.toString())
            assertTrue(messages.contains("webui in webui: b1"), messages.toString())
        }
    }

    @Test
    fun `provenance and the subject prefix can be turned off`() {
        corpus()
        val out = tmp.resolve("plain.git")
        braid(out, options = WriteOptions(subjectPrefix = "", provenance = false))

        SourceRepository.open(out).use { repo ->
            val messages = repo.readReachable(listOf(repo.resolveBranch("main")!!)).map { it.message }
            assertTrue(messages.contains("a1"), messages.toString())
            assertTrue(messages.none { "timebraid" in it }, messages.toString())
        }
    }

    @Test
    fun `idents survive byte for byte, so a second run produces the same shas`() {
        corpus()
        val first = tmp.resolve("first.git")
        val second = tmp.resolve("second.git")
        braid(first)
        braid(second)

        // Byte for byte: name, email, time and zone. PersonIdent's own equals leaves the zone out.
        fun bytes(ident: PersonIdent?) = ident?.toExternalString()
        val originals = listOf("backend", "webui").flatMap { name ->
            SourceRepository.open(tmp.resolve("$name.git")).use { repo ->
                repo.readReachable(repo.branches().map { it.target })
            }
        }.associateBy { it.id.name }
        for (commit in read(first).commits) {
            val original = originals.getValue(originalShaOf(commit))
            assertEquals(bytes(original.author), bytes(commit.author), "the author of ${original.message}")
            assertEquals(bytes(original.committer), bytes(commit.committer), "the committer of ${original.message}")
        }
        // An annotated tag carries an ident of its own, its tagger.
        val tagger = SourceRepository.open(tmp.resolve("webui.git")).use { it.tags().single().annotation?.tagger }
        SourceRepository.open(first).use { repo ->
            assertEquals(bytes(tagger), bytes(repo.tags().single { it.name == "webui/v2.0" }.annotation?.tagger))
        }

        SourceRepository.open(first).use { a ->
            SourceRepository.open(second).use { b ->
                assertEquals(a.resolveBranch("main"), b.resolveBranch("main"))
            }
        }
    }

    @Test
    fun `--root-repo splices the root repository's own entries beside the subdirectories`() {
        corpus()
        val out = tmp.resolve("rooted.git")
        braid(out, rootRepo = "backend")
        val written = read(out)

        SourceRepository.open(out).use { repo ->
            // "f" is the file TestRepoBuilder puts in every commit; it lands at the root, next to webui/.
            assertEquals(
                setOf("f", "webui"),
                repo.topLevelEntries(new(written, "a3").tree).map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun `a subdirectory that collides with the root repository's content is rejected`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            repo.branch("main", repo.commit("a1", files = mapOf("webui" to "a stray file")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1"))
        }

        val error = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("colliding.git"), rootRepo = "backend")
        }
        assertTrue(error.message!!.contains("'webui'"), error.message)
    }

    @Test
    fun `a branch name used by two inputs is qualified with the repository name`() {
        corpus()
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("release", original.getValue("a2")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("release", original.getValue("b1")) }

        val out = tmp.resolve("qualified.git")
        braid(out)

        SourceRepository.open(out).use { repo ->
            assertTrue(repo.branches().map { it.name }.containsAll(listOf("backend/release", "webui/release")))
            assertEquals(null, repo.resolveBranch("release"))
        }
    }

    @Test
    fun `refusing to overwrite an existing output, unless forced`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)

        val error = assertThrows<IllegalArgumentException> {
            TargetRepository.create(out, "main").close()
        }
        assertTrue(error.message!!.contains("--force"), error.message)

        TargetRepository.create(out, "main", force = true).close()
    }

    @Test
    fun `git itself accepts the result`() {
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)
        assertEquals("", fsck(out))
    }

    /**
     * Runs `git fsck --strict` and returns its output; the test is skipped when git is absent.
     *
     * `--no-dangling` because a braided output is expected to hold unreferenced objects: the
     * inputs' own commits come in with the fetch, and so does each annotated tag's original object,
     * and the braid points no ref at either — see [TargetRepository.dropFetchRefs].
     * `git gc --prune=now` reclaims them; corruption is what this is looking for.
     */
    private fun fsck(dir: Path): String {
        val process = try {
            ProcessBuilder("git", "fsck", "--strict", "--no-progress", "--no-dangling")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            assumeTrue(false, "git is not on PATH: ${e.message}")
            error("unreachable")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git fsck did not finish")
        assertEquals(0, process.exitValue(), output)
        return output
    }
}
