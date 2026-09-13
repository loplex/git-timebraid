package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How a flag that can be turned off is named in the help.
 *
 * The wording is not asserted here — `.github/scripts/check-help.py` keeps `doc/usage.md` equal to
 * whatever the program prints, so a reworded entry is a diff in that file rather than a failure
 * here. What is asserted is that nothing written in the source goes missing on the way out: a
 * merged row shows one name and drops the other's entry, so a sentence put on the wrong one of a
 * pair would never be seen again.
 */
class HelpFormattingTest {

    private fun help(vararg args: String): String =
        MergeCommand().test(args.toList()).also { assertEquals(0, it.statusCode, it.output) }.output

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
}
