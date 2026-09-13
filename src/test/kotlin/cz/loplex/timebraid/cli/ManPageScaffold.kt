package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter.ParameterHelp

private fun roff(text: String): String = text
    .replace("\\", "\\e")
    .replace("-", "\\-")

private fun bold(text: String): String = "\\fB$text\\fR"

private fun italic(text: String): String = "\\fI$text\\fR"

/**
 * A help string as roff requests: a blank line separates paragraphs, [BR] breaks a line inside one.
 *
 * A line that would begin with a control character is prefixed with `\&`, which occupies no width
 * and stops roff reading the rest of the line as a request it does not have.
 */
private fun body(help: String): String = help
    .split("\n\n")
    .joinToString("\n.sp\n") { paragraph ->
        paragraph.split(BR).joinToString("\n.br\n") { line ->
            roff(line.trim()).let { if (it.startsWith(".") || it.startsWith("'")) "\\&$it" else it }
        }
    }

/** The row naming an option: every name bold, the metavar italic behind an `=`. */
private fun signature(option: ParameterHelp.Option): String {
    val metavar = option.metavar?.let { "=" + italic(roff(it)) } ?: ""
    // In the order --help prints them, which check-man.py compares with: clikt's help formatter
    // puts every name not starting `--` first, and keeps the declared order otherwise.
    val names = option.names.sortedBy { it.startsWith("--") }
    return names.joinToString(", ") { bold(roff(it)) } + metavar
}

/**
 * The formatter clikt is handed, so that the parameters arrive the same way `--help` gets them —
 * already merged into the groups the command declares, and with the help strings resolved against
 * the context.
 */
private class ManPageFormatter(private val context: Context) : HelpFormatter {
    override fun formatHelp(
        error: UsageError?,
        prolog: String,
        epilog: String,
        parameters: List<ParameterHelp>,
        programName: String,
    ): String = buildString {
        appendLine(".SH OPTIONS")
        // Options arrive flat, each carrying the name of the group it was declared in, and the
        // Group entries carry those groups' own help. Both are in declaration order.
        val groups = parameters.filterIsInstance<ParameterHelp.Group>().associateBy { it.name }
        val options = parameters.filterIsInstance<ParameterHelp.Option>()
        for ((group, entries) in options.groupBy { it.groupName }) {
            group?.let { name ->
                appendLine(".SS $name")
                groups[name]?.help?.takeIf(String::isNotBlank)?.let { appendLine(body(it)) }
            }
            // The same merge the terminal help does, so a flag that can be turned off is one row
            // named `--[no-]x` here too, which is also how the page is checked.
            for (option in mergeNegations(entries)) {
                appendLine(".TP")
                appendLine(signature(option))
                appendLine(body(option.help))
            }
        }
    }
}

/**
 * Writes the OPTIONS section of `src/main/man/git-timebraid.1` as roff, to be edited by hand.
 *
 * It is not what keeps that page true — `.github/scripts/check-man.py` is, by comparing each
 * entry's signature line with the name column `--help` prints. This exists for the other half of
 * the job: filling a page in, or refilling it after a release reworks the option set, without
 * retyping thirty-odd entries and their escaping. Its output is a draft.
 *
 * It lives in the test sources rather than in `src/main` deliberately. Generating the section
 * outright was considered and turned down, because a page whose OPTIONS is build output can only
 * ever say what the help strings say, and a manual page should say more: the reader is there to
 * understand an option, not to be reminded of it. Here it is compiled but not shipped, so a clikt
 * release that moves [HelpFormatter] breaks the build and says so, instead of leaving a file that
 * quietly stopped working.
 *
 * What its output still needs by hand, each a place where a terminal and a manual page part ways:
 *
 *   * **the signature row.** A metavar clikt derived from a type arrives without the angle
 *     brackets the help adds when it renders;
 *   * **every hard break.** A [BR] becomes `.br` here, which stops `man` reflowing the line. Faithful
 *     to `--help` and wrong for a page that is laid out to the reader's own width;
 *   * **verbatim values.** A default like the provenance trailer, left in flowing text, is justified
 *     and hyphenated into something nobody can copy back out. Those belong in `.EX`/`.EE`;
 *   * **cross-references.** An option named inside another's prose is bold in a manual page. Only a
 *     person knows which words those are;
 *   * **the ungrouped options.** `-h`, `-hh` and `--version` arrive first, carrying no group name;
 *     the help renders them last;
 *   * **the arguments.** [ParameterHelp.Argument] entries are skipped entirely, the `<repo>`
 *     argument being described in the page's DESCRIPTION instead.
 *
 * Run it, and paste what it prints:
 *
 *     mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *         -Dexec.mainClass=cz.loplex.timebraid.cli.ManPageScaffoldKt
 *
 * `exec.mainClass` and not `main.class`: the second is what the manifest of the jar and of the
 * shaded jar is built with, and pom.xml keeps the two apart so that running this cannot become the
 * entry point of anything that gets packaged.
 */
fun main() {
    val command = MergeCommand()
    command.context { helpFormatter = { ManPageFormatter(it) } }
    print(command.getFormattedHelp(null))
}
