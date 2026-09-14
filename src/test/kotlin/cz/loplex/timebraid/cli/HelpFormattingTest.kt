package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two help outputs: what `-h` leaves out, and how a flag that can be turned off is named.
 *
 * The wording is not asserted here — `.github/scripts/check-help.py` keeps `doc/usage.md` equal to
 * whatever the program prints, so a reworded entry is a diff in that file rather than a failure
 * here. What is asserted is the shape, and above all that nothing written in the source goes
 * missing on the way out: a merged row shows one name and drops the other's entry, so a sentence
 * put on the wrong one of a pair would never be seen again.
 */
class HelpFormattingTest {

    private fun help(vararg args: String): String =
        MergeCommand().test(args.toList()).also { assertEquals(0, it.statusCode, it.output) }.output

    @Test
    fun `-hh and --help print the same thing`() {
        assertEquals(help("--help"), help("-hh"))
    }

    @Test
    fun `-hh is not two -h, and -h is shorter than the full help`() {
        val terse = help("-h")
        assertTrue(terse.isNotEmpty(), "-h printed nothing")
        assertNotEquals(help("-hh"), terse, "-h printed what -hh prints")
        assertTrue(
            terse.lines().size < help("--help").lines().size,
            "-h is no shorter than --help",
        )
    }

    @Test
    fun `-h holds every option the full help holds`() {
        // The name column: the names and the metavar, up to the two-space gap before the
        // description, or the end of a line the description was pushed off. A name anywhere else on
        // a line is not an entry — an option is named inside another's prose, and a group's own
        // text is indented to this same column and can open with one, which is what the two-space
        // gap tells apart.
        val column = Regex("^ {2}(-\\S+(?:, -\\S+)*)(?:\\s{2,}|\$)", RegexOption.MULTILINE)
        fun terms(help: String) = column.findAll(help).map { it.groupValues[1] }.toSet()

        val terse = terms(help("-h"))
        assertTrue(terse.isNotEmpty(), "no option rows were found in -h")
        assertEquals(terms(help("--help")), terse, "-h and --help do not list the same options")
    }

    @Test
    fun `-h keeps the definition a group's entries are read against`() {
        // Its opening and its end, read across the wrap: a definition cut after its first clause
        // still opens the same way, and says nothing of what <input>:: and ^ mean.
        val shown = help("-h").replace(Regex("\\s+"), " ")
        assertTrue(
            "A <ref-pattern> is" in shown && "refuse a ^ alone." in shown,
            "-h dropped the <ref-pattern> definition its own entries depend on",
        )
    }

    @Test
    fun `a flag that can be turned off is one row`() {
        for (help in listOf(help("-h"), help("--help"))) {
            for (name in listOf("bare", "provenance", "progress", "ascii")) {
                assertTrue("--[no-]$name" in help, "--[no-]$name is not rendered as one row")
                assertFalse(
                    Regex("^ {2}--no-$name\\b", RegexOption.MULTILINE).containsMatchIn(help),
                    "--no-$name still has an entry of its own",
                )
            }
        }
    }

    @Test
    fun `the negative of a merged pair carries no help of its own`() {
        // A merged row shows the positive's help and drops the negative's entry whole. Help
        // written on the negative would vanish, so there must not be any.
        // allHelpParams needs a context, which only parsing builds. --version establishes one
        // without running a merge.
        val command = MergeCommand().also { it.test("--version") }
        val negatives = command.allHelpParams()
            .filterIsInstance<com.github.ajalt.clikt.output.HelpFormatter.ParameterHelp.Option>()
            .filter { it.names.any { name -> name.startsWith("--no-") } }
        assertTrue(negatives.isNotEmpty(), "the pairs this guards are gone; so is the guard")
        for (option in negatives) {
            assertEquals(
                "",
                option.help,
                "${option.names} carries help that the merged row would discard",
            )
        }
    }

    @Test
    fun `the full help still gives every default`() {
        val full = help("--help")
        for (default in listOf("Default: bare.", "Default: committer.", "Default: none.")) {
            assertTrue(default in full, "--help lost \"$default\"")
        }
        assertFalse("Default:" in help("-h"), "-h is meant to drop the defaults")
    }
}
