package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The skeleton check: the command answers `--help` and `--version`. */
class MergeCommandTest {

    @Test
    fun `--help prints usage and does not signal an error`() {
        val command = MergeCommand()

        val help = assertThrows<PrintHelpMessage> { command.parse(arrayOf("--help")) }

        assertTrue(!help.error)
        val formatted = command.getFormattedHelp(help)
        assertTrue(formatted != null && formatted.contains("Usage"))
    }

    @Test
    fun `--version prints the program name and version`() {
        val version = assertThrows<PrintMessage> { MergeCommand().parse(arrayOf("--version")) }

        assertTrue(version.message!!.contains("git-timebraid"))
    }
}
