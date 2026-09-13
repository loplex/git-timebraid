package cz.loplex.timebraid.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.SystemReader
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SourceRepositoryTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads branches, tags and commit data out of a bare repository`() {
        val built = TestRepoBuilder.create(tmp.resolve("backend.git"))
        val first = built.commit("first")
        val second = built.commit("second\n\nwith a body line", parents = listOf(first))
        val feature = built.commit("feature work", parents = listOf(first))
        built.branch("main", second)
        built.branch("feature", feature)
        built.lightweightTag("v1.0", second)
        built.annotatedTag("v2.0", feature)
        built.close()
        // A symbolic tag is read at what it points at, as 0.1.0 read it.
        FileRepositoryBuilder().setGitDir(tmp.resolve("backend.git").toFile()).build().use {
            it.updateRef("refs/tags/latest").link("refs/tags/v1.0")
        }

        SourceRepository.open(tmp.resolve("backend.git")).use { repo ->
            assertEquals("backend", repo.name)
            assertEquals(listOf("feature", "main"), repo.branches().map { it.name })
            assertEquals(second, repo.resolveBranch("main"))
            assertNull(repo.resolveBranch("no-such-branch"))

            // Every tag peels to its commit; the annotated one does not leak its tag object.
            assertEquals(
                mapOf("latest" to second, "v1.0" to second, "v2.0" to feature),
                repo.tags().associate { it.name to it.target },
            )

            val commits = repo.readReachable(listOf(second, feature)).associateBy { it.id }
            assertEquals(setOf(first, second, feature), commits.keys)
            assertEquals(listOf(first), commits.getValue(second).parents)
            assertEquals("second\n\nwith a body line", commits.getValue(second).message)
            assertTrue(
                commits.getValue(second).time(OrderBy.COMMITTER) >
                    commits.getValue(first).time(OrderBy.COMMITTER),
            )
        }
    }

    @Test
    fun `a branch is looked up by its name, not read as a revision`() {
        val dir = tmp.resolve("backend.git")
        val main = TestRepoBuilder.create(dir).use { repo ->
            val a1 = repo.commit("a1")
            val a2 = repo.commit("a2", parents = listOf(a1))
            repo.branch("main", a2)
            repo.lightweightTag("v1", a1)
            a2
        }

        SourceRepository.open(dir).use { repo ->
            assertEquals(main, repo.resolveBranch("main"))
            // Each of these names an object to a revision parser, and no branch.
            for (name in listOf("main~1", "main^", "main@{0}", "../tags/v1", "../../HEAD")) {
                assertNull(repo.resolveBranch(name), name)
            }
        }
    }

    @Test
    fun `opens a repository that has a working tree`() {
        val built = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        val only = built.commit("only commit")
        built.branch("main", only)
        built.close()

        SourceRepository.open(tmp.resolve("webui")).use { repo ->
            assertEquals("webui", repo.name)
            assertEquals(only, repo.resolveBranch("main"))
            assertEquals(listOf("only commit"), repo.readReachable(listOf(only)).map { it.message })
        }
    }

    @Test
    fun `time follows the requested ident, author and committer read independently`() {
        val built = TestRepoBuilder.create(tmp.resolve("r.git"))
        // A rebased commit: written in 2020, landed in 2021.
        val rebased = built.commit(
            "rebased",
            at = java.time.Instant.parse("2021-06-01T00:00:00Z"),
            authorAt = java.time.Instant.parse("2020-06-01T00:00:00Z"),
        )
        built.branch("main", rebased)
        built.close()

        SourceRepository.open(tmp.resolve("r.git")).use { repo ->
            val commit = repo.readReachable(listOf(rebased)).single()
            assertEquals(
                java.time.Instant.parse("2020-06-01T00:00:00Z").epochSecond,
                commit.time(OrderBy.AUTHOR),
            )
            assertEquals(
                java.time.Instant.parse("2021-06-01T00:00:00Z").epochSecond,
                commit.time(OrderBy.COMMITTER),
            )
        }
    }

    @Test
    fun `a plain directory is not opened as the repository that encloses it`() {
        val built = TestRepoBuilder.create(tmp.resolve("outer"), bare = false)
        built.branch("main", built.commit("only commit"))
        built.close()
        val inside = tmp.resolve("outer/sub").createDirectories()

        // Without this, JGit's findGitDir walks up to outer/.git and opens that instead, so a
        // mistyped input silently becomes some other repository under the mistyped name.
        val refused = assertThrows<IllegalArgumentException> { SourceRepository.open(inside) }

        assertTrue(refused.message!!.contains("no git repository"), refused.message)
    }

    @Test
    fun `a redirecting variable in the environment cannot stand in for an input`() {
        val elsewhere = TestRepoBuilder.create(tmp.resolve("elsewhere.git"))
        elsewhere.branch("main", elsewhere.commit("only commit"))
        elsewhere.close()
        val webui = TestRepoBuilder.create(tmp.resolve("webui.git"))
        val b1 = webui.commit("b1")
        webui.branch("main", b1)
        webui.close()
        val api = TestRepoBuilder.create(tmp.resolve("api"), bare = false)
        val c1 = api.commit("c1")
        api.branch("main", c1)
        api.close()
        val plain = tmp.resolve("plain").createDirectories()

        withEnvironment(
            "GIT_DIR" to tmp.resolve("elsewhere.git").toString(),
            "GIT_OBJECT_DIRECTORY" to tmp.resolve("elsewhere.git/objects").toString(),
        ) {
            // The premise first: if JGit ever stops reading the environment through SystemReader,
            // everything below would pass without testing anything at all.
            assertEquals(
                tmp.resolve("elsewhere.git").toFile(),
                FileRepositoryBuilder().readEnvironment().gitDir,
                "the environment override no longer reaches JGit, so this test proves nothing",
            )

            // GIT_DIR alone used to satisfy the builder, so a directory that is no repository
            // opened as whatever the variable named — under the name of the path that was written.
            val refused = assertThrows<IllegalArgumentException> { SourceRepository.open(plain) }
            assertTrue(refused.message!!.contains("no git repository"), refused.message)

            // And a bare input is read from its own objects. For an input that is itself a git
            // directory, as this one is, setGitDir used to override GIT_DIR, but nothing overrode
            // the object-directory variables, so they reached even such an input.
            SourceRepository.open(tmp.resolve("webui.git")).use { repo ->
                assertEquals(b1, repo.resolveBranch("main"))
                assertEquals(listOf("b1"), repo.readReachable(listOf(b1)).map { it.message.trim() })
            }

            // An input named by its working tree most of all, the common case: GIT_DIR stood in for
            // every location that was not itself a git directory, so this one read elsewhere's refs.
            SourceRepository.open(tmp.resolve("api")).use { repo ->
                assertEquals(c1, repo.resolveBranch("main"))
            }
        }
    }

    /**
     * Runs [block] with [vars] added to the environment JGit reads, restoring the reader afterwards.
     * A test JVM cannot alter its own environment, so the reader is swapped instead — which is the
     * same thing from `readEnvironment()`'s point of view, and is asserted to be so above.
     */
    private fun <R> withEnvironment(vararg vars: Pair<String, String>, block: () -> R): R {
        val previous = SystemReader.getInstance()
        val overrides = vars.toMap()
        SystemReader.setInstance(object : SystemReader.Delegate(previous) {
            override fun getenv(variable: String): String? = overrides[variable] ?: super.getenv(variable)
        })
        return try {
            block()
        } finally {
            SystemReader.setInstance(previous)
        }
    }

    @Test
    fun `a working tree named by its own dot-git directory keeps the working tree's name`() {
        val built = TestRepoBuilder.create(tmp.resolve("webui"), bare = false)
        val only = built.commit("only commit")
        built.branch("main", only)
        built.close()

        SourceRepository.open(tmp.resolve("webui/.git")).use { repo ->
            // Naming it after the last segment would strip ".git" down to nothing at all.
            assertEquals("webui", repo.name)
            assertEquals(only, repo.resolveBranch("main"))
        }
    }

    @Test
    fun `a path is named by where it points, not by how it was written`() {
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer/.")))
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("sub/../outer")))
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer/.git")))
        // A bare repository still loses the suffix of its own directory name.
        assertEquals("outer", SourceRepository.defaultName(tmp.resolve("outer.git")))
        // "." is the natural way to name the repository the shell is sitting in. The expected value
        // comes first, as everywhere above; the inspection reads defaultName(Path.of(".")) as the
        // more constant side and would have the two swapped, which would make a failure message lie.
        @Suppress("KotlinMisorderedAssertEqualsArguments")
        assertEquals(
            Path.of("").toAbsolutePath().fileName.toString(),
            SourceRepository.defaultName(Path.of(".")),
        )
    }

    @Test
    fun `a shallow clone is refused, saying where its history stops and how to complete it`() {
        val built = TestRepoBuilder.create(tmp.resolve("backend.git"))
        val first = built.commit("first")
        val second = built.commit("second", parents = listOf(first))
        built.branch("main", second)
        built.close()
        // What `git clone --depth 1` leaves: the tip is kept and its parents are not. JGit reads
        // the boundary as a root, so nothing downstream of open would ever see a parent go missing.
        edit(tmp.resolve("backend.git")) { it.objectDatabase.shallowCommits = setOf(second) }

        val refused = assertThrows<IllegalArgumentException> {
            SourceRepository.open(tmp.resolve("backend.git"))
        }

        val message = refused.message!!
        assertTrue(message.startsWith("'backend' at ${tmp.resolve("backend.git")} "), message)
        assertTrue(message.contains("shallow clone: its history stops at 1 commit "), message)
        assertTrue(message.contains("fetch --unshallow"), message)
    }

    @Test
    fun `a partial clone is refused, by either mark git leaves on one`() {
        val marks = mapOf<String, (org.eclipse.jgit.lib.StoredConfig) -> Unit>(
            "extensions.partialClone" to
                { it.setString("extensions", null, "partialClone", "origin") },
            // Not origin: a clone made with `-o upstream` marks the remote of that name.
            "remote.upstream.promisor" to { it.setBoolean("remote", "upstream", "promisor", true) },
        )
        for ((mark, set) in marks) {
            val dir = tmp.resolve("$mark.git")
            TestRepoBuilder.create(dir).use { it.branch("main", it.commit("only commit")) }
            edit(dir) { repo -> set(repo.config); repo.config.save() }

            val refused =
                assertThrows<IllegalArgumentException>(mark) { SourceRepository.open(dir) }

            assertTrue(refused.message!!.contains("is a partial clone"), refused.message)
            assertTrue(refused.message!!.contains("without --filter"), refused.message)
        }
    }

    @Test
    fun `a remote that is no promisor leaves a repository complete, and it opens`() {
        val dir = tmp.resolve("webui.git")
        TestRepoBuilder.create(dir).use { it.branch("main", it.commit("only commit")) }
        edit(dir) { repo ->
            repo.config.setString("remote", "origin", "url", "https://example.com/webui.git")
            repo.config.setBoolean("remote", "upstream", "promisor", false)
            repo.config.save()
        }

        SourceRepository.open(dir).use { assertEquals("webui", it.name) }
    }

    /** Applies [change] to the repository at [dir] through a handle of its own, closed afterwards. */
    private fun edit(dir: Path, change: (org.eclipse.jgit.lib.Repository) -> Unit) =
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(dir.toFile()).build()
            .use(change)
}
