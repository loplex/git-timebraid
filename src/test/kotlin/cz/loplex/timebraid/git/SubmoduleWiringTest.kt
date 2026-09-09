package cz.loplex.timebraid.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The text transform on its own, without a repository in the way.
 *
 * Every case here is a `.gitmodules` that could plausibly be sitting in someone's history, checked
 * against the one property that matters: after the transform, a `path` names where the gitlink
 * actually is in the output, and a section name is one no other input can claim.
 */
class SubmoduleWiringTest {

    /**
     * The output names, which nothing but these assertions asks for: [SubmoduleWiring.merge] reports
     * a collision by walking the sections itself, so the class carries no name set of its own.
     */
    private val RewiredGitmodules.names: Set<String>
        get() = sections.mapTo(LinkedHashSet()) { it.name }

    private fun rewire(text: String, subdir: String?, repo: String = "A") =
        SubmoduleWiring.rewire(text, subdir, repo) { "$repo/abc123" }

    private fun merge(vararg parts: RewiredGitmodules) =
        SubmoduleWiring.merge(parts.toList()) { "abc123" }

    @Test
    fun `two inputs describing one submodule name are refused, naming it`() {
        // The arrangement the class KDoc names: `libs/a` with a section `b/x` against `libs/a/b`
        // with a section `x`. Both prefix to `libs/a/b/x`. --splice lets the pair arrive, and so
        // does the repository at the output root; everywhere else no destination contains another,
        // which is what makes `<subdir>/<name>` unique by construction.
        val outer = """
            [submodule "b/x"]
            	path = b/x
            	url = https://example.com/x.git
        """.trimIndent()
        val inner = """
            [submodule "x"]
            	path = x
            	url = https://example.com/x.git
        """.trimIndent()

        val refused = assertThrows<IllegalArgumentException> {
            merge(rewire(outer, subdir = "libs/a", repo = "A"), rewire(inner, subdir = "libs/a/b", repo = "B"))
        }

        assertTrue(
            refused.message!!.contains("two inputs both describe a submodule named 'libs/a/b/x'"),
            refused.message,
        )
    }

    @Test
    fun `an input in a subdirectory has both its path and its section name prefixed`() {
        val rewired = rewire(
            """
            [submodule "vendor/lib"]
            	path = vendor/lib
            	url = https://example.com/lib.git
            """.trimIndent(),
            subdir = "A",
        )

        assertEquals(setOf("A/vendor/lib"), rewired.names)
        assertEquals(
            """
            [submodule "A/vendor/lib"]
            	path = A/vendor/lib
            	url = https://example.com/lib.git
            """.trimIndent() + "\n",
            rewired.text,
        )
    }

    @Test
    fun `the root repository's paths are already the output's paths, so nothing is prefixed`() {
        val text = """
            [submodule "vendor/lib"]
            	path = vendor/lib
            	url = https://example.com/lib.git
        """.trimIndent() + "\n"

        val rewired = rewire(text, subdir = null)

        assertEquals(setOf("vendor/lib"), rewired.names)
        assertEquals(text, rewired.text)
    }

    @Test
    fun `keys that are not a path are carried over untouched`() {
        val rewired = rewire(
            """
            [submodule "lib"]
            	path = lib
            	url = ../lib.git
            	branch = release
            	update = rebase
            	ignore = dirty
            	shallow = true
            """.trimIndent(),
            subdir = "A",
        )

        // The url in particular: a relative one still resolves against the *output's* remote, which
        // is a limitation of the merge and not something this transform may paper over.
        assertTrue(rewired.text.contains("url = ../lib.git"), rewired.text)
        assertTrue(rewired.text.contains("branch = release"), rewired.text)
        assertTrue(rewired.text.contains("update = rebase"), rewired.text)
        assertTrue(rewired.text.contains("ignore = dirty"), rewired.text)
        assertTrue(rewired.text.contains("shallow = true"), rewired.text)
        assertTrue(rewired.text.contains("path = A/lib"), rewired.text)
    }

    @Test
    fun `a name that git does not derive from the path is prefixed all the same`() {
        // `git submodule add --name` decouples the two, and it is the name — not the path — that
        // decides which directory under .git/modules a populated submodule gets.
        val rewired = rewire(
            """
            [submodule "the-library"]
            	path = vendor/lib
            	url = https://example.com/lib.git
            """.trimIndent(),
            subdir = "A",
        )

        assertEquals(setOf("A/the-library"), rewired.names)
        assertTrue(rewired.text.contains("[submodule \"A/the-library\"]"), rewired.text)
        assertTrue(rewired.text.contains("path = A/vendor/lib"), rewired.text)
    }

    @Test
    fun `a section with no path keeps the path it did not have`() {
        val rewired = rewire(
            """
            [submodule "orphan"]
            	url = https://example.com/lib.git
            """.trimIndent(),
            subdir = "A",
        )

        assertEquals(setOf("A/orphan"), rewired.names)
        assertTrue(!rewired.text.contains("path"), rewired.text)
    }

    @Test
    fun `a gitmodules holding no submodule section contributes nothing`() {
        val rewired = rewire("# a comment and nothing else\n", subdir = "A")

        assertEquals(emptySet<String>(), rewired.names)
        assertNull(merge(rewired))
    }

    @Test
    fun `nothing at all yields no file, so a submodule-free braid keeps the tree it had`() {
        assertNull(merge())
        assertNull(merge(SubmoduleWiring.NOTHING, SubmoduleWiring.NOTHING))
    }

    @Test
    fun `two inputs merge into one file, each section under its own name`() {
        val a = rewire("[submodule \"lib\"]\n\tpath = lib\n\turl = a\n", subdir = "A")
        val b = rewire("[submodule \"lib\"]\n\tpath = lib\n\turl = b\n", subdir = "B", repo = "B")

        assertEquals(
            """
            [submodule "A/lib"]
            	path = A/lib
            	url = a
            [submodule "B/lib"]
            	path = B/lib
            	url = b
            """.trimIndent() + "\n",
            merge(a, b),
        )
    }

    @Test
    fun `two inputs claiming one submodule name is a named error`() {
        // Reachable across the root repository, whose names are not prefixed, under --splice, and
        // between two blank names. Everywhere else a subdirectory input cannot collide, because no
        // destination lies inside another, which keeps every `<subdir>/<name>` unique.
        val root = rewire("[submodule \"A/lib\"]\n\tpath = A/lib\n\turl = r\n", subdir = null, repo = "root")
        val a = rewire("[submodule \"lib\"]\n\tpath = lib\n\turl = a\n", subdir = "A")

        val error = assertThrows<IllegalArgumentException> { merge(root, a) }
        assertTrue(error.message!!.contains("'A/lib'"), error.message)
        assertTrue(error.message!!.contains("abc123"), error.message)
    }

    @Test
    fun `two blank submodule names are refused without advice a blank name cannot use`() {
        val a = rewire("[submodule \"\"]\n\tpath = lib\n\turl = a\n", subdir = "A")
        val b = rewire("[submodule \"\"]\n\tpath = lib\n\turl = b\n", subdir = "B", repo = "B")

        val error = assertThrows<IllegalArgumentException> { merge(a, b) }
        assertTrue(error.message!!.contains("a blank name"), error.message)
        assertTrue(error.message!!.contains("renaming that section"), error.message)
        assertTrue(!error.message!!.contains("another subdirectory"), error.message)

        // A name that is not blank still gets the advice that does part it.
        val root = rewire("[submodule \"A/lib\"]\n\tpath = A/lib\n\turl = r\n", subdir = null, repo = "root")
        val lib = rewire("[submodule \"lib\"]\n\tpath = lib\n", subdir = "A")
        val named = assertThrows<IllegalArgumentException> { merge(root, lib) }
        assertTrue(named.message!!.contains("another subdirectory"), named.message)
    }

    @Test
    fun `a gitmodules that is not git config is an error naming the input and the commit`() {
        val error = assertThrows<IllegalArgumentException> {
            rewire("[submodule \"lib\"\n\tpath = lib\n", subdir = "A")
        }
        assertTrue(error.message!!.contains(".gitmodules"), error.message)
        assertTrue(error.message!!.contains("'A'"), error.message)
        assertTrue(error.message!!.contains("A/abc123"), error.message)
    }
}
