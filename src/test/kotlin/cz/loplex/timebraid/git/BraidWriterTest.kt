package cz.loplex.timebraid.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
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
        mainlineBranch: List<String> = emptyList(),
    ): WriteSummary {
        val names = listOf("backend", "webui")
        val opened = names.map { SourceRepository.open(tmp.resolve("$it.git")) }
        try {
            val inputs = CommitGraphReader.read(opened, OrderBy.COMMITTER, mainlines(opened, mainlineBranch))
            val repoOf = inputs.sources.map { it.source }.zip(opened).toMap()
            val plan = inputs.graph.braid(inputs.heads).plan(
                inputs.graph.sources.associateWith { if (it.name == rootRepo) null else subdirs[it.name] ?: it.name }
            )
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
            fun entries(name: String) = repo.entriesOf(new(written, name).tree).associate {
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
                val entry = repo.entriesOf(new(written, "a3").tree).single { it.name == "webui" }
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
                listOf("backend/feature", "main", "webui/esbuild-experiment"),
                repo.branches().map { it.name },
            )
            assertEquals(new(written, "a3").id, repo.resolveBranch("main"))
            assertEquals(new(written, "f1").id, repo.resolveBranch("backend/feature"))
            assertEquals(new(written, "b1").id, repo.resolveBranch("webui/esbuild-experiment"))

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
    fun `a recreated tag carries no signature, PGP or SSH`() {
        // A signature covers the input's tag object, which names the input's commit and not the one
        // the output's tag points at, so nothing could verify it there. The reader cuts it off where
        // git does. The output is read raw, because reading it back through JGit would leave a
        // signature out just the same.
        corpus()
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { repo ->
            val b1 = original.getValue("b1")
            repo.annotatedTag("pgp", b1, "pgp\n-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n")
            repo.annotatedTag("ssh", b1, "ssh\n-----BEGIN SSH SIGNATURE-----\nabc\n-----END SSH SIGNATURE-----\n")
        }
        val out = tmp.resolve("merged.git")
        braid(out)

        Git.open(out.toFile()).use { git ->
            RevWalk(git.repository).use { walk ->
                for (name in listOf("pgp", "ssh")) {
                    val ref = git.repository.exactRef("refs/tags/webui/$name") ?: error("webui/$name was not written")
                    val raw = String(walk.parseTag(ref.objectId).rawBuffer, Charsets.UTF_8)
                    assertTrue(raw.endsWith("\n\n$name\n"), raw)
                }
            }
        }
    }

    @Test
    fun `a recreated tag's message is cut where git reads its signature as beginning`() {
        // git takes a signature to start at the last line that begins one of the headers it knows,
        // whatever follows that block: JGit reads two of these shapes otherwise.
        corpus()
        val own = "notes\n-----BEGIN PGP SIGNATURE-----\nquoted\n-----END PGP SIGNATURE-----\nmore\n"
        val tags = mapOf(
            // A block quoted ahead of the tag's own signature is message, and stays.
            "quoted" to (own + "-----BEGIN PGP SIGNATURE-----\nreal\n-----END PGP SIGNATURE-----\n" to own),
            // Followed by a block git does not know, the quoted one is where the signature begins.
            "noted" to (
                "notes\n-----BEGIN PGP SIGNATURE-----\nquoted\n-----END PGP SIGNATURE-----\n" +
                    "-----BEGIN NOTE-----\nkept\n-----END NOTE-----\n" to "notes\n"
                ),
            // A PGP MESSAGE armour is a signature to git, and to JGit only message.
            "armour" to ("armour\n-----BEGIN PGP MESSAGE-----\nabc\n-----END PGP MESSAGE-----\n" to "armour\n"),
        )
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { repo ->
            for ((name, written) in tags) repo.annotatedTag(name, original.getValue("b1"), written.first)
        }
        val out = tmp.resolve("merged.git")
        braid(out)

        Git.open(out.toFile()).use { git ->
            RevWalk(git.repository).use { walk ->
                for ((name, written) in tags) {
                    val ref = git.repository.exactRef("refs/tags/webui/$name") ?: error("webui/$name was not written")
                    val raw = String(walk.parseTag(ref.objectId).rawBuffer, Charsets.UTF_8)
                    assertTrue(raw.endsWith("\n\n${written.second}"), raw)
                }
            }
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
    fun `the provenance trailer lists a merge's original parents first parent first`() {
        // The order is what tells a merge's own line from the branch it brought in, as
        // `git log --first-parent` in the input reads it: a3 merged f1 into a2.
        corpus()
        val out = tmp.resolve("merged.git")
        braid(out)

        val a3 = new(read(out), "a3")
        assertTrue(
            a3.message.endsWith(
                " parents=${original.getValue("a2").name},${original.getValue("f1").name}]\n"
            ),
            a3.message,
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
                repo.entriesOf(new(written, "a3").tree).map { it.name }.toSet(),
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
    fun `every branch is qualified with the repository name, shared or not`() {
        corpus()
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("release", original.getValue("a2")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("release", original.getValue("b1")) }

        val out = tmp.resolve("qualified.git")
        braid(out)

        SourceRepository.open(out).use { repo ->
            val names = repo.branches().map { it.name }
            assertTrue(names.containsAll(listOf("backend/release", "webui/release")), names.toString())
            assertEquals(null, repo.resolveBranch("release"))
            // `feature` is backend's alone and is qualified all the same: what a branch is called in
            // the output follows from the input it came from, and from nothing else the run did.
            assertTrue(names.contains("backend/feature"), names.toString())
            assertEquals(null, repo.resolveBranch("feature"))
        }
    }

    @Test
    fun `two inputs' branches meeting under a prefix without {repo} are refused, not written over`() {
        corpus()
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("release", original.getValue("a2")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { repo ->
            repo.branch("release", original.getValue("b1"))
            repo.branch("backend/release", original.getValue("b2"))
        }

        // Apart: every branch carries its own input's name, so webui's branch named like backend's
        // qualified one no longer meets it.
        val apart = tmp.resolve("apart.git")
        braid(apart)
        SourceRepository.open(apart).use { repo ->
            val names = repo.branches().map { it.name }
            assertTrue(names.containsAll(listOf("backend/release", "webui/release", "webui/backend/release")), "$names")
        }

        // Meeting: a prefix without {repo} puts both inputs' release on one name, and neither may
        // quietly win it.
        val error = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("collided.git"), options = WriteOptions(branchPrefix = "x/"))
        }
        val message = error.message!!
        assertTrue(message.contains("'backend'") && message.contains("'webui'"), message)
        assertTrue(message.contains("refs/heads/x/release"), message)
    }

    @Test
    fun `a branch qualified onto the braid's own name is refused, not written over it`() {
        corpus()
        // The mainline is `backend/x`, which an unscoped --mainline-branch asks of both inputs.
        // Under `{repo}/`, backend's `x` is qualified onto the braid's own branch, and the refusal
        // names that meeting.
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { repo ->
            repo.branch("backend/x", original.getValue("a3"))
            repo.branch("x", original.getValue("a1"))
        }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { repo ->
            repo.branch("backend/x", original.getValue("b2"))
            repo.branch("x", original.getValue("b1"))
        }

        val message = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("met.git"), mainlineBranch = listOf("backend/x"))
        }.message!!
        assertTrue(message.contains("'the braid' and 'backend' would both write 'refs/heads/backend/x'"), message)
    }

    @Test
    fun `the branch qualifier is a template, and every branch sees it`() {
        corpus()
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("release", original.getValue("a2")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("release", original.getValue("b1")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("alone", original.getValue("b1")) }

        val out = tmp.resolve("templated.git")
        braid(out, options = WriteOptions(branchPrefix = "from-{repo}--"))

        SourceRepository.open(out).use { repo ->
            val names = repo.branches().map { it.name }
            assertTrue(names.containsAll(listOf("from-backend--release", "from-webui--release")), names.toString())
            assertTrue(names.contains("from-webui--alone"), names.toString())
        }
    }

    @Test
    fun `an emptied branch prefix leaves the names plain`() {
        corpus()
        val out = tmp.resolve("plain.git")
        braid(out, options = WriteOptions(branchPrefix = "", tagPrefix = ""))

        SourceRepository.open(out).use { repo ->
            val names = repo.branches().map { it.name }
            assertTrue(names.containsAll(listOf("feature", "esbuild-experiment")), names.toString())
            assertEquals(
                listOf("v1.0", "v2.0"),
                repo.tags().map { it.name }.sorted(),
            )
        }
    }

    @Test
    fun `two inputs meeting on one name under an emptied prefix is refused`() {
        corpus()
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("release", original.getValue("a2")) }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("release", original.getValue("b1")) }

        val error = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("collided.git"), options = WriteOptions(branchPrefix = ""))
        }
        val message = error.message!!
        assertTrue(message.contains("'backend'") && message.contains("'webui'"), message)
        assertTrue(message.contains("refs/heads/release"), message)
        assertTrue(message.contains("--branch-prefix"), message)
    }

    @Test
    fun `a side branch may not take the name the braid carries`() {
        corpus()
        // webui braids along `main`, so a *backend* branch called `main` is not the mainline of its
        // own input — and with nothing qualifying it, it lands on the output's own branch.
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("main", original.getValue("a3")) }
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { it.branch("trunk", original.getValue("a3")) }

        val error = assertThrows<IllegalArgumentException> {
            braid(
                tmp.resolve("shadowed.git"),
                // `main` unscoped names the output's branch and webui's; backend braids along
                // `trunk`, which leaves its own `main` an ordinary side branch.
                mainlineBranch = listOf("main", "backend::trunk"),
                options = WriteOptions(branchPrefix = ""),
            )
        }
        assertTrue(error.message!!.contains("refs/heads/main"), error.message)
    }

    @Test
    fun `a branch the default prefix qualifies onto the braid's name is refused without that advice`() {
        corpus()
        // backend braids along `trunk`, so its `x` is an ordinary branch, and the default prefix
        // writes it as `backend/x`: the very name webui braids along and the output's branch takes.
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { repo ->
            repo.branch("trunk", original.getValue("a3"))
            repo.branch("x", original.getValue("a3"))
        }
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.branch("backend/x", original.getValue("b2")) }

        val error = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("spelled.git"), mainlineBranch = listOf("backend/x", "backend::trunk"))
        }
        val message = error.message!!
        assertTrue(message.contains("refs/heads/backend/x") && message.contains("braid"), message)
        // The default prefix is the one that met it, so recommending it would lead nowhere.
        assertTrue("as {repo}/ does" !in message, message)

        // Two inputs meeting under a prefix without {repo}, here on their ordinary `main`s, are
        // still pointed at one that has it.
        val inputs = assertThrows<IllegalArgumentException> {
            braid(
                tmp.resolve("met.git"),
                mainlineBranch = listOf("backend/x", "backend::trunk"),
                options = WriteOptions(branchPrefix = "p/"),
            )
        }.message!!
        assertTrue(inputs.contains("refs/heads/p/main") && inputs.contains("as {repo}/ does"), inputs)
    }

    @Test
    fun `an annotated tag is flattened on request, and nothing else changes`() {
        corpus()
        val kept = tmp.resolve("annotated.git")
        val flattened = tmp.resolve("lightweight.git")
        braid(kept)
        braid(flattened, options = WriteOptions(lightweightTags = true))

        SourceRepository.open(kept).use { repo ->
            val tag = repo.tags().single { it.name == "webui/v2.0" }
            assertEquals("the second release\n", tag.annotation?.message)
        }
        SourceRepository.open(flattened).use { repo ->
            // The same tags at the same commits; only the tag objects are gone.
            assertEquals(listOf("backend/v1.0", "webui/v2.0"), repo.tags().map { it.name }.sorted())
            assertTrue(repo.tags().all { it.annotation == null }, "an annotation survived")
        }
        SourceRepository.open(kept).use { before ->
            SourceRepository.open(flattened).use { after ->
                assertEquals(
                    before.tags().associate { it.name to it.target.name },
                    after.tags().associate { it.name to it.target.name },
                    "flattening a tag must not move it",
                )
            }
        }
    }

    @Test
    fun `the provenance trailer is a template`() {
        corpus()
        val out = tmp.resolve("trailer.git")
        braid(out, options = WriteOptions(provenanceTrailer = "origin {commit} of {repo}, from {parents}"))

        // Read without [read], which keys the output by the sha it finds in the default trailer:
        // a template that does not carry one is exactly what this is testing.
        SourceRepository.open(out).use { repo ->
            val messages = repo.readReachable(repo.branches().map { it.target }).map { it.message }
            assertTrue(
                messages.contains(
                    "backend: a2\n\nwith a body line\n\n" +
                        "origin ${original.getValue("a2").name} of backend," +
                        " from ${original.getValue("a1").name}\n"
                ),
                messages.toString(),
            )
        }
    }

    @Test
    fun `two inputs' tags meeting under a prefix without {repo} are refused, not written over`() {
        corpus()

        // Apart, the plain names are what an empty prefix asks for.
        val plain = tmp.resolve("plain.git")
        braid(plain, options = WriteOptions(tagPrefix = ""))
        SourceRepository.open(plain).use { repo ->
            assertEquals(listOf("v1.0", "v2.0"), repo.tags().map { it.name }.sorted())
        }

        // Meeting, neither input's v1.0 may quietly win the name.
        TestRepoBuilder.open(tmp.resolve("webui.git")).use { it.lightweightTag("v1.0", original.getValue("b1")) }
        val error = assertThrows<IllegalArgumentException> {
            braid(tmp.resolve("collided.git"), options = WriteOptions(tagPrefix = ""))
        }
        val message = error.message!!
        assertTrue(message.contains("'backend'") && message.contains("'webui'"), message)
        assertTrue(message.contains("refs/tags/v1.0"), message)
        assertTrue(message.contains("--tag-prefix"), message)
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
    fun `a new output turns line-ending conversion off in its own config`() {
        // The blobs are the inputs' bytes, so a checkout that converted them would disagree with
        // the inputs. Read without the user's and the system's config beneath it, which would
        // answer for a key the output's own file leaves out.
        for (bare in listOf(true, false)) {
            val out = tmp.resolve("out-$bare")
            TargetRepository.create(out, "main", bare = bare).close()
            val gitDir = if (bare) out else out.resolve(".git")
            val config = FileBasedConfig(null, gitDir.resolve("config").toFile(), FS.DETECTED).apply { load() }
            assertEquals("false", config.getString("core", null, "autocrlf"), "bare=$bare")
            assertEquals("lf", config.getString("core", null, "eol"), "bare=$bare")
        }
    }

    @Test
    fun `a ref name git refuses is not written, a lock suffix on any component included`() {
        // Each measured against `git check-ref-format`: JGit's own check lets the first one by.
        for (refused in listOf("refs/tags/a.lock/v1", "refs/tags/a/v1.lock", "refs/tags/a..b/v1")) {
            assertTrue(!TargetRepository.isRefName(refused), refused)
        }
        // Not a component ending in `.`, which git takes and JGit refuses on Windows.
        for (accepted in listOf("refs/tags/a.locked/v1", "refs/tags/a.LOCK/v1")) {
            assertTrue(TargetRepository.isRefName(accepted), accepted)
        }

        TargetRepository.create(tmp.resolve("merged.git"), "main").use { target ->
            val error = assertThrows<IllegalArgumentException> {
                target.point("refs/tags/a.lock/v1", ObjectId.zeroId())
            }
            assertTrue(error.message!!.contains("'refs/tags/a.lock/v1' is not a valid ref name"), error.message)
        }
    }

    @Test
    fun `an output location that is a file is refused, forced or not`() {
        val out = tmp.resolve("merged.git")
        out.toFile().writeText("not a repository")

        for (force in listOf(false, true)) {
            val error = assertThrows<IllegalArgumentException> {
                TargetRepository.create(out, "main", force = force).close()
            }
            assertTrue(error.message!!.contains("$out exists and is not a directory"), error.message)
        }
        assertEquals("not a repository", out.toFile().readText())
    }

    @Test
    fun `create reports an output it cannot create by the location given`() {
        // The command line asks refusal() first and so rarely lets a run get this far, but the
        // directory can change between the two checks, and a caller other than the command line
        // asks nothing first.
        val file = tmp.resolve("afile")
        file.toFile().writeText("a file, where a directory would have to be")
        val out = file.resolve("merged.git")

        val error = assertThrows<IllegalArgumentException> { TargetRepository.create(out, "main").close() }
        assertTrue(error.message!!.startsWith("cannot create the output at '$out': "), error.message)
    }

    @Test
    fun `an empty location is refused as no directory, not asked of the working directory's parent`() {
        // The command line refuses an empty -o first; another caller gets the same answer here.
        assertEquals(
            "an empty location names no directory for the output",
            TargetRepository.refusal(Path.of(""), force = false, bare = true),
        )
    }

    @Test
    fun `an empty directory is accepted as the output without --force`() {
        val out = tmp.resolve("merged.git")
        assertTrue(out.toFile().mkdir())

        TargetRepository.create(out, "main").close()

        assertTrue(out.resolve("HEAD").toFile().isFile, "no repository was created in the directory")
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
