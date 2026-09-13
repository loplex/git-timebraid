package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.HelpFormatter.ParameterHelp
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.Widget

/** How a flag that can be turned off is written once both of its names are on one row. */
private const val NEGATABLE = "--[no-]"

/** The prefix clikt gives a flag's secondary name, and the one this tool spells its pairs with. */
private const val NEGATIVE = "--no-"

/**
 * The help clikt renders, with `--[no-]bare` in place of `--bare / --no-bare`.
 *
 * That is how git writes a flag that can be turned off — `git fetch -h` spells every such flag
 * that way and none the other — and it is a line shorter each time, since the two names and the
 * two sentences behind them become one of each.
 *
 * Two shapes arrive here and both leave as that one row:
 *
 *   * a flag with a secondary name, which clikt already renders as one entry joined by a slash;
 *   * two separate flags, `--progress`/`--no-progress` and `--ascii`/`--no-ascii`, which are two
 *     options because the default is neither of them — a run decides for itself, and each of the
 *     pair overrides that decision in one direction.
 *
 * Nothing about parsing changes, including that giving both of a pair is still an error. Only the
 * row is merged, and the negative's own entry is dropped once the positive has taken its name. So
 * the positive carries the help for both and the negative carries none: help written on a negative
 * would be discarded here without a word, which is what `HelpFormattingTest` stops.
 */
open class TimebraidHelpFormatter(context: Context) : MordantHelpFormatter(context) {
    override fun renderOptionGroup(
        help: String?,
        parameters: List<ParameterHelp.Option>,
    ): Widget = super.renderOptionGroup(help, mergeNegations(parameters))
}

/**
 * The same help with every entry cut to its first line: what `-h` prints.
 *
 * Every option is still there, which is the point — a terse list that leaves options out would
 * make them undiscoverable, and `git fetch -h` leaves none out either. What goes is the qualifiers
 * and the defaults under each first line, which is exactly the split the help strings are already
 * written to: one information per line, the first saying what the option does.
 *
 * A group's own help keeps its first paragraph rather than its first line, because that paragraph
 * is where `<ref-pattern>` is defined and the entries below it are unreadable without it. The
 * prolog is cut to its first paragraph as well.
 */
class TerseHelpFormatter(context: Context) : TimebraidHelpFormatter(context) {
    override fun collectHelpParts(
        error: UsageError?,
        prolog: String,
        epilog: String,
        parameters: List<ParameterHelp>,
        programName: String,
    ): List<Widget> = super.collectHelpParts(
        error,
        firstParagraph(prolog),
        epilog,
        parameters.map(::shortened),
        programName,
    )
}

/**
 * One row per flag that can be turned off, named `--[no-]x`.
 *
 * The negative of a pair is found among the section's own option names as well as among the
 * positive's secondary names, which is what lets two separate flags merge like one flag with two.
 * It is computed rather than listed, so a pair added later needs nothing here.
 */
internal fun mergeNegations(options: List<ParameterHelp.Option>): List<ParameterHelp.Option> {
    val names = options.flatMapTo(mutableSetOf()) { it.names }
    // The negatives that have a positive in this same section, and so are about to be absorbed by
    // one. Collected up front rather than as the list is walked, so the order the two were
    // declared in does not decide whether they merge.
    val absorbed = names.mapNotNullTo(mutableSetOf()) { negationOf(it) }.filterTo(mutableSetOf()) {
        it in names
    }
    return options.mapNotNull { option ->
        if (option.names.any { it in absorbed }) return@mapNotNull null
        val positive = option.names.firstOrNull { name ->
            negationOf(name)?.let { it in option.secondaryNames || it in names } == true
        } ?: return@mapNotNull option
        option.copy(
            names = option.names.mapTo(LinkedHashSet()) {
                if (it == positive) NEGATABLE + it.removePrefix("--") else it
            },
            secondaryNames = emptySet(),
        )
    }
}

/** What clikt would call the off switch of [name], or null where [name] is already one. */
private fun negationOf(name: String): String? = when {
    !name.startsWith("--") || name.startsWith(NEGATIVE) -> null
    else -> NEGATIVE + name.removePrefix("--")
}

private fun shortened(parameter: ParameterHelp): ParameterHelp = when (parameter) {
    is ParameterHelp.Option -> parameter.copy(help = firstLine(parameter.help))
    is ParameterHelp.Argument -> parameter.copy(help = firstLine(parameter.help))
    is ParameterHelp.Group -> parameter.copy(help = firstParagraph(parameter.help))
    else -> parameter
}

private fun firstParagraph(text: String): String = text.substringBefore("\n\n")

/** The first line of a help string, [BR] being what puts the rest on lines of their own. */
private fun firstLine(text: String): String = firstParagraph(text).substringBefore(BR)
