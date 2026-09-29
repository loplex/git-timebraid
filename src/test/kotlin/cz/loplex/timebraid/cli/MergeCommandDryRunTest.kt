package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import cz.loplex.timebraid.GitCli
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TestRepoBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/**
 * The CLI paths up to the write: `--dry-run`, `--plan-out`, the option and input checks, and what a
 * plain `-o` run reports.
 */
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
    fun `--verbose counts the commits --interleave-ref opts in, each once`() {
        corpus()
        // Four refs on two commits: backend's main, side and v1 on a2, and webui's main on b1.
        TestRepoBuilder.open(tmp.resolve("backend.git")).use { repo ->
            val a2 = SourceRepository.open(tmp.resolve("backend.git")).use { it.resolveBranch("main")!! }
            repo.branch("side", a2)
            repo.lightweightTag("v1", a2)
        }

        val result = MergeCommand().test(
            listOf(
                "--dry-run", "--verbose", "--interleave-ref", "refs/heads/*", "--interleave-ref", "refs/tags/*",
                tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("2 commits opted into the interleave"), result.output)
    }

    @Test
    fun `a --mainline-branch naming a revision rather than a branch is refused`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run", "--mainline-branch", "main~1",
                tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("branch 'main~1' is missing in: backend, webui"), result.output)
    }

    @Test
    fun `--mainline-branch given twice for the same inputs is refused, scoped or not`() {
        corpus()
        val inputs = listOf(tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString())

        // 0.1.0 took the last of these.
        val unscoped = MergeCommand().test(
            listOf("--dry-run", "--mainline-branch", "main", "--mainline-branch", "master") + inputs
        )
        assertEquals(1, unscoped.statusCode, unscoped.output)
        assertTrue(
            unscoped.output.contains("--mainline-branch is given twice without naming an input: 'main' and 'master'"),
            unscoped.output,
        )

        val scoped = MergeCommand().test(
            listOf("--dry-run", "--mainline-branch", "backend::main", "--mainline-branch", "backend::main") + inputs
        )
        assertEquals(1, scoped.statusCode, scoped.output)
        assertTrue(scoped.output.contains("--mainline-branch is given twice for input 'backend'"), scoped.output)
    }

    @Test
    fun `a caret before a --mainline-branch scope is refused without advice to subtract`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run", "--mainline-branch", "^backend::main",
                tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("a mainline is a branch to braid along"), result.output)
        assertFalse(result.output.contains("subtracts"), result.output)
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
    fun `an unknown option is refused wherever it stands, with a suggestion`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                tmp.resolve("backend.git").toString(),
                "--dryrun",
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("no such option --dryrun"), result.output)
        assertTrue(result.output.contains("Did you mean --dry-run?"), result.output)

        // A short one too, which is no more an input than a long one is.
        val short = MergeCommand().test(listOf("--dry-run", tmp.resolve("backend.git").toString(), "-x"))
        assertEquals(1, short.statusCode, short.output)
        assertTrue(short.output.contains("no such option -x"), short.output)
    }

    @Test
    fun `a mistyped off switch is suggested as itself`() {
        // An off switch is a flag's secondary name, and clikt suggests from those as well: the
        // switch meant comes first, ahead of the flag it turns off.
        val result = MergeCommand().test(listOf("--dry-run", "--no-bar", "backend.git"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("no such option --no-bar"), result.output)
        assertTrue(result.output.contains("(Possible options: --no-bare,"), result.output)
    }

    @Test
    fun `an input after -- is read as an input, a leading dash and all`() {
        // No repository is at -dash, so the run is refused; what matters is what for.
        val after = MergeCommand().test(listOf("--dry-run", "--", "-dash"))
        assertEquals(1, after.statusCode, after.output)
        assertTrue(after.output.contains("-dash"), after.output)
        assertTrue("option" !in after.output, after.output)

        val before = MergeCommand().test(listOf("--dry-run", "--dryrun", "--", "-dash"))
        assertEquals(1, before.statusCode, before.output)
        assertTrue(before.output.contains("no such option --dryrun"), before.output)
    }

    @Test
    fun `a -- with no input after it still asks for one`() {
        val result = MergeCommand().test(listOf("--dry-run", "--"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("give at least one input repository"), result.output)
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
    fun `a ref name that cannot be written is refused on a dry run too, before any output exists`() {
        val at = java.time.Instant.parse("2021-01-01T09:00:00Z")
        TestRepoBuilder.create(tmp.resolve("backend.git")).use { repo ->
            val a1 = repo.commit("a1", at = at)
            repo.branch("main", a1)
            repo.branch("a", a1)
            repo.lightweightTag("v1", a1)
        }
        TestRepoBuilder.create(tmp.resolve("webui.git")).use { repo ->
            val b1 = repo.commit("b1", at = at.plusSeconds(60))
            repo.branch("main", b1)
            repo.branch("a/b", b1)
            repo.lightweightTag("v1", b1)
        }
        val out = tmp.resolve("merged.git")
        val inputs = listOf(tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString())

        // Names no check of the command line refuses, and the write would refuse only once every
        // commit was written: a name that is a directory of another, two inputs meeting on one, and
        // a name git does not accept.
        val clashes = listOf(
            listOf("--branch-prefix", "") to "'refs/heads/a' and 'refs/heads/a/b' cannot both be refs",
            listOf("--tag-prefix", "") to "'backend' and 'webui' would both write 'refs/tags/v1'",
            listOf("--tag-prefix", "{repo}.lock/") to "'refs/tags/backend.lock/v1' is not a valid ref name",
        )
        for ((options, refusal) in clashes) {
            for (dryRun in listOf(listOf("--dry-run"), emptyList())) {
                val result = MergeCommand().test(dryRun + options + listOf("-o", out.toString()) + inputs)

                assertEquals(1, result.statusCode, result.output)
                assertTrue(result.output.contains(refusal), result.output)
                assertTrue(!out.toFile().exists(), "the output should not have been created")
            }
        }
    }

    @Test
    fun `an -o that cannot be created is reported by the location given, not thrown`() {
        corpus()
        tmp.resolve("afile").toFile().writeText("a file, not a directory")
        val out = tmp.resolve("afile/merged.git")

        val result = MergeCommand().test(
            listOf("-o", out.toString(), tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString())
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("cannot create the output at '$out'"), result.output)
    }

    @Test
    fun `an -o that cannot be created is reported before a remote input is cloned beside it`() {
        corpus()
        tmp.resolve("afile").toFile().writeText("a file, not a directory")
        val out = tmp.resolve("afile/merged.git")

        val result = MergeCommand().test(
            listOf(
                "-o", out.toString(),
                tmp.resolve("backend.git").toUri().toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("cannot create the output at '$out'"), result.output)
        assertTrue(!result.output.contains("making every input a local repository"), result.output)
    }

    @Test
    fun `a clones directory that cannot be made beside a usable -o is reported with that -o`() {
        corpus()
        tmp.resolve(".timebraid-clones").toFile().writeText("a file, not a directory")
        val out = tmp.resolve("merged.git")

        val result = MergeCommand().test(
            listOf(
                "-o", out.toString(),
                tmp.resolve("backend.git").toUri().toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("for the remote inputs' clones, beside the output '$out'"), result.output)
    }

    @Test
    fun `a dry run refuses an -o the real run would refuse, and creates nothing`() {
        corpus()
        val inputs = listOf(tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString())
        val occupied = Files.createDirectories(tmp.resolve("occupied"))
        Files.writeString(occupied.resolve("keep.txt"), "somebody's")
        val file = tmp.resolve("afile").also { it.toFile().writeText("a file, not a directory") }
        val refusals = listOf(
            occupied to "already exists and is not empty; pass --force",
            file to "exists and is not a directory",
            file.resolve("merged.git") to "cannot create the output at '${file.resolve("merged.git")}'",
        )
        for ((out, refusal) in refusals) {
            for (dryRun in listOf(listOf("--dry-run"), emptyList())) {
                val result = MergeCommand().test(dryRun + listOf("-o", out.toString()) + inputs)

                assertEquals(1, result.statusCode, "$dryRun -o $out: ${result.output}")
                assertTrue(result.output.contains(refusal), "$dryRun -o $out: ${result.output}")
                // Refused before the inputs are read, where every other command-line refusal is.
                assertTrue(!result.output.contains("reading the inputs"), result.output)
            }
        }
        assertEquals(listOf("keep.txt"), occupied.toFile().list()?.toList())

        // An empty -o is no place at all, and is refused before anything is read, a dry run's too.
        for (dryRun in listOf(listOf("--dry-run"), emptyList())) {
            val empty = MergeCommand().test(dryRun + listOf("-o", "") + inputs)
            assertEquals(1, empty.statusCode, empty.output)
            assertTrue(empty.output.contains("-o/--output names no directory"), empty.output)
            assertTrue(!empty.output.contains("reading the inputs"), empty.output)
        }

        // --force takes the occupied directory, a dry run as well as a real one.
        val forced = MergeCommand().test(listOf("--dry-run", "--force", "-o", occupied.toString()) + inputs)
        assertEquals(0, forced.statusCode, forced.output)
    }

    @Test
    fun `an output spelled in another case than an input is refused where the filesystem ignores case`() {
        corpus()
        val backend = tmp.resolve("backend.git")
        val other = tmp.resolve("BACKEND.git")
        // Only where one name finds the other: on Windows, say, and not on most Linux filesystems.
        assumeTrue(Files.exists(other), "the filesystem under $tmp tells case apart")

        val result = MergeCommand().test(
            listOf("--dry-run", "-o", other.toString(), backend.toString(), tmp.resolve("webui.git").toString())
        )
        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("is the output (-o)"), result.output)
    }

    @Test
    fun `an input that is the output is refused, on a dry run and under --force alike`() {
        corpus()
        val backend = tmp.resolve("backend.git")
        val before = SourceRepository.open(backend).use { repo -> repo.branches().map { it.name to it.target.name } }

        for (mode in listOf("--dry-run", "--force")) {
            val result = MergeCommand().test(
                listOf(mode, "-o", backend.toString(), backend.toString(), tmp.resolve("webui.git").toString())
            )

            assertEquals(1, result.statusCode, "$mode: ${result.output}")
            assertTrue(
                result.output.contains("'$backend' is the output (-o), which a run cannot braid into itself"),
                "$mode: ${result.output}",
            )
        }
        val after = SourceRepository.open(backend).use { repo -> repo.branches().map { it.name to it.target.name } }
        assertEquals(before, after, "the input was written into")

        // One repository named another way on either side: a working tree and its own git
        // directory, a symlink to the input, a symlink to a working tree's git directory, and a
        // working tree whose `.git` is a symlink to its git directory kept elsewhere.
        val tree = tmp.resolve("tree")
        TestRepoBuilder.create(tree, bare = false).use { it.branch("main", it.commit("t1")) }
        val alias = Files.createSymbolicLink(tmp.resolve("alias.git"), backend)
        val gitDir = Files.createSymbolicLink(tmp.resolve("gitdir"), tree.resolve(".git"))
        // A working tree whose `.git` is itself a symlink to a git directory kept elsewhere.
        val linked = tmp.resolve("linked")
        TestRepoBuilder.create(linked, bare = false).use { it.branch("main", it.commit("l1")) }
        val kept = Files.move(linked.resolve(".git"), tmp.resolve("store.git"))
        Files.createSymbolicLink(linked.resolve(".git"), kept)
        // A working tree whose `.git` is a file naming its git directory, as a submodule's is.
        val pointed = tmp.resolve("pointed")
        val pointedStore = tmp.resolve("pointed-store.git")
        TestRepoBuilder.create(pointedStore).use { it.branch("main", it.commit("p1")) }
        Files.createDirectories(pointed)
        Files.writeString(pointed.resolve(".git"), "gitdir: $pointedStore\n")
        // A linked worktree of `tree`: its `.git` file names a directory under `tree/.git/worktrees`,
        // whose `commondir` leads back to `tree/.git`.
        val worktree = tmp.resolve("worktree")
        val admin = Files.createDirectories(tree.resolve(".git/worktrees/worktree"))
        Files.writeString(admin.resolve("HEAD"), "ref: refs/heads/main\n")
        Files.writeString(admin.resolve("commondir"), "../..\n")
        Files.writeString(admin.resolve("gitdir"), "${worktree.resolve(".git")}\n")
        Files.createDirectories(worktree)
        Files.writeString(worktree.resolve(".git"), "gitdir: $admin\n")
        // A working tree whose `.git` file names `<tree>.git` beside it, as --separate-git-dir does.
        val separate = tmp.resolve("separate")
        val separateStore = tmp.resolve("separate.git")
        TestRepoBuilder.create(separateStore).use { it.branch("main", it.commit("s1")) }
        Files.createDirectories(separate)
        Files.writeString(separate.resolve(".git"), "gitdir: $separateStore\n")
        val same = listOf(
            tree.resolve(".git") to tree, tree to tree.resolve(".git"), alias to backend,
            gitDir to tree, tree to gitDir,
            kept to linked, linked to kept, linked.resolve(".git") to linked,
            pointedStore to pointed, pointed to pointedStore,
            separateStore to separate, separate to separateStore,
            tree.resolve(".git") to worktree, worktree to tree,
            // A bare repository and a `.git` inside it: git would take the new one for it.
            backend.resolve(".git") to backend,
        )
        for ((o, input) in same) {
            val result = MergeCommand().test(
                listOf("--dry-run", "-o", o.toString(), input.toString(), tmp.resolve("webui.git").toString())
            )
            assertEquals(1, result.statusCode, "-o $o, input $input: ${result.output}")
            assertTrue(result.output.contains("is the output (-o)"), "-o $o, input $input: ${result.output}")
        }

        // The `.git` ending a bare repository's name is part of it: `backend` is another place,
        // an empty directory beside it included, which JGit alone would take for `backend.git`.
        Files.createDirectories(tmp.resolve("backend"))
        val beside = MergeCommand().test(
            listOf(
                "--dry-run", "-o", tmp.resolve("backend").toString(),
                backend.toString(), tmp.resolve("webui.git").toString(),
            )
        )
        assertEquals(0, beside.statusCode, beside.output)

        // Nor when that directory holds a `.git` that is no repository: JGit would guess the
        // sibling `backend.git` there too, and the input is not what `-o backend` writes into.
        Files.createDirectories(tmp.resolve("backend/.git"))
        val broken = MergeCommand().test(
            listOf(
                "--dry-run", "--force", "-o", tmp.resolve("backend").toString(),
                backend.toString(), tmp.resolve("webui.git").toString(),
            )
        )
        assertEquals(0, broken.statusCode, broken.output)
    }

    @Test
    @DisabledOnOs(
        value = [OS.WINDOWS],
        disabledReason = "Windows resolves a '..' as text, before any symlink, so it leads where its text does",
    )
    fun `an input that is the output by way of a dot-dot past a symlink is refused`() {
        corpus()
        val backend = tmp.resolve("backend.git")
        // A working tree whose `.git` is a file naming its git directory, as a submodule's is.
        val pointed = tmp.resolve("pointed")
        val pointedStore = tmp.resolve("pointed-store.git")
        TestRepoBuilder.create(pointedStore).use { it.branch("main", it.commit("p1")) }
        Files.createDirectories(pointed)
        Files.writeString(pointed.resolve(".git"), "gitdir: $pointedStore\n")
        // A `..` past a symlink into a working tree whose git directory is kept elsewhere: its graph
        // and its objects both come from the one `pointed/.git` names.
        Files.createDirectories(pointed.resolve("sub"))
        val up = Files.createSymbolicLink(tmp.resolve("up"), pointed.resolve("sub"))
        // A `..` past a symlink: `dotdot/..` is backend.git itself, not the directory `dotdot` sits in.
        val dotdot = Files.createSymbolicLink(tmp.resolve("dotdot"), backend.resolve("refs"))
        val same = listOf(
            pointedStore to up.resolve(".."), up.resolve("..") to pointedStore,
            dotdot.resolve("..") to backend,
        )
        for ((o, input) in same) {
            val result = MergeCommand().test(
                listOf("--dry-run", "-o", o.toString(), input.toString(), tmp.resolve("webui.git").toString())
            )
            assertEquals(1, result.statusCode, "-o $o, input $input: ${result.output}")
            assertTrue(result.output.contains("is the output (-o)"), "-o $o, input $input: ${result.output}")
        }

        // The same kind of `..` from inside `tree` is `pointed`, read and fetched alike, and so is
        // not the output `tree`: a lexical `..` would have fetched it from `tree`.
        val tree = tmp.resolve("tree")
        TestRepoBuilder.create(tree, bare = false).use { it.branch("main", it.commit("t1")) }
        val inside = Files.createSymbolicLink(tree.resolve("sym"), pointed.resolve("sub")).resolve("..")
        val apart = MergeCommand().test(
            listOf(
                "--dry-run", "--force", "-o", tree.toString(),
                inside.toString(), tmp.resolve("webui.git").toString(),
            )
        )
        assertEquals(0, apart.statusCode, apart.output)
    }

    @Test
    fun `a --plan-out that cannot be written is refused before the output is created`() {
        corpus()
        val out = tmp.resolve("merged.git")
        // A directory where the file would go: the one case that fails whoever runs the test, root
        // included, where a file without write permission would not.
        val plan = tmp.resolve("plan").also { it.toFile().mkdir() }

        val result = MergeCommand().test(
            listOf(
                "-o", out.toString(),
                "--plan-out", plan.toString(),
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString(),
            )
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("--plan-out '$plan' cannot be written: it is a directory"), result.output)
        assertTrue(!out.toFile().exists(), "the output should not have been created")
    }

    @Test
    fun `a subdirectory override and --root-repo are honoured`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                "--root-repo", "backend",
                tmp.resolve("backend.git").toString(),
                tmp.resolve("webui.git").toString() + "::apps/ui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> <root>"), result.output)
        // "::apps/ui" places the content, and the name follows its last segment.
        assertTrue(result.output.contains("ui -> apps/ui/"), result.output)
    }

    @Test
    fun `two inputs whose directories share a name are told apart by placing them`() {
        for (side in listOf("a", "b")) {
            TestRepoBuilder.create(tmp.resolve("$side/proj.git")).use { repo ->
                repo.branch("main", repo.commit("$side commit"))
            }
        }
        val shared = listOf(tmp.resolve("a/proj.git").toString(), tmp.resolve("b/proj.git").toString())

        // Each lands at its name when it gives no place, so the two meet at `proj/`.
        val collided = MergeCommand().test(listOf("--dry-run") + shared)
        assertEquals(1, collided.statusCode, collided.output)
        assertTrue(collided.output.contains("two inputs would be placed at 'proj'"), collided.output)

        val named = MergeCommand().test(
            listOf("--dry-run", shared[0] + "::proj-a", shared[1] + "::proj-b"),
        )
        assertEquals(0, named.statusCode, named.output)
        assertTrue(named.output.contains("proj-a -> proj-a/"), named.output)
        assertTrue(named.output.contains("proj-b -> proj-b/"), named.output)
    }

    @Test
    fun `an equals sign is part of the location unless a separator precedes it`() {
        // `=<name>` lives inside the suffix, so a location holding a `=` needs no escaping and no
        // guard: with no `::` in the argument, the whole of it is the location.
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/a=b/c"))

        assertEquals(1, result.statusCode, result.output)
        // Refused as a location that is not there. Every refusal quotes the argument, so the `=`
        // turning up in the message would not tell one reading from another on its own.
        assertTrue(result.output.contains("no git repository"), result.output)
        // The segment carrying the `=` is the whole of what this is about: the rest of the location
        // reaches the message as a path, and its separators are then the platform's rather than the
        // ones written here.
        assertTrue(result.output.contains("a=b"), result.output)
    }

    @Test
    @DisabledOnOs(
        value = [OS.WINDOWS],
        disabledReason = "a Windows filename cannot hold a ':', so the directory cannot be created",
    )
    fun `a location holding the separator is ended by a bare one, and then named`() {
        corpus()
        val holding = tmp.resolve("odd::name")
        tmp.resolve("backend.git").toFile().renameTo(holding.toFile())

        // The location is now whole — but the name derived from it holds a ':', which an <input>::
        // scope could not spell, so the argument has to say what the input is called.
        val derived = MergeCommand().test(listOf("--dry-run", "$holding::", path("webui.git")))
        assertEquals(1, derived.statusCode, derived.output)
        assertTrue(derived.output.contains("cannot be a repository name"), derived.output)
        assertTrue(derived.output.contains("give the input a name, as '$holding::=<name>'"), derived.output)

        val named = MergeCommand().test(
            listOf("--dry-run", "$holding::=oddname", path("webui.git"))
        )
        assertEquals(0, named.statusCode, named.output)
        assertTrue(named.output.contains("oddname -> oddname/"), named.output)
    }

    @Test
    fun `an unusable subdirectory is refused, and the message names the remedy`() {
        // `b//c` cannot be a subdirectory at all, so the grammar refuses it while parsing and the
        // argument never reaches `checkSplit`; the message carries the remedy as well as the
        // reason.
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/a::b//c"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("not a usable subdirectory"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    @DisabledOnOs(
        value = [OS.WINDOWS],
        disabledReason = "a Windows filename cannot hold a ':', so the text after '::' cannot belong to the location",
    )
    fun `a location that is not there names the text the separator dropped`() {
        // `b/c` parses as a perfectly good subdirectory, so nothing about the reading looks wrong
        // and the run would fail naming only '/nonexistent/a'. Which reading was meant is not
        // settled here — the location is missing either way — but the dropped text is said.
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/a::b/c"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("nothing at '/nonexistent/a'"), result.output)
        assertTrue(result.output.contains("with '::b/c' read off its end"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)

        // A subdirectory and a name are one suffix, quoted whole rather than taken for one part.
        val both = MergeCommand().test(listOf("--dry-run", "/nonexistent/path::libs=core"))
        assertTrue(both.output.contains("with '::libs=core' read off its end"), both.output)
    }

    @Test
    fun `a missing location that ends in the bare separator is only a missing location`() {
        // The bare `::` already says the location holds the separator, so there is nothing cut
        // off to report and no remedy to offer: the refusal is the location's own.
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/path::"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("no git repository at /nonexistent/path"), result.output)
        assertTrue("end the argument with '::'" !in result.output, result.output)
    }

    @Test
    fun `a malformed ref pattern is refused before a remote input is cloned`() {
        corpus()
        val remote = tmp.resolve("backend.git").toUri().toString()

        val result = MergeCommand().test(
            listOf("-o", path("out.git"), "--interleave-ref", "^refs/heads/wip", remote, path("webui.git"))
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("nothing but patterns that subtract"), result.output)
        assertTrue(!Files.exists(tmp.resolve(".timebraid-clones")), result.output)
    }

    @Test
    fun `a local input that is no repository is refused before a remote one is cloned`() {
        corpus()
        val remote = tmp.resolve("backend.git").toUri().toString()

        val result = MergeCommand().test(listOf("-o", path("out.git"), remote, path("missing")))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("no git repository at ${path("missing")}"), result.output)
        // The clones go beside the output, so a refusal made after them leaves this behind.
        assertTrue(!Files.exists(tmp.resolve(".timebraid-clones")), result.output)
    }

    @Test
    @DisabledOnOs(
        value = [OS.WINDOWS],
        disabledReason = "a Windows filename cannot hold a ':', so the directory cannot be created",
    )
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
    fun `two inputs may share a name, and a reference to it is refused as naming both`() {
        corpus()
        val inputs = listOf(path("backend.git") + "::libs/a", path("webui.git") + "::apps/a")

        // A name is a label: both are called `a`, and where each lands tells them apart.
        val shared = MergeCommand().test(listOf("--dry-run") + inputs)
        assertEquals(0, shared.statusCode, shared.output)
        assertTrue(shared.output.contains("a -> libs/a/"), shared.output)
        assertTrue(shared.output.contains("a -> apps/a/"), shared.output)

        // What refers to an input by its name cannot say which of the two it means.
        val scoped = MergeCommand().test(listOf("--dry-run", "--ref", "a::refs/heads/main") + inputs)
        assertEquals(1, scoped.statusCode, scoped.output)
        assertTrue(scoped.output.contains("two inputs are called that"), scoped.output)
        // Said as --root-repo says it: where each is, and each argument's own way out.
        assertTrue(scoped.output.contains("'${inputs[0].substringBefore("::")}::libs/a=<name>'"), scoped.output)
        assertTrue(scoped.output.contains("'${inputs[1].substringBefore("::")}::apps/a=<name>'"), scoped.output)

        // And the way out keeps the subdirectory each argument gave: `<path-or-url>::=<name>` would move
        // either input from where its argument put it to `<name>/`.
        val root = MergeCommand().test(listOf("--dry-run", "--root-repo", "a") + inputs)
        val printed = root.output.replace(Regex("\\s+"), " ")
        assertEquals(1, root.statusCode, root.output)
        assertTrue(printed.contains("--root-repo 'a' names two inputs"), root.output)
        assertTrue(printed.contains("'${path("backend.git")}::libs/a=<name>'"), root.output)
        assertTrue(printed.contains("'${path("webui.git")}::apps/a=<name>'"), root.output)
    }

    @Test
    fun `two inputs sharing a name meet on a ref name, which {subdir} keeps apart`() {
        for (side in listOf("a", "b")) {
            TestRepoBuilder.create(tmp.resolve("$side.git")).use { repo ->
                val c = repo.commit("$side commit")
                repo.branch("main", c)
                repo.branch("wip", c)
            }
        }
        val inputs = listOf(path("a.git") + "::a=x", path("b.git") + "::b=x")

        // `{repo}/` qualifies both alike, so their `wip` branches meet on one name.
        val met = MergeCommand().test(listOf("--dry-run") + inputs)
        assertEquals(1, met.statusCode, met.output)
        assertTrue(met.output.contains("two inputs called 'x' would both write 'refs/heads/x/wip'"), met.output)
        assertTrue(met.output.contains("{subdir}"), met.output)

        val apart = MergeCommand().test(listOf("--dry-run", "--branch-prefix", "{subdir}/") + inputs)
        assertEquals(0, apart.statusCode, apart.output)

        // A real run labels each input's fetch by where it lands as well, the name being both's.
        val written = MergeCommand().test(
            listOf("-o", tmp.resolve("apart.git").toString(), "--branch-prefix", "{subdir}/") + inputs
        )
        assertEquals(0, written.statusCode, written.output)
        assertTrue(written.output.contains("[x(a)] ") && written.output.contains("[x(b)] "), written.output)

        // So does a remote input's clone, which a dry run makes too.
        GitCli.requireGit()
        val urls = listOf("a", "b").map { tmp.resolve("$it.git").toUri().toString() + "::$it=x" }
        val cloned = MergeCommand().test(listOf("--dry-run", "--branch-prefix", "{subdir}/") + urls)
        assertEquals(0, cloned.statusCode, cloned.output)
        assertTrue(
            cloned.output.contains("[x(a)] cloned into") && cloned.output.contains("[x(b)] cloned into"),
            cloned.output,
        )

        // A remote is named after its input, and two of one name would be one remote.
        val remotes = MergeCommand().test(
            listOf("--dry-run", "--keep-remotes", "--branch-prefix", "{subdir}/") + inputs
        )
        assertEquals(1, remotes.statusCode, remotes.output)
        assertTrue(remotes.output.contains("two are called 'x'"), remotes.output)
    }

    @Test
    fun `a name may hold a slash, and what else it may hold follows from where it is used`() {
        corpus()
        val backend = path("backend.git")
        val webui = path("webui.git")

        // Each segment one a directory could be called, and the ref names it makes are git's.
        val slashed = MergeCommand().test(listOf("--dry-run", "$backend::libs/b=libs/backend", webui))
        assertEquals(0, slashed.statusCode, slashed.output)
        assertTrue(slashed.output.contains("libs/backend -> libs/b/"), slashed.output)
        val empty = MergeCommand().test(listOf("--dry-run", "$backend::=libs//backend", webui))
        assertEquals(1, empty.statusCode, empty.output)
        assertTrue(empty.output.contains("is not a usable name"), empty.output)

        // A name no template puts in a ref is a label: git's rules for a ref name are not asked.
        val label = listOf("$backend::b=b~ck", webui)
        val refused = MergeCommand().test(listOf("--dry-run") + label)
        assertEquals(1, refused.statusCode, refused.output)
        assertTrue(refused.output.contains("--tag-prefix '{repo}/' puts it in 'refs/tags/b~ck/'"), refused.output)
        val unused = MergeCommand().test(
            listOf("--dry-run", "--tag-prefix", "{subdir}/", "--branch-prefix", "{subdir}/") + label
        )
        assertEquals(0, unused.statusCode, unused.output)
        // --keep-remotes names a remote after it, so there it is a ref name again.
        val remote = MergeCommand().test(
            listOf("--dry-run", "--keep-remotes", "--tag-prefix", "{subdir}/", "--branch-prefix", "{subdir}/") + label
        )
        assertEquals(1, remote.statusCode, remote.output)
        assertTrue(remote.output.contains("--keep-remotes puts it in 'refs/remotes/b~ck/'"), remote.output)

        // And two remotes, one a directory of the other, are refused: a pruning fetch of the outer
        // deletes what the inner fetched.
        val nested = MergeCommand().test(
            listOf("--dry-run", "--keep-remotes", "$backend::b=libs", "$webui::w=libs/core"),
        )
        assertEquals(1, nested.statusCode, nested.output)
        assertTrue(nested.output.contains("'libs' would hold the refs of 'libs/core'"), nested.output)
    }

    @Test
    fun `a name holding whitespace or opening with a caret is refused, since a scope could not spell it`() {
        corpus()
        val backend = path("backend.git")
        val webui = path("webui.git")

        // Refused even where no template puts the name in a ref: a pattern option splits its value
        // on whitespace, and reads a '^' before a scope as a subtraction.
        val label = listOf("--dry-run", "--tag-prefix", "{subdir}/", "--branch-prefix", "{subdir}/")
        for (name in listOf("my repo", "^core")) {
            val result = MergeCommand().test(label + listOf("$backend::b=$name", webui))
            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains("'$name' cannot be a repository name"), result.output)
            assertTrue(result.output.contains("an <input>:: scope has to be able to spell it"), result.output)
        }
    }

    @Test
    @DisabledOnOs(
        value = [OS.WINDOWS],
        disabledReason = "a Windows filename cannot hold a ':', so the directory cannot be created",
    )
    fun `a name holding a colon is refused, since a scope ends at the first double colon`() {
        corpus()
        val webui = path("webui.git")

        // Named after the directory, and put in no ref: 'x:::refs/heads/main' would read the scope
        // 'x'. Every ':' is refused, 'a:b' too, so that which one ends a scope never matters.
        val label = listOf("--dry-run", "--tag-prefix", "v/", "--branch-prefix", "")
        for (name in listOf("x:", "a:b")) {
            val dir = tmp.resolve(name)
            tmp.resolve("backend.git").toFile().copyRecursively(dir.toFile())
            val result = MergeCommand().test(label + listOf(dir.toString(), webui))
            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains("'$name' cannot be a repository name"), result.output)
            assertTrue(result.output.contains("a scope ends at the first '::'"), result.output)
        }
    }

    @Test
    fun `the input at the root is asked for {subdir} in a prefix, and the notes prefix only under --notes`() {
        corpus()
        val backend = path("backend.git")
        val webui = path("webui.git")
        val label = listOf("--tag-prefix", "{subdir}/", "--branch-prefix", "{subdir}/", "$backend::b=b~ck", webui)

        // At the root the name stands for {subdir} too, so a prefix holding it puts the name in a
        // ref, and the refusal names the option on the command line.
        val root = MergeCommand().test(listOf("--dry-run", "--root-repo", "b~ck") + label)
        assertEquals(1, root.statusCode, root.output)
        assertTrue(root.output.contains("--tag-prefix '{subdir}/' puts it in 'refs/tags/b~ck/'"), root.output)

        // The notes prefix is asked only where notes are written.
        val plain = MergeCommand().test(listOf("--dry-run") + label)
        assertEquals(0, plain.statusCode, plain.output)
        val notes = MergeCommand().test(listOf("--dry-run", "--notes") + label)
        assertEquals(1, notes.statusCode, notes.output)
        assertTrue(notes.output.contains("--notes-prefix '"), notes.output)
    }

    @Test
    fun `the --root-repo input meets no other at the place it was written with`() {
        corpus()

        // backend lands at the root, so webui may have backend/ for itself.
        val result = MergeCommand().test(
            listOf(
                "--dry-run", "--root-repo", "backend",
                tmp.resolve("backend.git").toString(), tmp.resolve("webui.git").toString() + "::backend=cee",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> <root>"), result.output)
        assertTrue(result.output.contains("cee -> backend/"), result.output)
    }

    @Test
    fun `two inputs placed at one directory are refused naming both locations`() {
        val first = tmp.resolve("libs/core")
        val second = tmp.resolve("tools/core")
        first.createDirectories()
        second.createDirectories()

        val result = MergeCommand().test(
            listOf("--dry-run", "-o", tmp.resolve("out").toString(), first.toString(), second.toString()),
        )

        // The place alone leaves the reader to work out which two inputs it came from.
        val printed = result.output.replace(Regex("\\s+"), " ")
        assertEquals(1, result.statusCode, result.output)
        assertTrue(printed.contains("two inputs would be placed at 'core'"), result.output)
        assertTrue(printed.contains("$first and $second"), result.output)
        // Moved, it keeps its name, which the new subdirectory would otherwise give it.
        assertTrue(printed.contains("'$first::<subdir>=core'"), result.output)
    }

    @Test
    fun `an IPv6 URL is refused with the remedy rather than read as a name`() {
        // Both spellings git accepts for a literal address carry a `::`, and no guard second-guesses
        // it any more: the argument is read as written and the way out is stated.
        for (url in listOf("https://[fe80::1]/repo.git", "git@[::1]:repo.git")) {
            val result = MergeCommand().test(listOf("--dry-run", url))

            assertEquals(1, result.statusCode, "$url\n${result.output}")
            assertTrue(result.output.contains("stops inside a '['"), result.output)
            assertTrue(result.output.contains("end the argument with '::'"), result.output)
        }
    }

    @Test
    fun `a bare separator asks for the derived name`() {
        corpus()

        val result = MergeCommand().test(
            listOf("--dry-run", path("backend.git") + "::", path("webui.git") + "::ui")
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> backend/"), result.output)
        assertTrue(result.output.contains("ui -> ui/"), result.output)
    }

    @Test
    fun `a colon in the suffix is refused rather than reread`() {
        // What keeps the grammar provable: a suffix holds no colon, so it can hold no `::` of its
        // own, so the last `::` in the argument is always the separator.
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::odd:name"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("cannot appear in the subdirectory"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a second equals sign is refused rather than folded into the name`() {
        val result = MergeCommand().test(
            listOf("--dry-run", path("backend.git") + "::libs/backend=odd=name")
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("cannot appear in the name"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a name git would not accept in a ref is refused here`() {
        // The name becomes part of a ref wherever one carries it (a tag, a branch, a
        // --keep-remotes mirror), so this used to surface only at the write of the first such ref,
        // once the braid was written, and an input no ref carried went through.
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::ok=odd~name"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("cannot be a repository name"), result.output)
        // Another name is the way out, the subdirectory kept; and since the name came out of the
        // suffix, so is the suffix's own remedy.
        assertTrue(
            result.output.contains("give the input a name, as '${path("backend.git")}::ok=<name>'"),
            result.output,
        )
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `an equals sign with nothing after it is refused`() {
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::name="))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("names no repository"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a nested subdirectory places an input below the output root`() {
        corpus()

        val result = MergeCommand().test(
            listOf(
                "--dry-run",
                tmp.resolve("backend.git").toString() + "::libs/backend",
                tmp.resolve("webui.git").toString() + "::apps/webui",
            )
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
        assertTrue(result.output.contains("webui -> apps/webui/"), result.output)
    }

    @Test
    fun `a subdirectory with an unusable segment is refused before anything is read`() {
        val result = MergeCommand().test(listOf("--dry-run", "/nonexistent/backend.git::libs//backend"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("not a usable subdirectory"), result.output)
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a file scheme over a Windows path is still named by its last segment`() {
        // Concatenating `file://` with an absolute path is how a caller spells a local repository as
        // a URL, and on Windows the result carries a drive letter and backslashes. The URL names
        // the directory the clone goes in, so reading it from the colon of `C:` would take the clone
        // out of the clone root and into whatever `\repos\backend` resolves to.
        val result = MergeCommand().test(listOf("--dry-run", "file://C:\\repos\\backend.git"))

        assertEquals(1, result.statusCode, result.output)
        // Read from the refusal, which quotes the whole `git clone` including where it was sending
        // the clone. The run cannot get further than that: there is no repository at the path.
        val target = Path.of(result.output.substringAfter("backend.git ").substringBefore("`").trim())
        assertTrue(Regex("backend-[0-9a-f]{12}\\.git").matches(target.fileName.toString()), result.output)
        assertTrue(target.parent.fileName.toString().startsWith("timebraid-clones-"), result.output)
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
        // outside a drive letter, which is how `a::b::` gets in. Either way the report is the
        // user's typo, not an exception out of the parser.
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
        assertTrue(result.output.contains("ui -> frontend/"), result.output)
    }

    @Test
    fun `one repository given as two arguments is refused, however the second spells it`() {
        corpus()
        val backend = path("backend.git")
        for (second in listOf("$backend::y", "$backend/.::y")) {
            val result = MergeCommand().test(listOf("--dry-run", "$backend::x", second))

            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.contains("two input arguments name one repository"), result.output)
            assertTrue(result.output.contains("placing it at 'x' and 'y'"), result.output)
        }
    }

    @Test
    fun `two findings at one subdirectory are refused, told that a finding stays where it is`() {
        // `core.git` and `core` both land at `libs/core`, and neither is an argument to move.
        scanned("libs/core.git")
        scanned("libs/core", bare = false)

        val result = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))

        val printed = result.output.replace(Regex("\\s+"), " ")
        assertEquals(1, result.statusCode, result.output)
        assertTrue(printed.contains("two inputs would be placed at 'libs/core'"), result.output)
        assertTrue(
            printed.contains("a finding cannot be given another subdirectory: rename one of their directories"),
            result.output,
        )
    }

    @Test
    fun `a finding and two arguments at one subdirectory offer one of the arguments a subdirectory`() {
        scanned("libs/core.git")
        for (name in listOf("a.git", "b.git")) {
            TestRepoBuilder.create(tmp.resolve(name)).use { it.branch("main", it.commit("x1")) }
        }

        val result = MergeCommand().test(
            listOf("--dry-run", "--scan", path("tree"), path("a.git") + "::libs/core", path("b.git") + "::libs/core"),
        )

        val printed = result.output.replace(Regex("\\s+"), " ")
        assertEquals(1, result.statusCode, result.output)
        assertTrue(printed.contains("three inputs would be placed at 'libs/core'"), result.output)
        assertTrue(printed.contains("give one of the arguments a subdirectory of its own"), result.output)
    }

    /** A repository at [at] under the scan base, with one commit in it. */
    private fun scanned(at: String, bare: Boolean = true) {
        val dir = tmp.resolve("tree/$at")
        dir.parent?.createDirectories()
        TestRepoBuilder.create(dir, bare = bare).use { it.branch("main", it.commit("c1")) }
    }

    @Test
    fun `--scan takes the layout off the directory tree`() {
        scanned("libs/backend.git")
        scanned("apps/webui.git")

        val result = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("webui -> apps/webui/"), result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
    }

    @Test
    fun `--scan puts a base directory that is itself a repository at the output root`() {
        TestRepoBuilder.create(tmp.resolve("tree"), bare = false).use {
            it.branch("main", it.commit("p1"))
        }
        scanned("libs/backend.git")

        val result = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("tree -> <root>"), result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
    }

    @Test
    fun `a placement refusal tells an argument how to move, and a finding that it cannot`() {
        // A file where the scan found a repository below it: the base holds 'libs', so nothing lands
        // inside it.
        TestRepoBuilder.create(tmp.resolve("tree"), bare = false).use {
            it.branch("main", it.commit("p1", files = mapOf("libs" to "a stray file")))
        }
        scanned("libs/backend.git")
        val found = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))
        assertEquals(1, found.statusCode, found.output)
        assertTrue(found.output.contains("rename the directory of the repository at 'libs/backend'"), found.output)

        TestRepoBuilder.create(tmp.resolve("webui.git")).use { it.branch("main", it.commit("w1")) }
        val argument = MergeCommand().test(
            listOf("--dry-run", "--scan", path("tree"), "${path("webui.git")}::libs/webui")
        )
        assertEquals(1, argument.statusCode, argument.output)
        assertTrue(
            argument.output.contains(
                "give the repository at 'libs/webui' another subdirectory, as '${path("webui.git")}::<subdir>=webui'"
            ),
            argument.output,
        )
    }

    @Test
    fun `a correction renames the finding at its subdirectory, and leaves it there`() {
        scanned("libs/core.git")
        scanned("tools/core.git")

        // Two findings may share a name; where each sits tells them apart.
        val shared = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))
        assertEquals(0, shared.statusCode, shared.output)
        assertTrue(shared.output.contains("core -> libs/core/"), shared.output)
        assertTrue(shared.output.contains("core -> tools/core/"), shared.output)

        val named = MergeCommand().test(listOf("--dry-run", "--scan", path("tree"), "::tools/core=tools-core"))
        assertEquals(0, named.statusCode, named.output)
        // Renamed, and left where the scan put it: a correction names the place, it does not move.
        assertTrue(named.output.contains("tools-core -> tools/core/"), named.output)
        assertTrue(named.output.contains("core -> libs/core/"), named.output)

        // Matched by the string, against a place the scan found something at, and needing a name.
        val refusals = listOf(
            listOf("::tools/cor=x") to "it found none there; it found one at 'libs/core', 'tools/core'",
            listOf("::tools/core") to "gives it no name",
            listOf("::tools/core=") to
                "names no repository after its '=' (a correction gives one, as '::tools/core=<name>')",
            listOf("::tools/core=a", "::tools/core=b") to "two corrections rename",
            // Not a correction: one with a location is named after its subdirectory, or the location.
            listOf(path("tree") + "/libs/core::libs/core=") to "leave the '=' out to name it after the subdirectory",
            listOf(path("tree") + "/libs/core::=") to "leave the '=' out to name it after the location",
        )
        for ((extra, refusal) in refusals) {
            val result = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")) + extra)
            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.output.replace(Regex("\\s+"), " ").contains(refusal), result.output)
        }
        // A correction has no location, so a refusal of its suffix offers none of the location's
        // remedies.
        val unusable = MergeCommand().test(listOf("--dry-run", "--scan", path("tree"), "::tools//core=x"))
        assertEquals(1, unusable.statusCode, unusable.output)
        assertTrue(unusable.output.contains("is not a usable subdirectory"), unusable.output)
        assertTrue("end the argument with '::'" !in unusable.output, unusable.output)

        // And without a scan there is nothing to correct.
        val unscanned = MergeCommand().test(listOf("--dry-run", "::tools/core=x", path("tree/libs/core.git")))
        assertEquals(1, unscanned.statusCode, unscanned.output)
        assertTrue(unscanned.output.contains("there is no --scan"), unscanned.output)
    }

    @Test
    fun `a scanned name git will not have in a ref is refused, and a correction renames it`() {
        scanned("apps/x~y.git")

        // Refused when the scan is read, as an argument's own name is, rather than at the write.
        val refused = MergeCommand().test(listOf("--dry-run", "--scan", path("tree")))
        assertEquals(1, refused.statusCode, refused.output)
        val printed = refused.output.replace(Regex("\\s+"), " ")
        assertTrue(
            printed.contains("'x~y' cannot be a repository name (found by --scan at "),
            refused.output,
        )
        assertTrue(printed.contains("--tag-prefix '{repo}/' puts it in 'refs/tags/x~y/'"), refused.output)
        assertTrue(printed.contains("give the input a name, as '::apps/x~y=<name>'"), refused.output)

        val renamed = MergeCommand().test(listOf("--dry-run", "--scan", path("tree"), "::apps/x~y=xy"))
        assertEquals(0, renamed.statusCode, renamed.output)
        assertTrue(renamed.output.contains("xy -> apps/x~y/"), renamed.output)
    }

    @Test
    fun `an argument naming a found repository however spelled is refused, naming the correction`() {
        scanned("libs/core.git")
        scanned("tools/core", bare = false)
        val alias = Files.createSymbolicLink(tmp.resolve("alias.git"), tmp.resolve("tree/libs/core.git"))

        // By its own path, by its `.git`, and through a symlink: one git directory each time, which
        // is what makes it the finding rather than another input however it is spelled.
        val spellings = listOf(
            path("tree/libs/core.git") + "::=x" to "'::libs/core=<name>'",
            path("tree/tools/core/.git") to "'::tools/core=<name>'",
            "$alias::=x" to "'::libs/core=<name>'",
        )
        for ((argument, correction) in spellings) {
            val result = MergeCommand().test(listOf("--dry-run", "--scan", path("tree"), argument))
            val printed = result.output.replace(Regex("\\s+"), " ")
            assertEquals(1, result.statusCode, result.output)
            assertTrue(printed.contains("names a repository --scan already found"), result.output)
            assertTrue(printed.contains("write $correction"), result.output)
        }
    }

    @Test
    fun `a scan leaves the run's own output out, and an argument or a finding that is the output is refused`() {
        scanned("libs/backend.git")
        // What an earlier run with `-o tree/merged.git` left beside its inputs.
        scanned("merged.git")
        val out = path("tree/merged.git")

        // --force, since the output is a repository already, and a dry run now asks what the real
        // run would.
        val result = MergeCommand().test(listOf("--dry-run", "--force", "-o", out, "--scan", path("tree")))
        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
        assertTrue(!result.output.contains("merged -> "), result.output)

        // The same output named through a symlink is left out all the same.
        val alias = Files.createSymbolicLink(tmp.resolve("out-alias.git"), tmp.resolve("tree/merged.git"))
        val aliased = MergeCommand().test(
            listOf("--dry-run", "--force", "-o", alias.toString(), "--scan", path("tree"))
        )
        assertEquals(0, aliased.statusCode, aliased.output)
        assertTrue(!aliased.output.contains("merged -> "), aliased.output)

        val named = MergeCommand().test(listOf("--dry-run", "-o", out, path("tree/libs/backend.git"), out))
        assertEquals(1, named.statusCode, named.output)
        assertTrue(named.output.contains("is the output (-o)"), named.output)

        // A working tree the scan finds, whose own git directory the output is.
        scanned("apps/webui", bare = false)
        val gitDir = MergeCommand().test(
            listOf("--dry-run", "-o", path("tree/apps/webui/.git"), "--scan", path("tree"))
        )
        assertEquals(1, gitDir.statusCode, gitDir.output)
        assertTrue(gitDir.output.contains("which --scan found, is the output (-o)"), gitDir.output)

        // The same where the working tree's `.git` is a symlink to a git directory kept elsewhere.
        scanned("apps/linked", bare = false)
        val kept = Files.move(tmp.resolve("tree/apps/linked/.git"), tmp.resolve("linked-store.git"))
        Files.createSymbolicLink(tmp.resolve("tree/apps/linked/.git"), kept)
        val linked = MergeCommand().test(
            listOf("--dry-run", "-o", path("tree/apps/linked/.git"), "--scan", path("tree"))
        )
        assertEquals(1, linked.statusCode, linked.output)
        assertTrue(linked.output.contains("which --scan found, is the output (-o)"), linked.output)
    }

    @Test
    fun `an argument for a directory the scan did not find is an input of its own`() {
        scanned("libs/backend.git")
        TestRepoBuilder.create(tmp.resolve("outside.git")).use { it.branch("main", it.commit("o1")) }

        val result = MergeCommand().test(
            listOf("--dry-run", "--scan", path("tree"), path("outside.git")),
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("backend -> libs/backend/"), result.output)
        assertTrue(result.output.contains("outside -> outside/"), result.output)
    }

    @Test
    fun `--root-repo against a scanned base that already holds the root is refused, with the way out`() {
        TestRepoBuilder.create(tmp.resolve("tree"), bare = false).use {
            it.branch("main", it.commit("p1"))
        }
        scanned("libs/backend.git")

        val clash = MergeCommand().test(
            listOf("--dry-run", "--scan", path("tree"), "--root-repo", "backend"),
        )
        assertEquals(1, clash.statusCode, clash.output)
        assertTrue(clash.output.contains("conflicts with --scan"), clash.output)

        // Nothing moves a finding, so the way out is the one the message names: leave --root-repo
        // out, and the base stays at the root. A correction with no subdirectory renames it there.
        assertTrue(clash.output.contains("leave --root-repo out"), clash.output)
        val renamed = MergeCommand().test(listOf("--dry-run", "--scan", path("tree"), "::=platform"))
        assertEquals(0, renamed.statusCode, renamed.output)
        assertTrue(renamed.output.contains("platform -> <root>"), renamed.output)
    }

    @Test
    fun `neither an input nor a scan is a usage error, not an empty merge`() {
        val result = MergeCommand().test(listOf("--dry-run"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("--scan"), result.output)
    }

    @Test
    fun `a name taken from the suffix's subdirectory is refused with a name that keeps the subdirectory`() {
        val result = MergeCommand().test(listOf("--dry-run", path("backend.git") + "::a..b"))

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'a..b' cannot be a repository name"), result.output)
        // `::=<name>` would drop the subdirectory the argument gave.
        assertTrue(
            result.output.contains("give the input a name, as '${path("backend.git")}::a..b=<name>'"),
            result.output,
        )
        assertTrue(result.output.contains("end the argument with '::'"), result.output)
    }

    @Test
    fun `a name ending in dot lock is refused by git's rule, which JGit's own check lets by`() {
        val result = MergeCommand().test(
            listOf("--dry-run", tmp.resolve("backend.git").toString() + "::ok=odd.lock")
        )

        assertEquals(1, result.statusCode, result.output)
        assertTrue(result.output.contains("'odd.lock' cannot be a repository name"), result.output)
    }

    @Test
    fun `a name derived from a location git would not take in a ref is refused, and naming it works`() {
        corpus()
        // A '~', which only git's own rule refuses in a ref: a space or a ':' is refused earlier, by
        // the rules every name is held to, and would not show that this one is asked at all.
        val spaced = tmp.resolve("my~repo")
        tmp.resolve("backend.git").toFile().renameTo(spaced.toFile())
        val webui = tmp.resolve("webui.git").toString()

        // Refused while the arguments are read, before an output exists: it used to fail only at
        // the write of a ref carrying the name, a tag's, with the braid already written.
        val derived = MergeCommand().test(listOf("--dry-run", spaced.toString(), webui))
        val printed = derived.output.replace(Regex("\\s+"), " ")
        assertEquals(1, derived.statusCode, derived.output)
        assertTrue(printed.contains("'my~repo' cannot be a repository name"), derived.output)
        assertTrue(printed.contains("give the input a name, as '$spaced::=<name>'"), derived.output)

        val named = MergeCommand().test(listOf("--dry-run", "$spaced::=myrepo", webui))
        assertEquals(0, named.statusCode, named.output)
        assertTrue(named.output.contains("myrepo -> myrepo/"), named.output)
    }
}
