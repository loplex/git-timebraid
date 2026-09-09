package cz.loplex.timebraid.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

class RepositoryScanTest {

    @TempDir
    lateinit var tmp: Path

    /** A repository at [at], bare unless a working tree is asked for, with one commit in it. */
    private fun repo(at: String, bare: Boolean = true) {
        val dir = tmp.resolve(at)
        dir.parent?.createDirectories()
        TestRepoBuilder.create(dir, bare = bare).use { it.branch("main", it.commit("c1")) }
    }

    private fun scan(base: String = ""): List<Pair<String?, String>> =
        RepositoryScan.scan(tmp.resolve(base)).map { it.subdir to it.name }

    @Test
    fun `every repository under the base becomes an input placed where it sits`() {
        repo("libs/backend")
        repo("apps/webui")

        assertEquals(listOf("apps/webui" to "webui", "libs/backend" to "backend"), scan())
    }

    @Test
    fun `a bare repository loses its dot-git suffix, in the name and in the destination alike`() {
        repo("libs/backend.git")

        assertEquals(listOf("libs/backend" to "backend"), scan())
    }

    @Test
    fun `a working tree is found by its dot-git, and named after the tree`() {
        repo("libs/backend", bare = false)

        assertEquals(listOf("libs/backend" to "backend"), scan())
    }

    @Test
    fun `the base directory itself lands at the output root when it is a repository`() {
        TestRepoBuilder.create(tmp.resolve("platform"), bare = false).use {
            it.branch("main", it.commit("p1"))
        }
        repo("platform/libs/backend")

        assertEquals(
            listOf(null to "platform", "libs/backend" to "backend"),
            scan("platform"),
        )
    }

    @Test
    fun `a repository is not descended into, so what is nested inside one stays its own business`() {
        repo("libs", bare = false)
        repo("libs/vendored")

        assertEquals(listOf("libs" to "libs"), scan())
    }

    @Test
    fun `dot-names are skipped, so a cache or a tool's directory is never walked`() {
        repo(".cache/backend")
        repo("apps/webui")

        assertEquals(listOf("apps/webui" to "webui"), scan())
    }

    @Test
    fun `a symlinked directory is not followed`() {
        repo("apps/webui")
        Files.createSymbolicLink(tmp.resolve("mirror"), tmp.resolve("apps"))

        assertEquals(listOf("apps/webui" to "webui"), scan())
    }

    @Test
    fun `the order is the tree's own, so two runs produce the same layout`() {
        for (name in listOf("zeta", "alpha", "mid/inner", "mid/aaa")) repo(name)

        assertEquals(
            listOf(
                "alpha" to "alpha",
                "mid/aaa" to "aaa",
                "mid/inner" to "inner",
                "zeta" to "zeta",
            ),
            scan(),
        )
    }

    @Test
    fun `a base with no repository under it is refused rather than merged as nothing`() {
        tmp.resolve("empty/deeper").createDirectories()

        val error = assertThrows<IllegalArgumentException> { scan() }
        assertTrue(error.message!!.contains("no git repository"), error.message)
    }

    @Test
    fun `a base that is not a directory is refused`() {
        Files.writeString(tmp.resolve("a-file"), "x")

        val error = assertThrows<IllegalArgumentException> { scan("a-file") }
        assertTrue(error.message!!.contains("not a directory"), error.message)
    }
}
