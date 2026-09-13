package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.eclipse.jgit.internal.storage.file.ObjectDirectory
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The outer CLI options: cloning a remote input, --no-bare, --keep-remotes, the prefixes, -q/-v. */
class MergeCommandOptionsTest {

    @TempDir
    lateinit var tmp: Path

    private fun corpus() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = Instant.parse("2021-01-01T09:00:00Z"))
            val a2 = repo.commit("a2", parents = listOf(a1), at = Instant.parse("2021-01-01T11:00:00Z"))
            repo.branch("main", a2)
            repo.lightweightTag("v1", a2)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            repo.branch("main", repo.commit("b1", at = Instant.parse("2021-01-01T10:00:00Z")))
        }
    }

    private fun run(vararg args: String) =
        MergeCommand().test(args.toList()).also { assertEquals(0, it.statusCode, it.output) }

    @Test
    fun `--no-bare checks out a working tree`() {
        corpus()
        val out = tmp.resolve("merged")

        run(
            "-o", out.toString(), "--no-bare",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        assertTrue(out.resolve(".git").isDirectory(), "expected a non-bare repository")
        assertTrue(out.resolve("backend/f").exists(), "backend subtree not checked out")
        assertTrue(out.resolve("webui/f").exists(), "webui subtree not checked out")
    }

    @Test
    fun `--keep-remotes points its refs at the inputs' own commits`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(), "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repo ->
            assertTrue(repo.remoteNames.containsAll(setOf("backend", "webui")), repo.remoteNames.toString())
            for (name in listOf("backend", "webui")) {
                val original = SourceRepository.open(tmp.resolve("$name.git"), name)
                    .use { it.resolveBranch("main") }
                assertEquals(
                    original,
                    repo.resolve("refs/remotes/$name/main"),
                    "refs/remotes/$name/main should be $name's own commit, sha and all",
                )
            }
        }
    }

    @Test
    fun `every input object arrives, and nothing is written but the braid's own`() {
        corpus()
        // A side branch: the only input objects the mainline does not reach, so a transfer narrowed
        // to the mainline's ref would leave its commit, its tree and its blob behind.
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.repository.resolve("refs/heads/main~1")
            repo.branch("side", repo.commit("s1", parents = listOf(a1), at = Instant.parse("2021-01-01T12:00:00Z")))
        }
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(), "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        // The inputs arrive by one fetch each and the braid is written on top, so the output holds
        // the union of the inputs' objects plus what the braid invented. What the count pins is
        // that nothing extra was written and nothing went missing — a byte-identical second copy
        // has the same id and collapses into the set, so it is not this assertion that would catch
        // one.
        val inputObjects = listOf("backend.git", "webui.git")
            .flatMap { objectIdsOf(tmp.resolve(it)) }
            .toSet()
        // Four commits over four root trees, the side branch's rewritten onto its rewritten parent
        // as the braid's three are; every blob and subtree came from an input.
        val invented = 4 + 4

        assertEquals(
            inputObjects.size + invented,
            objectIdsOf(out).size,
            "the output should hold every input object, the braid's own, and nothing else",
        )
        assertTrue(
            objectIdsOf(out).containsAll(inputObjects),
            "an input object went missing, so the transfer was not complete",
        )
    }

    @Test
    fun `a rerun into its own output keeps its remotes, and a remote under another URL is refused`() {
        corpus()
        val out = tmp.resolve("merged.git")
        val inputs = arrayOf(tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString())
        run("-o", out.toString(), "--keep-remotes", *inputs)
        fun remotes() = FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repository ->
            repository.config.getSubsections("remote").associateWith {
                repository.config.getString("remote", it, "url")
            }
        }
        fun refs() = FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repository ->
            repository.refDatabase.refs.associate { it.name to it.objectId }
        }
        val recorded = remotes()

        // The same run again, into what it wrote: the remotes are its own, and stay as they were.
        run("-o", out.toString(), "--keep-remotes", "--force", *inputs)
        assertEquals(recorded, remotes())

        // Another repository under the name of one: refused before the output is written, where 0.1.0
        // failed on `git remote add` with the braid already written, and on a dry run too, which 0.1.0
        // passed.
        TestRepoBuilder.create(tmp.resolve("elsewhere/backend.git")).use { repo ->
            repo.branch("main", repo.commit("c1", at = Instant.parse("2021-01-01T08:00:00Z")))
        }
        val written = refs()
        for (dryRun in listOf(false, true)) {
            val result = MergeCommand().test(
                listOfNotNull(
                    "-o", out.toString(), "--keep-remotes", "--force", "--dry-run".takeIf { dryRun },
                    tmp.resolve("elsewhere/backend.git").toString(), tmp.resolve("webui.git").toString(),
                )
            )
            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains("already has a remote 'backend'"), result.output)
        }
        assertEquals(written, refs())
        assertEquals(recorded, remotes())
    }

    /**
     * Every object id the bare repository at [dir] holds, packed and loose alike.
     *
     * Both halves are needed and neither is optional: a fixture built object by object is loose,
     * where a fetch and the braid's own inserter both write packs. Counting the union is what makes
     * the count one of objects rather than of pack files: an object gone missing, or one nobody
     * asked for, shows whether it is loose or packed.
     */
    private fun objectIdsOf(dir: Path): Set<String> {
        val objects = dir.resolve("objects")
        val loose = objects.listDirectoryEntries()
            .filter { it.isDirectory() && it.name.length == 2 }
            .flatMap { fanout -> fanout.listDirectoryEntries().map { fanout.name + it.name } }

        FileRepositoryBuilder().setGitDir(dir.toFile()).build().use { repo ->
            val packed = (repo.objectDatabase as ObjectDirectory).packs
                .flatMap { pack -> pack.map { it.toObjectId().name } }
            return (loose + packed).toSet()
        }
    }

    @Test
    fun `--keep-remotes resolves a relative input into an absolute remote URL`() {
        corpus()
        val out = tmp.resolve("merged.git")
        val cwd = Path.of("").toAbsolutePath()
        // A relative path between the two only exists when they share a filesystem root, which on
        // Windows they need not.
        assumeTrue(cwd.root == tmp.root, "temp directory is on another root than the working directory")
        val backend = cwd.relativize(tmp.resolve("backend.git")).toString()
        val webui = cwd.relativize(tmp.resolve("webui.git")).toString()
        assumeTrue(!Path.of(backend).isAbsolute, "relativize did not produce a relative path")

        run("-o", out.toString(), "--keep-remotes", backend, webui)

        // git stores a remote's URL verbatim and resolves it against the repository holding it, so
        // the relative path the caller typed has to be made absolute on the way in. The run
        // fetches the inputs from their paths, not through the remotes it records, so nothing fails
        // at merge time — what a relative path would break is the `git fetch <name>` the remote
        // exists for, later, from a directory that is not the one the caller typed it in.
        FileRepositoryBuilder().setGitDir(out.toFile()).build().use { repo ->
            for (name in listOf("backend", "webui")) {
                val url = repo.config.getString("remote", name, "url")
                assertTrue(Path.of(url).isAbsolute, "remote '$name' recorded a relative url: $url")
                assertTrue(
                    repo.refDatabase.getRefsByPrefix("refs/remotes/$name/").isNotEmpty(),
                    "$name refs were not written",
                )
            }
        }
    }

    @Test
    fun `a renamed input carries the new name into its tags and provenance`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            tmp.resolve("backend.git").toString() + "::legacy",
            tmp.resolve("webui.git").toString(),
        )

        SourceRepository.open(out).use { repo ->
            // The default tag prefix is "{repo}/", so the tag shows the name and not just where the
            // content was placed; the same name has to reach the provenance trailer.
            assertEquals(listOf("legacy/v1"), repo.tags().map { it.name })
            val messages = repo.readReachable(repo.branches().map { it.target }).map { it.message }
            assertTrue(messages.any { it.contains("repo=\"legacy\"") }, messages.toString())
            assertTrue(messages.none { it.contains("backend") }, messages.toString())
        }
    }

    @Test
    fun `--tag-prefix and --no-provenance shape the output`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            "--tag-prefix", "legacy-{repo}-",
            "--no-provenance",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )

        SourceRepository.open(out).use { repo ->
            assertEquals(listOf("legacy-backend-v1"), repo.tags().map { it.name })
            val messages = repo.readReachable(repo.branches().map { it.target }).map { it.message }
            assertTrue(messages.none { it.contains("[timebraid:") }, "provenance trailer was still written")
        }
    }

    @Test
    fun `a --tag-prefix that makes a component git refuses is refused`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "-o", tmp.resolve("merged.git").toString(),
                "--tag-prefix", "{repo}.lock/",
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'refs/tags/backend.lock/v1' is not a valid ref name"), result.output)
    }

    @Test
    fun `-b is shorthand for a --ref pattern, and narrowing leaves the tags out`() {
        corpus()
        // A side branch too, so that -b is seen to name a branch: the mainline is written from the
        // braid whatever the selection says, so -b main alone could not tell -b from nothing.
        val a2 = SourceRepository.open(tmp.resolve("backend.git")).use { it.resolveBranch("main")!! }
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { repo ->
            val f1 = repo.commit("f1", parents = listOf(a2), at = Instant.parse("2021-01-01T12:00:00Z"))
            repo.branch("feature", f1)
        }

        // Branches and tags of an output built with the given selection, as the CLI sees them.
        fun refsOf(name: String, vararg select: String): Pair<List<String>, List<String>> {
            val out = tmp.resolve(name)
            run(
                "-o", out.toString(), *select,
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
            return SourceRepository.open(out).use { repo ->
                repo.branches().map { it.name }.sorted() to repo.tags().map { it.name }.sorted()
            }
        }

        // Nothing named: every branch and every tag, which is what a plain run has always done.
        assertEquals(listOf("backend/feature", "main") to listOf("backend/v1"), refsOf("all.git"))

        // -b main is refs/heads/main and nothing else, so backend's tag is not carried over. This
        // is the point of the option and what it could not do while tags were loaded regardless.
        assertEquals(listOf("main") to emptyList<String>(), refsOf("narrow.git", "-b", "main"))
        assertEquals(
            listOf("backend/feature", "main") to emptyList<String>(),
            refsOf("side.git", "-b", "feature"),
        )

        // The old behaviour, for a run that wants it: say so, rather than have it implied.
        assertEquals(
            listOf("main") to listOf("backend/v1"),
            refsOf("both.git", "--ref", "refs/heads/main", "--ref", "refs/tags/*"),
        )
    }

    @Test
    fun `one -b argument carries several branch names`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = Instant.parse("2021-01-01T09:00:00Z"))
            repo.branch("main", a1)
            repo.branch("feature", repo.commit("a2", parents = listOf(a1), at = Instant.parse("2021-01-01T11:00:00Z")))
            repo.branch("wip", repo.commit("a3", parents = listOf(a1), at = Instant.parse("2021-01-01T12:00:00Z")))
        }
        val out = tmp.resolve("two-of-three.git")

        // One quoted argument may hold several patterns on every option taking one, and -b is
        // shorthand for one of them. The third branch is what shows the run still narrows.
        run("-o", out.toString(), "-b", "main feature", tmp.resolve("backend.git").toString())

        // `feature` arrives under the branch prefix and the mainline does not, which is the rule
        // everywhere else; what this pins is that both names were read out of the one argument.
        assertEquals(
            listOf("backend/feature", "main"),
            SourceRepository.open(out).use { repo -> repo.branches().map { it.name }.sorted() },
        )
    }

    @Test
    fun `a branch and a tag of one input meeting on one mirror name are refused`() {
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = Instant.parse("2021-01-01T09:00:00Z"))
            repo.branch("main", a1)
            // A branch literally named `tags/v1.0` mirrors to the name the tag `v1.0` mirrors to,
            // which is the meeting BraidWriter.mirrorInputs documents and refuses.
            repo.branch("tags/v1.0", a1)
            repo.lightweightTag("v1.0", a1)
        }
        val out = tmp.resolve("collide.git")

        val result = MergeCommand().test(
            listOf("-o", out.toString(), "--keep-remotes", tmp.resolve("backend.git").toString())
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(
            result.output.contains(
                "the branch 'tags/v1.0' and the tag 'v1.0' of 'backend' would both be mirrored as " +
                    "'refs/remotes/backend/tags/v1.0'"
            ),
            result.output,
        )
    }

    @Test
    fun `a file URL input is cloned next to the output`() {
        corpus()
        val out = tmp.resolve("merged.git")

        run(
            "-o", out.toString(),
            // `toUri` rather than `"file://" + path`: the two agree only where a path already
            // begins with a separator, and a Windows one begins with a drive letter instead.
            tmp.resolve("backend.git").toUri().toString(),
            tmp.resolve("webui.git").toString(),
        )

        val clone = tmp.resolve(".timebraid-clones/backend.git")
        assertTrue(RepositoryCache.FileKey.isGitRepository(clone.toFile(), FS.DETECTED), "input was not cloned")
        SourceRepository.open(out).use { repo ->
            assertTrue(repo.branches().any { it.name == "main" })
        }
    }

    @Test
    fun `a clone another URL made is refused rather than refreshed`() {
        corpus()
        // A second repository whose location derives the same name, `backend`.
        TestRepoBuilder.create(tmp.resolve("fork/backend.git")).use { repo ->
            repo.branch("main", repo.commit("f1", at = Instant.parse("2021-01-01T08:00:00Z")))
        }
        val url = tmp.resolve("backend.git").toUri().toString()
        val fork = tmp.resolve("fork/backend.git").toUri().toString()
        val webui = tmp.resolve("webui.git").toString()
        run("-o", tmp.resolve("first.git").toString(), url, webui)

        val refused = MergeCommand().test(listOf("-o", tmp.resolve("second.git").toString(), fork, webui))

        assertEquals(1, refused.statusCode, refused.output)
        val clone = tmp.resolve(".timebraid-clones/backend.git")
        assertTrue(refused.output.contains("$clone is a clone of $url, not of $fork"), refused.output)
        // The same URL is still refreshed rather than refused.
        run("-o", tmp.resolve("third.git").toString(), url, webui)
    }

    @Test
    fun `a dry run removes its clones only where no -o gave them a place to stay`() {
        corpus()
        val url = tmp.resolve("backend.git").toUri().toString()
        val webui = tmp.resolve("webui.git").toString()

        // Without -o the clone goes to a temporary directory that no later run looks in.
        val dry = run("--dry-run", url, webui)
        val clone = Regex("""into (\S*timebraid-clones-\S+)""").find(dry.output)?.groupValues?.get(1)
        assertTrue(clone != null, dry.output)
        assertFalse(Path.of(clone!!).parent.exists(), "the directory $clone was cloned into is still there")

        // A run refused after the clone was made removes it too.
        TestRepoBuilder.create(tmp.resolve("topic.git"), initialBranch = "topic").use { repo ->
            repo.branch("topic", repo.commit("t1", at = Instant.parse("2021-01-01T08:00:00Z")))
        }
        val refused = MergeCommand().test(listOf("--dry-run", tmp.resolve("topic.git").toUri().toString(), webui))
        assertTrue(refused.statusCode != 0, refused.output)
        val refusedClone = Regex("""into (\S*timebraid-clones-\S+)""").find(refused.output)?.groupValues?.get(1)
        assertTrue(refusedClone != null, refused.output)
        assertFalse(Path.of(refusedClone!!).parent.exists(), "a refused run left $refusedClone behind")

        // With -o it sits beside the output, where the real run will refresh it rather than clone.
        run("--dry-run", "-o", tmp.resolve("merged.git").toString(), url, webui)
        val kept = tmp.resolve(".timebraid-clones/backend.git")
        assertTrue(RepositoryCache.FileKey.isGitRepository(kept.toFile(), FS.DETECTED), "the clone beside -o is gone")
    }

    @Test
    fun `--quiet silences progress, --verbose shows the git commands`() {
        corpus()

        val quiet = run(
            "-o", tmp.resolve("q.git").toString(), "--quiet", "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )
        assertFalse(quiet.output.contains("-- writing the braid"), quiet.output)
        // Nor a blank line ahead of the closing report: no phase was printed to set it off from.
        assertTrue(quiet.stderr.startsWith("mainline branch:"), quiet.stderr)

        val verbose = run(
            "-o", tmp.resolve("v.git").toString(), "--verbose", "--keep-remotes",
            tmp.resolve("backend.git").toString(),
            tmp.resolve("webui.git").toString(),
        )
        assertTrue(verbose.output.contains("-- writing the braid"), verbose.output)
        assertTrue(verbose.output.contains("remote add"), verbose.output)
        // Creating the output is the fetching phase's first step, and is printed under its heading.
        val created = verbose.output.indexOf("symbolic-ref")
        val fetching = verbose.output.indexOf("-- fetching the inputs into the output")
        assertTrue(fetching in 0 until created, verbose.output)
    }

    @Test
    fun `the pairs that say opposite things about progress refuse to be combined`() {
        corpus()
        val backend = tmp.resolve("backend.git").toString()
        for (pair in listOf(
            listOf("--progress", "--no-progress"),
            listOf("--quiet", "--progress"),
            listOf("--ascii", "--no-ascii"),
        )) {
            val refused = MergeCommand().test(
                listOf("-o", tmp.resolve("x-${pair.joinToString("")}.git").toString()) + pair + backend
            )
            assertEquals(1, refused.statusCode, refused.output)
            assertTrue(refused.output.contains("cannot be combined"), refused.output)
        }
    }

    @Test
    fun `--verbose reports the in-process work too, where nothing runs through GitCommand`() {
        corpus()

        // Local inputs, bare output, no --keep-remotes: the program starts none of its git commands
        // here, so this run is the one where a flag that only echoed them would have nothing
        // whatever to say.
        val out = tmp.resolve("v2.git")
        // backend is typed as a relative path, where one exists: the two share a filesystem root,
        // which on Windows they need not. Only a source typed relative can show it was made
        // absolute on the way to the logged line.
        val cwd = Path.of("").toAbsolutePath()
        val backendDir = tmp.resolve("backend.git")
        val typed = if (cwd.root == tmp.root) cwd.relativize(backendDir) else backendDir
        val verbose = run(
            "-o", out.toString(), "--verbose",
            typed.toString(),
            tmp.resolve("webui.git").toString(),
        )

        // One per input, which is what makes this a transfer rather than an object-by-object copy.
        val transfers = verbose.output.lines().filter { it.contains("fetch --no-tags") }
        assertEquals(2, transfers.size, verbose.output)
        // The source has to be absolute for the line to run: `-C` has already moved the working
        // directory by the time git reads it. Matched as a word of the line, not as a substring: a
        // relative spelling that climbs to the root and back down ends in the absolute one.
        val backend = backendDir.toAbsolutePath().toString()
        val transfer = transfers.single { backend in it.split(' ') }
        assertTrue(transfer.contains("+refs/heads/main:"), transfer)

        assertTrue(verbose.output.contains("update-ref refs/heads/main "), verbose.output)
        assertTrue(verbose.output.contains("update-ref -d refs/timebraid-fetch/"), verbose.output)
        assertTrue(verbose.output.contains("symbolic-ref HEAD refs/heads/main"), verbose.output)
    }
}
