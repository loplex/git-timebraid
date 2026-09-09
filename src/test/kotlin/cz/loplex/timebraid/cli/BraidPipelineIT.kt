package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.GitCli
import cz.loplex.timebraid.git.OutputRepo
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * The whole pipeline (`MergeCommand` → clone/read/plan/write) exercised through the command
 * line, one fixture per behaviour that has to hold end to end. Every fixture is built by
 * [TestRepoBuilder] with fixed idents and a controlled clock, so its output is byte-identical on
 * every run; where git is on `PATH` each output is run through `git fsck --strict`.
 *
 * The reference model is the README's two-strand example:
 *
 * ```
 * backend   a1 09:00 ── a2 11:00 ── a3 15:00 (merge of f1)
 *                          └ f1 12:00 ┘
 * webui     b1 10:00 ── b2 13:00
 * ```
 */
class BraidPipelineIT {

    @TempDir
    lateinit var tmp: Path

    private fun at(hm: String) = Instant.parse("2021-05-04T$hm:00Z")

    /** Runs the CLI and asserts it exited cleanly. */
    private fun braid(vararg args: String) =
        MergeCommand().test(args.toList()).also { assertEquals(0, it.statusCode, it.output) }

    private fun path(name: String) = tmp.resolve(name).toString()

    // ── the reference two-strand history ────────────────────────────────────────────────────────

    /** The reference history, returning `name -> original sha` so a test can key the output by it. */
    private fun reference(): Map<String, ObjectId> {
        val ids = HashMap<String, ObjectId>()
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            val a2 = r.commit("a2\n\nwith a body line\n", parents = listOf(a1), at = at("11:00"))
            val f1 = r.commit("f1", parents = listOf(a2), at = at("12:00"))
            val a3 = r.commit("a3", parents = listOf(a2, f1), at = at("15:00"))
            r.branch("main", a3)
            r.branch("feature", f1)
            r.lightweightTag("v1.0", a2)
            ids += mapOf("a1" to a1, "a2" to a2, "f1" to f1, "a3" to a3)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            val b1 = r.commit("b1", at = at("10:00"))
            val b2 = r.commit("b2", parents = listOf(b1), at = at("13:00"))
            r.branch("main", b2)
            r.branch("esbuild-experiment", b1)
            r.annotatedTag("v2.0", b2, message = "the second release\n")
            ids += mapOf("b1" to b1, "b2" to b2)
        }
        return ids
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two linear repos interleave by committer time`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            r.branch("main", r.commit("a2", parents = listOf(a1), at = at("11:00")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", at = at("10:00")))
        }
        val out = tmp.resolve("merged.git")

        val result = braid("-o", out.toString(), "--plan-out", path("plan.txt"), path("backend.git"), path("webui.git"))

        assertTrue(result.output.contains("commits: 3 (braid: 3)"), result.output)
        assertEquals(
            listOf("backend", "webui", "backend"),
            Path.of(path("plan.txt")).let { java.nio.file.Files.readAllLines(it) }
                .filter { " * " in it }
                .map { it.substringAfter(" * ").substringBefore("/") },
        )
        OutputRepo.assertEveryOriginalEdgePreserved(out)
        if (GitCli.available) {
            GitCli.fsck(out)
            // First parent of the braid tip is the previous commit in time, whichever strand it is.
            assertEquals(3, GitCli.run(out, "rev-list", "--count", "main").toInt())
        }

        // Determinism: a second run into a fresh directory produces the very same tip.
        val again = tmp.resolve("again.git")
        braid("-o", again.toString(), path("backend.git"), path("webui.git"))
        SourceRepository.open(out).use { a ->
            SourceRepository.open(again).use { b ->
                assertEquals(a.resolveBranch("main"), b.resolveBranch("main"))
            }
        }
    }

    @Test
    fun `--keep-remotes leaves the inputs' own history walkable in the output`() {
        val ids = reference()
        val out = tmp.resolve("merged.git")

        braid("-o", out.toString(), "--keep-remotes", path("backend.git"), path("webui.git"))

        GitCli.requireGit()
        GitCli.fsck(out)

        // Every branch and every tag of both inputs, at its own sha. Tags go under `tags/` so that a
        // branch and a tag of one name cannot land on the same ref, and they are mirrored at all
        // because a commit an input reaches only from a tag would otherwise have no ref pointing at
        // it -- present in the output but unreachable.
        assertEquals(
            listOf(
                "refs/remotes/backend/feature",
                "refs/remotes/backend/main",
                "refs/remotes/backend/tags/v1.0",
                "refs/remotes/webui/esbuild-experiment",
                "refs/remotes/webui/main",
                "refs/remotes/webui/tags/v2.0",
            ),
            GitCli.run(out, "for-each-ref", "--format=%(refname)", "refs/remotes").lines().sorted(),
        )
        assertEquals(ids.getValue("a3").name, GitCli.run(out, "rev-parse", "refs/remotes/backend/main"))
        assertEquals(ids.getValue("f1").name, GitCli.run(out, "rev-parse", "refs/remotes/backend/feature"))
        assertEquals(ids.getValue("b2").name, GitCli.run(out, "rev-parse", "refs/remotes/webui/main"))
        // v1.0 was a lightweight tag of a2 and v2.0 an annotated tag of b2; either way the mirror
        // names the commit, because the annotation itself is recreated under the output's own tag.
        assertEquals(ids.getValue("a2").name, GitCli.run(out, "rev-parse", "refs/remotes/backend/tags/v1.0"))
        assertEquals(ids.getValue("b2").name, GitCli.run(out, "rev-parse", "refs/remotes/webui/tags/v2.0"))

        // The originals are complete, not just their tips: rev-list cannot count a history whose
        // parent is missing, and `--objects` cannot list one whose trees are missing -- which is the
        // point, because those trees were never transferred, only reused where the braid put them.
        assertEquals(4, GitCli.run(out, "rev-list", "--count", "refs/remotes/backend/main").toInt())
        assertEquals(2, GitCli.run(out, "rev-list", "--count", "refs/remotes/webui/main").toInt())
        GitCli.run(out, "rev-list", "--objects", "--remotes")

        // The whole point of mirroring the tags: every original the run read is now reachable, so
        // nothing of the inputs survives only as an object nobody can name.
        assertEquals(
            ids.size,
            GitCli.run(out, "rev-list", "--count", "--remotes").toInt(),
            "every original commit should be reachable from refs/remotes",
        )

        // And the braid is untouched by any of it: the mainline is still the interleaving of both.
        assertEquals(6, GitCli.run(out, "rev-list", "--count", "main").toInt())
    }

    @Test
    fun `-b keeps the objects of an unselected branch out of the output entirely`() {
        val ids = HashMap<String, ObjectId>()
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            val a2 = r.commit("a2", parents = listOf(a1), at = at("11:00"))
            // x1 hangs off a1 and is never merged, so `experiment` is the only way to reach it.
            val x1 = r.commit("x1", parents = listOf(a1), at = at("12:00"))
            r.branch("main", a2)
            r.branch("experiment", x1)
            ids += mapOf("a1" to a1, "a2" to a2, "x1" to x1)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", at = at("10:00")))
        }
        val out = tmp.resolve("merged.git")

        braid("-o", out.toString(), "-b", "main", path("backend.git"), path("webui.git"))

        GitCli.requireGit()
        GitCli.fsck(out)

        // -b says which branches are read, and the output is filled by fetching exactly those, so a
        // commit only that branch could reach is not merely unreferenced -- its object is not there.
        // The check is on the *original* sha: a rewritten x1 would be a different object, and the
        // originals do arrive, which is what makes this worth asserting rather than assuming.
        assertEquals(
            listOf("refs/heads/main"),
            GitCli.run(out, "for-each-ref", "--format=%(refname)").lines(),
        )
        assertTrue(
            objectMissing(out, ids.getValue("x1")),
            "x1 came across even though `experiment` was not read",
        )
        for (name in listOf("a1", "a2")) {
            assertTrue(
                !objectMissing(out, ids.getValue(name)),
                "$name is on the mainline and should have been transferred",
            )
        }
    }

    /** Whether [id] is absent from the repository at [dir], asked the way git itself answers it. */
    private fun objectMissing(dir: Path, id: ObjectId): Boolean {
        GitCli.requireGit()
        val process = ProcessBuilder("git", "cat-file", "-e", id.name)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readAllBytes()
        return process.waitFor() != 0
    }

    @Test
    fun `a merge on the mainline is rewritten with three parents`() {
        val ids = reference()
        val out = tmp.resolve("merged.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        val written = OutputRepo.read(out)
        val a3 = written.byOriginalSha.getValue(ids.getValue("a3").name)
        assertEquals(3, a3.parents.size, "a3 was a merge at home and gains a braid parent on top")
        // Its braid parent is b2 (13:00, the previous commit in time), then its two home parents.
        assertEquals(written.byOriginalSha.getValue(ids.getValue("b2").name).id, a3.parents[0])
        OutputRepo.assertEveryOriginalEdgePreserved(out)

        if (GitCli.available) {
            GitCli.fsck(out)
            val parentCounts = GitCli.run(out, "rev-list", "--all", "--parents")
                .lines().map { it.trim().split(" ").size - 1 }.toSet()
            assertTrue(3 in parentCounts, "no three-parent commit in $parentCounts")
        }
    }

    @Test
    fun `--root-repo places one strand at the output root`() {
        val ids = reference()
        val out = tmp.resolve("rooted.git")
        braid("-o", out.toString(), "--root-repo", "backend", path("backend.git"), path("webui.git"))

        val a3 = OutputRepo.read(out).byOriginalSha.getValue(ids.getValue("a3").name)
        SourceRepository.open(out).use { repo ->
            val top = repo.entriesOf(a3.tree).map { it.name }
            // "f" is TestRepoBuilder's default file; it lands at the root, next to webui/.
            assertTrue(top.contains("f"), top.toString())
            assertTrue(top.contains("webui"), top.toString())
        }
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `a side branch keeps the other strand frozen where it forked`() {
        val ids = reference()
        val out = tmp.resolve("merged.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        val written = OutputRepo.read(out)
        SourceRepository.open(out).use { repo ->
            fun webuiTreeOf(name: String): ObjectId =
                repo.entriesOf(written.byOriginalSha.getValue(ids.getValue(name).name).tree)
                    .single { it.name == "webui" }.id
            // f1 forked off a2 (11:00) while webui was at b1 (10:00); the braid went on to b2 (13:00).
            assertEquals(webuiTreeOf("b1"), webuiTreeOf("f1"))
            assertNotEquals(webuiTreeOf("f1"), webuiTreeOf("a3"))
        }
    }

    /**
     * `backend`'s `m` (12:00) merges in `f`, timestamped 20:00 -- a slow review, or a clock skewed the
     * other way. `webui` has commits at 11:00 and 13:00, so whether `f` is allowed to delay `m`
     * decides which of them becomes `m`'s braid predecessor.
     */
    private fun lateMergeFixture(): Map<String, ObjectId> {
        val ids = HashMap<String, ObjectId>()
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            val a2 = r.commit("a2", parents = listOf(a1), at = at("10:00"))
            val f = r.commit("f", parents = listOf(a1), at = at("20:00"))
            val m = r.commit("m", parents = listOf(a2, f), at = at("12:00"))
            r.branch("main", m)
            r.branch("feature", f)
            ids += mapOf("a1" to a1, "a2" to a2, "f" to f, "m" to m)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            val b1 = r.commit("b1", at = at("11:00"))
            val b2 = r.commit("b2", parents = listOf(b1), at = at("13:00"))
            r.branch("main", b2)
            ids += mapOf("b1" to b1, "b2" to b2)
        }
        return ids
    }

    @Test
    fun `a merge whose merged-in branch is timestamped late still lands at its own time`() {
        // Only the mainlines decide where the strands interleave, so m takes its place by its own
        // 12:00, between b1 (11:00) and b2 (13:00). Waiting for f as well would push m past b2 and
        // give it a braid predecessor timestamped after itself -- which the checkout would then show
        // as webui/ at 13:00 under a commit recorded at 12:00.
        val ids = lateMergeFixture()
        val out = tmp.resolve("merged.git")

        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        val written = OutputRepo.read(out)
        fun rewritten(name: String) = written.byOriginalSha.getValue(ids.getValue(name).name)
        val m = rewritten("m")
        assertEquals(3, m.parents.size, "m was a merge at home and gains a braid parent on top")
        assertEquals(rewritten("b1").id, m.parents[0], "m's braid predecessor should be b1, not b2")

        SourceRepository.open(out).use { repo ->
            fun webuiTreeOf(name: String): ObjectId =
                repo.entriesOf(rewritten(name).tree).single { it.name == "webui" }.id
            // The observable consequence: m shows webui/ as it stood before m's own recorded moment.
            assertEquals(webuiTreeOf("b1"), webuiTreeOf("m"))
            assertNotEquals(webuiTreeOf("b2"), webuiTreeOf("m"))
        }

        OutputRepo.assertEveryOriginalEdgePreserved(out)
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `--interleave-ref lets that branch delay the merge that brings it in`() {
        // The same fixture with f opted in: m now waits for f, so it lands after webui's whole
        // history and its braid predecessor becomes b2 -- later than m's own timestamp. That is the
        // trade the option exists to make available, and it is the behaviour the tool had before the
        // braid and the write order were separated.
        val ids = lateMergeFixture()
        val out = tmp.resolve("merged.git")

        braid(
            "-o", out.toString(),
            "--interleave-ref", "refs/heads/feature",
            path("backend.git"), path("webui.git"),
        )

        val written = OutputRepo.read(out)
        fun rewritten(name: String) = written.byOriginalSha.getValue(ids.getValue(name).name)
        val m = rewritten("m")
        assertEquals(3, m.parents.size, "the parent rule is unchanged by the interleave scope")
        assertEquals(rewritten("b2").id, m.parents[0], "m should now follow b2, not b1")

        SourceRepository.open(out).use { repo ->
            fun webuiTreeOf(name: String): ObjectId =
                repo.entriesOf(rewritten(name).tree).single { it.name == "webui" }.id
            assertEquals(webuiTreeOf("b2"), webuiTreeOf("m"))
        }

        OutputRepo.assertEveryOriginalEdgePreserved(out)
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `--interleave-ref matching nothing leaves the braid exactly as it was`() {
        val ids = lateMergeFixture()
        val out = tmp.resolve("merged.git")

        braid(
            "-o", out.toString(),
            "--interleave-ref", "refs/heads/does-not-exist",
            path("backend.git"), path("webui.git"),
        )

        val written = OutputRepo.read(out)
        val m = written.byOriginalSha.getValue(ids.getValue("m").name)
        val b1 = written.byOriginalSha.getValue(ids.getValue("b1").name)
        assertEquals(b1.id, m.parents[0], "an unmatched pattern must not widen the scope")
    }

    @Test
    fun `a branch used by only one input comes out on its own, no configuration`() {
        reference()
        val out = tmp.resolve("merged.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        SourceRepository.open(out).use { repo ->
            assertEquals(
                listOf("esbuild-experiment", "feature", "main"),
                repo.branches().map { it.name },
            )
        }
    }

    @Test
    fun `clock skew, a commit older than its parent still follows it`() {
        val ids = HashMap<String, ObjectId>()
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            ids["a1"] = r.commit("a1", at = at("10:00"))
            // Rebased locally: lands after a1 but carries an earlier timestamp.
            ids["a2"] = r.commit("a2", parents = listOf(ids.getValue("a1")), at = at("09:00"))
            r.branch("main", ids.getValue("a2"))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", at = at("09:30")))
        }
        val out = tmp.resolve("skewed.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        val written = OutputRepo.read(out)
        val a1 = written.byOriginalSha.getValue(ids.getValue("a1").name)
        val a2 = written.byOriginalSha.getValue(ids.getValue("a2").name)
        assertTrue(a1.id in a2.parents, "ancestry must beat the timestamp — a2 keeps a1 as a parent")
        OutputRepo.assertEveryOriginalEdgePreserved(out)
        // No cycle was introduced; git agrees.
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `identical trees across the braid are stored once`() {
        // Every commit writes the same single file, so many braided root-tree states repeat.
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", files = mapOf("shared" to "same"), at = at("09:00"))
            r.branch("main", r.commit("a2", parents = listOf(a1), files = mapOf("shared" to "same"), at = at("11:00")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", files = mapOf("shared" to "same"), at = at("10:00")))
        }
        val out = tmp.resolve("dedup.git")
        val result = braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        // The report states how many trees were written; it is well below one per commit.
        val trees = Regex("(\\d+) trees").find(result.output)?.groupValues?.get(1)?.toInt()
            ?: error("no tree count in:\n${result.output}")
        assertTrue(trees < 3, "expected tree reuse, wrote $trees trees for 3 commits")
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `a subdirectory that collides with the root repo's content is a clear error`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("webui" to "a stray file at the root")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1"))
        }

        val result = MergeCommand().test(
            listOf("-o", path("out.git"), "--root-repo", "backend", path("backend.git"), path("webui.git")),
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'webui'"), result.output)
        assertTrue(result.output.contains("collides"), result.output)
    }

    @Test
    fun `nested destinations are built out and spliced into the root repository's own directory`() {
        // platform owns libs/ already; backend is placed inside it, webui two levels down under a
        // prefix nothing else has. The splice is the point: libs/ ends up holding both.
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch(
                "main",
                r.commit(
                    "p1",
                    files = mapOf("libs/README.md" to "the platform's own", "build.txt" to "x"),
                    at = at("09:00"),
                ),
            )
        }
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("src/Main.kt" to "a"), at = at("10:00")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", files = mapOf("index.html" to "b"), at = at("11:00")))
        }

        val out = tmp.resolve("nested.git")
        braid(
            "-o", out.toString(),
            "--root-repo", "platform",
            path("platform.git"),
            path("backend.git") + "::=libs/backend",
            path("webui.git") + "::=apps/frontend/webui",
        )

        SourceRepository.open(out).use { repo ->
            val main = repo.branches().single { it.name == "main" }.target
            val tip = repo.readReachable(listOf(main)).single { it.id == main }
            fun names(path: String): List<String> {
                var tree = tip.tree
                for (segment in path.split('/').filter { it.isNotEmpty() }) {
                    tree = repo.entriesOf(tree).single { it.name == segment }.id
                }
                return repo.entriesOf(tree).map { it.name }
            }

            assertEquals(listOf("apps", "build.txt", "libs"), names(""))
            // The root repository's own file and the input placed beside it, in one tree.
            assertEquals(listOf("README.md", "backend"), names("libs"))
            assertEquals(listOf("src"), names("libs/backend"))
            assertEquals(listOf("index.html"), names("apps/frontend/webui"))
        }

        if (GitCli.available) {
            GitCli.fsck(out)
            assertEquals("a", GitCli.run(out, "show", "main:libs/backend/src/Main.kt"))
            assertEquals("the platform's own", GitCli.run(out, "show", "main:libs/README.md"))
        }
        OutputRepo.assertEveryOriginalEdgePreserved(out)
    }

    @Test
    fun `a nested destination reaching into a file of the root repository is a clear error`() {
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch("main", r.commit("p1", files = mapOf("libs" to "a stray file where a directory is wanted")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r -> r.branch("main", r.commit("b1")) }

        val result = MergeCommand().test(
            listOf(
                "-o", path("out.git"),
                "--root-repo", "platform",
                path("platform.git"),
                path("webui.git") + "::=libs/webui",
            ),
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'libs'"), result.output)
        assertTrue(result.output.contains("not a directory"), result.output)
    }

    @Test
    fun `one destination inside another is refused until --splice asks for it`() {
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch("main", r.commit("p1", files = mapOf("README.md" to "the platform's own")))
        }
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("src/Main.kt" to "a")))
        }

        val result = MergeCommand().test(
            listOf(
                "-o", path("out.git"),
                path("platform.git") + "::=libs",
                path("backend.git") + "::=libs/backend",
            ),
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("one contains the other"), result.output)
        assertTrue(result.output.contains("--splice"), result.output)
    }

    @Test
    fun `--splice places one input inside another, both repositories' content in one directory`() {
        // The same shape --root-repo has always allowed, one level down: platform holds libs/ and
        // backend is placed inside it. Without --splice the planner refuses the pair outright.
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch(
                "main",
                r.commit(
                    "p1",
                    files = mapOf("README.md" to "the platform's own", "docs/guide.md" to "g"),
                    at = at("09:00"),
                ),
            )
        }
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("src/Main.kt" to "a"), at = at("10:00")))
        }

        val out = tmp.resolve("spliced.git")
        val result = braid(
            "-o", out.toString(),
            "--splice",
            path("platform.git") + "::=libs",
            path("backend.git") + "::=libs/backend",
        )

        // The run says which splice it made and that it checked out clean, because a flag that
        // silently changes the layout is the thing --splice exists to avoid.
        assertTrue(result.output.contains("spliced: libs/backend inside libs"), result.output)
        assertTrue(result.output.contains("no collision"), result.output)

        SourceRepository.open(out).use { repo ->
            val main = repo.branches().single { it.name == "main" }.target
            val tip = repo.readReachable(listOf(main)).single { it.id == main }
            fun names(path: String): List<String> {
                var tree = tip.tree
                for (segment in path.split('/').filter { it.isNotEmpty() }) {
                    tree = repo.entriesOf(tree).single { it.name == segment }.id
                }
                return repo.entriesOf(tree).map { it.name }
            }

            assertEquals(listOf("libs"), names(""))
            // The containing repository's own entries, with the input placed in beside them.
            assertEquals(listOf("README.md", "backend", "docs"), names("libs"))
            assertEquals(listOf("src"), names("libs/backend"))
        }

        if (GitCli.available) {
            GitCli.fsck(out)
            assertEquals("a", GitCli.run(out, "show", "main:libs/backend/src/Main.kt"))
            assertEquals("the platform's own", GitCli.run(out, "show", "main:libs/README.md"))
        }
        OutputRepo.assertEveryOriginalEdgePreserved(out)
    }

    @Test
    fun `--splice still refuses an input landing on something the containing repository holds`() {
        // The flag says the nesting is intended, not that anything goes: what the containing
        // repository already has at the inner destination is a collision either way. And because
        // that is a property of a tree rather than of the destinations, it is checked against every
        // tree the plan uses before a single object is written -- on a dry run as much as on a real
        // one, so a dry run never reports a plan the writer would then choke on.
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch("main", r.commit("p1", files = mapOf("backend" to "a stray file")))
        }
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("src/Main.kt" to "a")))
        }
        val inputs = listOf(
            "--splice",
            path("platform.git") + "::=libs",
            path("backend.git") + "::=libs/backend",
        )

        val dry = MergeCommand().test(listOf("--dry-run") + inputs)
        assertEquals(1, dry.statusCode, dry.output)
        assertTrue(dry.output.contains("'libs/backend'"), dry.output)
        assertTrue(dry.output.contains("collides"), dry.output)

        val out = tmp.resolve("out.git")
        val result = MergeCommand().test(listOf("-o", out.toString()) + inputs)
        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'libs/backend'"), result.output)
        assertFalse(out.exists(), "the output was created before the collision was found")
    }

    @Test
    fun `a splice inside a splice reaches the tree the middle repository actually contributes`() {
        // Three levels: platform at the root, libs inside it, backend inside libs. What the output
        // holds at libs/ is the middle repository's tree, so that is the one backend can collide
        // with -- platform's own libs/ was already refused a level up, and never reaches here.
        TestRepoBuilder.create(tmp.resolve("platform.git")).use { r ->
            r.branch("main", r.commit("p1", files = mapOf("README.md" to "p"), at = at("09:00")))
        }
        TestRepoBuilder.create(tmp.resolve("libs.git")).use { r ->
            r.branch("main", r.commit("l1", files = mapOf("shared.txt" to "l"), at = at("10:00")))
        }
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", files = mapOf("src/Main.kt" to "a"), at = at("11:00")))
        }

        val out = tmp.resolve("deep.git")
        braid(
            "-o", out.toString(),
            "--splice",
            "--root-repo", "platform",
            path("platform.git"),
            path("libs.git") + "::=libs",
            path("backend.git") + "::=libs/backend",
        )

        SourceRepository.open(out).use { repo ->
            val main = repo.branches().single { it.name == "main" }.target
            val tip = repo.readReachable(listOf(main)).single { it.id == main }
            fun names(path: String): List<String> {
                var tree = tip.tree
                for (segment in path.split('/').filter { it.isNotEmpty() }) {
                    tree = repo.entriesOf(tree).single { it.name == segment }.id
                }
                return repo.entriesOf(tree).map { it.name }
            }

            assertEquals(listOf("README.md", "libs"), names(""))
            assertEquals(listOf("backend", "shared.txt"), names("libs"))
            assertEquals(listOf("src"), names("libs/backend"))
        }

        if (GitCli.available) GitCli.fsck(out)
        OutputRepo.assertEveryOriginalEdgePreserved(out)
    }

    @Test
    fun `a-txt sorts before a-slash and CRLF content is left untouched`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.commitBytes(
                "root layout",
                files = mapOf(
                    "a.txt" to "flat\n".toByteArray(),
                    "a/nested" to "deep\n".toByteArray(),
                    "crlf.txt" to "line one\r\nline two\r\n".toByteArray(StandardCharsets.UTF_8),
                ),
            ).let { r.branch("main", it) }
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r -> r.branch("main", r.commit("b1")) }

        val out = tmp.resolve("layout.git")
        braid("-o", out.toString(), "--root-repo", "backend", path("backend.git"), path("webui.git"))

        // git fsck is the real judge of tree-entry ordering: a.txt (0x2E) must precede a/ (0x2F).
        if (GitCli.available) {
            GitCli.fsck(out)
            assertEquals("line one\r\nline two", GitCli.run(out, "show", "main:crlf.txt"))
        }
    }

    @Test
    fun `a submodule comes out wired, so git submodule resolves it in the output`() {
        // The gitlink's target need not exist anywhere here: a superproject records a sha it fetches
        // from the submodule's own url, and that is what the output has to keep pointing at.
        val vendored = ObjectId.fromString("06df2481b3f0ad0e5d6d0f04ac4b5f0e0eaa1234")
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            val a2 = r.commit(
                "a2, vendor the library",
                parents = listOf(a1),
                files = mapOf(
                    "a.txt" to "a\n",
                    ".gitmodules" to "[submodule \"vendor/lib\"]\n\tpath = vendor/lib\n" +
                        "\turl = https://example.com/lib.git\n",
                ),
                gitlinks = mapOf("vendor/lib" to vendored),
                at = at("11:00"),
            )
            r.branch("main", a2)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", at = at("10:00")))
        }

        val out = tmp.resolve("subs")
        braid("-o", out.toString(), "--no-bare", path("backend.git"), path("webui.git"))

        SourceRepository.open(out).use { repo ->
            val tip = repo.resolveBranch("main")!!
            val tree = repo.readReachable(listOf(tip)).first { it.id == tip }.tree

            // The wiring git actually reads: one file at the root, its path pointing at where the
            // gitlink landed rather than at where it used to be.
            assertEquals(
                "[submodule \"backend/vendor/lib\"]\n\tpath = backend/vendor/lib\n" +
                    "\turl = https://example.com/lib.git\n",
                repo.gitmodules(tree),
            )
        }

        if (GitCli.available) {
            GitCli.fsck(out)
            // The gitlink itself, unchanged, at its new path.
            assertEquals(
                "160000 commit ${vendored.name}\tbackend/vendor/lib",
                GitCli.run(out, "ls-tree", "main", "backend/vendor/lib"),
            )
            // The command that used to fail outright with "no submodule mapping found". It reports
            // the submodule as not checked out (the leading `-`), which is what an uninitialised
            // submodule looks like — the mapping resolves.
            assertEquals(
                "-${vendored.name} backend/vendor/lib",
                GitCli.run(out, "submodule", "status"),
            )
        }
    }

    @Test
    fun `a non-ASCII file name and commit message survive the rewrite`() {
        val subject = "Přidání funkce 🚀"
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("$subject\n\ntělo zprávy\n", files = mapOf("café.txt" to "obsah\n")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r -> r.branch("main", r.commit("b1")) }

        val out = tmp.resolve("utf8.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))

        val written = OutputRepo.read(out)
        assertTrue(written.commits.any { it.message.contains(subject) }, "the subject was mangled")
        SourceRepository.open(out).use { repo ->
            val tip = repo.readReachable(listOf(repo.resolveBranch("main")!!)).first { it.message.contains(subject) }
            val backend = repo.entriesOf(tip.tree).single { it.name == "backend" }
            assertTrue(repo.entriesOf(backend.id).any { it.name == "café.txt" })
        }
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `a missing mainline branch fails with one line, not a stack trace`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r -> r.branch("main", r.commit("a1")) }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r -> r.branch("trunk", r.commit("b1")) }

        val result = MergeCommand().test(
            listOf("-o", path("out.git"), "--mainline-branch", "main", path("backend.git"), path("webui.git")),
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("main"), result.output)
        assertTrue(result.output.contains("webui"), result.output)
        assertTrue(result.output.lines().none { it.contains("Exception") }, result.output)
    }

    @Test
    fun `an input with no common branch is rejected before anything is written`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r -> r.branch("main", r.commit("a1")) }
        // webui has commits but no branch at all — an "empty" repo as far as refs go.
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r -> r.commit("b1") }
        val out = tmp.resolve("out.git")

        val result = MergeCommand().test(listOf("-o", out.toString(), path("backend.git"), path("webui.git")))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("--mainline-branch"), result.output)
        assertTrue(!out.toFile().exists(), "nothing should have been written")
    }

    @Test
    fun `a directory inside a repository is refused, not taken for the repository itself`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r -> r.branch("main", r.commit("a1")) }
        val webui = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        webui.branch("main", webui.commit("b1"))
        webui.close()
        val inside = tmp.resolve("webui/sub").createDirectories()
        val out = tmp.resolve("out.git")

        // The name-collision check cannot catch this on its own: "sub" and "webui" are different
        // names, so an enclosing repository would come in a second time as a strand of its own and
        // be braided against itself.
        val result = MergeCommand().test(listOf("-o", out.toString(), path("backend.git"), inside.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("no git repository"), result.output)
        assertTrue(!out.toFile().exists(), "nothing should have been written")
    }

    @Test
    fun `an input written as its dot-git directory lands under the working tree's name`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            r.branch("main", r.commit("a1", at = at("09:00")))
        }
        val webui = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        val b1 = webui.commit("b1", at = at("10:00"))
        webui.branch("main", b1)
        webui.close()
        val out = tmp.resolve("out.git")

        braid("-o", out.toString(), path("backend.git"), tmp.resolve("webui/.git").toString())

        val tip = OutputRepo.read(out).byOriginalSha.getValue(b1.name)
        SourceRepository.open(out).use { repo ->
            val top = repo.entriesOf(tip.tree).map { it.name }
            assertTrue(top.contains("webui"), top.toString())
            assertTrue(top.none { it.isEmpty() || it == ".git" }, top.toString())
        }
        if (GitCli.available) GitCli.fsck(out)
    }

    @Test
    fun `a small fixture keeps a stable git log --graph shape`() {
        // Three commits, no side branches, so --all is just the braided mainline.
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { r ->
            val a1 = r.commit("a1", at = at("09:00"))
            r.branch("main", r.commit("a2", parents = listOf(a1), at = at("11:00")))
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { r ->
            r.branch("main", r.commit("b1", at = at("10:00")))
        }
        val out = tmp.resolve("merged.git")
        braid("-o", out.toString(), path("backend.git"), path("webui.git"))
        GitCli.requireGit()

        val graph = GitCli.graphLog(out)
            .lines()
            .joinToString("\n") { it.replace(Regex("[0-9a-f]{7,}"), "<sha>").trimEnd() }

        // a2 (backend, 11:00) has two parents: b1 by the braid, a1 from home.
        assertEquals(
            """
            *   <sha> backend: a2
            |\
            * | <sha> webui: b1
            |/
            * <sha> backend: a1
            """.trimIndent(),
            graph,
        )
    }
}
