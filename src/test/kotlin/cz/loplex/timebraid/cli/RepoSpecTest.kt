package cz.loplex.timebraid.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The `<repo>` grammar read and written back: [RepoSpec.format] is [parseRepoSpec]'s inverse. */
class RepoSpecTest {

    /** Every part the parser reads, in one comparable value. */
    private fun parts(spec: RepoSpec) = listOf(spec.location, spec.split, spec.subdir, spec.given, spec.name)

    private val arguments = listOf(
        "backend",
        "/srv/git/backend.git",
        "backend.git::",
        "backend.git::libs/backend",
        "backend.git::=core",
        "backend.git::libs/a=libs-a",
        "odd::name::=oddname",
        "a=b/c",
        "https://example.com/org/webui.git::apps/webui",
        "https://[fe80::1]/repo.git::",
        "git@example.com:org/repo.git::=repo",
    )

    @Test
    fun `an argument formats back to itself`() {
        for (raw in arguments) assertEquals(raw, parseRepoSpec(raw).format(), raw)
    }

    @Test
    fun `a formatted spec parses back to the same parts`() {
        for (raw in arguments) {
            val spec = parseRepoSpec(raw)
            assertEquals(parts(spec), parts(parseRepoSpec(spec.format())), raw)
        }
    }

    @Test
    fun `a remedy keeps every part of the argument it does not change`() {
        val remedy = InputRemedy(parseRepoSpec("backend.git::libs/a"))

        assertEquals("'backend.git::libs/a=<name>'", remedy.named())
        assertEquals("'odd::name::=<name>'", InputRemedy(parseRepoSpec("odd::name::=x")).named())
        assertEquals("'backend.git::<subdir>'", InputRemedy("backend.git").moved())
        // Moved, an input keeps its name, given or derived: without it, the new subdirectory would name it.
        assertEquals("'backend.git::<subdir>=core'", InputRemedy(parseRepoSpec("backend.git::libs/a=core")).moved())
        assertEquals("'backend.git::<subdir>=a'", InputRemedy(parseRepoSpec("backend.git::libs/a")).moved())
    }
}
