package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.ArgumentTransformContext
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.transformAll
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import cz.loplex.timebraid.MergeRequest
import cz.loplex.timebraid.MergeResult
import cz.loplex.timebraid.LocalPlace
import cz.loplex.timebraid.MergeRunner
import cz.loplex.timebraid.ResolvedInput
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.GitCommandException
import cz.loplex.timebraid.git.MainlineRequest
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.RepositoryScan
import cz.loplex.timebraid.git.ScopedPatterns
import cz.loplex.timebraid.git.SharedScope
import cz.loplex.timebraid.git.branchPatterns
import cz.loplex.timebraid.git.ScannedRepository
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.WriteOptions
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.util.FS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A hard line break inside a single help paragraph.
 *
 * Clikt hands a help string to mordant as plain text: a lone `\n` collapses into a space, and
 * U+0085 (NEL) breaks a line without also opening a paragraph (U+2028 does too, and nothing here
 * uses it). It is what puts an option's default on a line of its own, where the whole column can
 * be skimmed for defaults, at no cost in blank lines. Mordant replaces it with a newline, so the
 * character reaches no console — which is why `MessageCharsetTest` exempts this one and nothing
 * else.
 */
internal const val BR = "\u0085"

/*
 * The groups below exist for `--help`, which clikt renders one section per group. A single flat
 * list makes the required ones look like the rare ones; grouped, a reader meets them in the
 * order the decisions arise. Nothing about parsing changes: a grouped option is spelled and given
 * exactly as it was.
 *
 * Each help string carries one information per line, with `BR` between them, because the
 * formatter reflows anything else into a block a reader has to read rather than scan — a single
 * newline is dropped, and a `-` list renders its bullets without indenting what follows them. What
 * an option does, and separately what happens when it is not given, are two such informations, so
 * every default sits on its own line and the whole column can be skimmed for them.
 *
 * What an option *means*, and what it costs, stays in `doc/usage.md`, which the epilog points at. A
 * help entry that grows past a few lines is almost always a copy of that page, and the copy is the
 * one that goes stale.
 *
 * A group carries help of its own, which clikt sets above its option list, and that is where syntax
 * more than one of its options shares is written. A metavar is what binds the two: `<ref-pattern>`
 * is spelled out once in the group, and an option that takes one says so in its own term rather
 * than listing the fields again. Spelling it out per option is what made this list long, and a
 * sentence repeated four times stays true in all four copies only by luck.
 */

private class OutputRepoOptions : OptionGroup(
    name = "Where the result is written",
) {
    val output by option("-o", "--output").path()
        .help(
            "Output repository." + BR +
                "Must not exist, or must be an empty directory; --force also takes a non-empty one."
        )

    val force by option("--force").flag()
        .help(
            "Write into a non-empty output directory instead of refusing it." + BR +
                "Whatever it already holds may be written over."
        )
}

private class PlacementOptions : OptionGroup(
    name = "Finding the inputs, and placing their content",
) {
    val scan by option("--scan").path()
        .help(
            "Take the layout from this directory." + BR +
                "Every repository under it becomes an input, placed in the output where it sits " +
                "on disk." + BR +
                "'::<subdir>=<name>', with no location, renames the one it found at <subdir>."
        )

    val rootRepo by option("--root-repo")
        .help("Name of the repository whose content lands at the output root.")

    val splice by option("--splice").flag()
        .help(
            "Allow one input's subdirectory to lie inside another's, splicing the two into one " +
                "directory." + BR + "Without it, such a pair is refused."
        )

    val dissolveSubmodules by option("--dissolve-submodules").flag()
        .help(
            "Where an input lands exactly on a gitlink, replace that submodule with the " +
                "input's own content." + BR + "Its .gitmodules section is dropped with it."
        )
}

private class HistoryOptions : OptionGroup(
    name = "Which history is read, and how it interleaves",
) {
    val mainlineBranch by option("--mainline-branch", metavar = "<branch>").multiple()
        .help(
            "Branch treated as the mainline in every input (repeatable)." + BR +
                "Prefix with <input>:: to give one input its own; one quoted argument may hold " +
                "several, separated by spaces." + BR +
                "An unscoped value covers the rest and names the output's branch." + BR +
                "Default: the first of " +
                "${CommitGraphReader.MAINLINE_CANDIDATES.joinToString("/")} present in all."
        )

    val orderBy by option("--order-by")
        .choice("author" to OrderBy.AUTHOR, "committer" to OrderBy.COMMITTER)
        .default(OrderBy.COMMITTER)
        .help("Timestamp used to interleave the strands." + BR + "Default: committer.")
}

private class RefOptions : OptionGroup(
    name = "Which refs a pattern speaks for",
    help = "A <ref-pattern> is [<input>::][^]<refspec>, matched against full ref names. " +
        "<input>:: narrows it to one input, and a ^ in front subtracts instead of selecting. With " +
        "nothing selected, -b and --ref subtract from every branch and tag, while --label-ref and " +
        "--interleave-ref, which take no ref unless asked, refuse a ^ alone.\n\n" +
        "The <refspec> is git's, <glob>[:<destination>]: -b, --ref and --label-ref take the " +
        "destination, saying where the matches land, as 'refs/heads/*:refs/tags/' does.\n\n" +
        "Every option here is repeatable, and one quoted argument may hold several patterns, " +
        "separated by spaces.",
) {
    val branches by option("-b", "--branch", metavar = "<branch>").multiple()
        .help(
            "Carry over these branches, by short name, or with ^ leave them out." + BR +
                "Shorthand for --ref refs/heads/<branch>, so naming one without a ^ leaves out " +
                "every ref not named, tags included."
        )

    val refs by option("--ref", metavar = "<ref-pattern>").multiple()
        .help(
            "Carry over the refs matching this pattern, branches and tags alike, or with ^ leave " +
                "them out." + BR +
                "Default: every branch and tag, each in the namespace it came from."
        )

    val labelRefs by option("--label-ref", metavar = "<ref-pattern>").multiple()
        .help(
            "Also recreate the refs matching this pattern whose target the run already holds." + BR +
                "Adding one cannot change a commit the run writes; a selection can." + BR +
                "Default: none."
        )

    val interleaveRefs by option("--interleave-ref", metavar = "<ref-pattern>").multiple()
        .help(
            "Let this ref's commits delay a mainline merge that merges them in." + BR +
                "'refs/heads/* ^refs/heads/main' is every side branch; a bare '*' opts in " +
                "every tag too." + BR +
                "Default: none."
        )
}

private class OutputContentOptions : OptionGroup(
    name = "What the output repository holds",
    help = "--tag-prefix, --branch-prefix and --notes-prefix qualify a ref name of the output: " +
        "{repo} and {subdir} are substituted, an empty value qualifies nothing, and two inputs then meeting " +
        "on one name is refused rather than resolved.",
) {
    val bare by option("--bare").flag("--no-bare", default = true)
        .help(
            "Write a bare output repository." + BR +
                "--no-bare checks out a working tree instead." + BR +
                "Default: bare."
        )

    val keepRemotes by option("--keep-remotes").flag()
        .help(
            "Add each input as a remote." + BR +
                "Every ref it carried over lands under refs/remotes/<name>/*, at the original " +
                    "commits, and so does each input's mainline whether the selection took it or " +
                    "not. Notes are written under refs/notes/ and are not mirrored."
        )

    val tagPrefix by option("--tag-prefix").default(WriteOptions().tagPrefix)
        .help(
            "Prefix prepended to every recreated tag." + BR +
                "Default: \"" + WriteOptions().tagPrefix + "\""
        )

    val branchPrefix by option("--branch-prefix").default(WriteOptions().branchPrefix)
        .help(
            "Prefix prepended to every recreated branch." + BR +
                "Default: \"" + WriteOptions().branchPrefix + "\""
        )

    val subjectPrefix by option("--subject-prefix").default(WriteOptions().subjectPrefix)
        .help(
            "Prefix prepended to every commit subject." + BR +
                "{repo} and {subdir} are substituted." + BR +
                "Default: \"" + WriteOptions().subjectPrefix + "\""
        )

    val notes by option("--notes").flag()
        .help(
            "Carry over every input's refs/notes/, rekeyed onto the commits this run writes." +
                BR + "Default: notes are not read."
        )

    val notesPrefix by option("--notes-prefix").default(WriteOptions().notesPrefix)
        .help(
            "Prefix prepended to every recreated notes ref, below refs/notes/." + BR +
                "Default: \"" + WriteOptions().notesPrefix + "\""
        )

    val lightweightTags by option("--lightweight-tags").flag()
        .help(
            "Recreate every annotated tag as a lightweight one, dropping its tagger, date " +
                "and message." + BR +
                "Default: an annotated tag stays annotated."
        )

    val provenance by option("--provenance").flag("--no-provenance", default = true)
        .help(
            "Record each commit's original sha and parents in a trailer." + BR + "Default: on."
        )

    val provenanceTrailer by option("--provenance-trailer").default(WriteOptions().provenanceTrailer)
        .help(
            "The trailer --provenance writes, as its own paragraph." + BR +
                "{repo}, {subdir}, {commit} and {parents} are substituted." + BR +
                "Default: \"" + WriteOptions().provenanceTrailer + "\""
        )
}

private class ReportingOptions : OptionGroup(
    name = "Inspecting a run",
) {
    val dryRun by option("--dry-run").flag()
        .help("Compute and summarize the plan, write no output.")

    val planOut by option("--plan-out").path()
        .help("Dump the deterministic plan as text to this file.")

    val quiet by option("-q", "--quiet").flag()
        .help("Say nothing but the closing report and any error.")

    val verbose by option("-v", "--verbose").flag()
        .help(
            "Print the git command behind each step." + BR +
                "Every git command it shells out to, and the equivalent of the transfer and of every " +
                    "ref written." + BR +
                "Writing the commits is not one command; --plan-out dumps that."
        )

    // Two flags rather than one with a secondary name, because the default is neither of them: a
    // run decides for itself, and each of these overrides that decision in one direction. Giving
    // both is an error, as it is for --quiet and --verbose a few lines up.
    //
    // The help shows them as the one --[no-]progress they are to a reader, so this one carries the
    // help for both and the one below carries none. Help written there would be dropped unsaid.
    val progress by option("--progress").flag()
        .help(
            "Draw progress, or refuse to, whatever stderr is." + BR +
                "For a log of a run that is taking too long, or a terminal to keep clean." + BR +
                "Two options, not one: giving both, or --progress with --quiet, is an error." + BR +
                "Default: drawn when stderr is a terminal, and not otherwise."
        )

    val noProgress by option("--no-progress").flag()

    // The same two-flag shape, and for the same reason: the default is neither, a run working out
    // for itself what its console can encode. These say what it may draw with when that is wrong —
    // in either direction, since the check can misjudge a console both ways. One row again, so the
    // help is all on this one.
    val ascii by option("--ascii").flag()
        .help(
            "Draw progress with ASCII characters only, or with the full set." + BR +
                "For a console that shows the bar as question marks, or one misread the other " +
                "way." + BR +
                "Two options, not one: giving both is an error." + BR +
                "Default: whichever of the two the console can encode."
        )

    val noAscii by option("--no-ascii").flag()
}

/**
 * Entry point of the `git-timebraid` CLI: parse and validate the options, hand a [MergeRequest] to
 * [MergeRunner], and turn what it returns into the closing report. All the orchestration — cloning,
 * reading, planning, writing — lives in the runner; this class is only the command line.
 */
class MergeCommand : CliktCommand(name = "git-timebraid") {

    /**
     * An input is written `<path-or-url>[::[<subdir>][=<name>]]`, and clikt reads a token that
     * opens with `/` and holds a `=`, an absolute path with a name, as a long option with its value
     * attached, and refuses it as unknown. Routing unknown option-shaped tokens to the arguments
     * instead lets the positional parser see the whole spec; [inputs] then refuses, as clikt would,
     * a token opening with `-` that stood before any `--`.
     */
    override val treatUnknownOptionsAsArgs: Boolean = true

    /**
     * `--` followed by [END_OF_OPTIONS], which is how [inputs] learns where the options ended.
     *
     * clikt still reads the `--` itself as the end of the options, so the marker after it lands
     * among the arguments. It holds a NUL, which no command line can pass, so it cannot be an
     * input.
     */
    override fun aliases(): Map<String, List<String>> = mapOf("--" to listOf("--", END_OF_OPTIONS))

    init {
        versionOption(version(), help = "Show the version and exit.") { "git-timebraid version $it" }

        context {
            helpFormatter = { TimebraidHelpFormatter(it) }
            // -h is no longer a synonym for --help but this command's own option, printing the
            // terse list below. That is git's split — `git fetch -h` is one line per option and
            // `git fetch --help` is the manual — and here it is also the only split that works:
            // git intercepts --help on a subcommand before dispatching it and looks for a man
            // page, so `git timebraid --help` never reaches this program at all. -hh is the
            // spelling that gets the full list through that form.
            helpOptionNames = emptySet()
        }

        eagerOption("-h", help = "Show each option's first line and exit.") {
            throw PrintMessage(terseHelp(context))
        }

        eagerOption(
            "--help", "-hh",
            help = "Show every option with its defaults and exit.",
        ) {
            throw PrintHelpMessage(context)
        }

        // The help formatter lays out to the terminal's width, which mordant asks the OS for when
        // the output is a console and otherwise settles at 79 — what a redirect, a pipe into a
        // pager, or a CI log gets. COLUMNS is the usual way to say otherwise, and honouring it is
        // also what lets doc/usage.md quote the option list at the width the rest of that page is
        // written to, rather than at the narrow fallback.
        System.getenv("COLUMNS")?.toIntOrNull()?.let { columns ->
            context { terminal = Terminal(width = columns.coerceAtLeast(40)) }
        }
    }

    private val outputRepo by OutputRepoOptions()
    private val placement by PlacementOptions()
    private val history by HistoryOptions()
    private val refPatterns by RefOptions()
    private val outputContent by OutputContentOptions()
    private val reporting by ReportingOptions()

    private val inputs by argument("repo")
        .help(
            // The form again, because this is where its fields are described and an entry a
            // reader jumps to has to stand on its own. The prolog is a hundred lines up in the
            // rendered help.
            "<path-or-url>[::[<subdir>][=<name>]]\n\n" +
                "Everything before the last '::' is the location, used verbatim -- never " +
                "escaped." + BR +
                "Append a bare '::' when the location itself holds one, and '=<name>' too " +
                "when its last segment cannot be a ref name.\n\n" +
                "<subdir> is where its content lands, and may be nested (::libs/backend)." + BR +
                "Defaults to <name>.\n\n" +
                "<name> labels the repository: the tag and branch prefixes and the provenance " +
                "label by default, and what --root-repo and an <input>:: scope match." + BR +
                "Two inputs may share one; <subdir> is what tells them apart." + BR +
                "Defaults to the last segment of <subdir>, or of the location." + BR +
                "Neither may be written with a ':' or a '='.",
        )
        .transformAll(nvalues = -1, required = false) { tokens -> afterOptions(tokens) }

    override fun help(context: Context): String =
        "Merge several independent git repositories into one, braided together along the time " +
            "axis.\n\n" +
            "Each <repo> is written <path-or-url>[::[<subdir>][=<name>]]."

    override fun helpEpilog(context: Context): String =
        "More on each option, and what the output holds: " +
            "https://github.com/loplex/git-timebraid/blob/main/doc/usage.md"

    /**
     * What `-h` prints: every option, its entry cut to the first line.
     *
     * The epilog is not the one `--help` carries. A reader of the terse list needs the full list
     * before the page that explains it, and needs both spellings, since which of the two works
     * depends on whether this program was reached through git.
     */
    private fun terseHelp(context: Context): String = TerseHelpFormatter(context).formatHelp(
        error = null,
        prolog = help(context),
        epilog = "Every option with its defaults: git-timebraid --help, or git timebraid -hh." +
            "\n\n" + helpEpilog(context),
        parameters = allHelpParams(),
        programName = commandName,
    )

    override fun run() {
        if (reporting.quiet && reporting.verbose) {
            throw UsageError("--quiet and --verbose cannot be combined")
        }
        if (reporting.progress && reporting.noProgress) {
            throw UsageError("--progress and --no-progress cannot be combined")
        }
        if (reporting.ascii && reporting.noAscii) {
            throw UsageError("--ascii and --no-ascii cannot be combined")
        }
        if (reporting.quiet && reporting.progress) {
            // --quiet is about saying nothing, and a bar is not nothing.
            throw UsageError("--quiet and --progress cannot be combined")
        }
        if (outputRepo.output == null && !reporting.dryRun) {
            throw UsageError("-o/--output is required unless --dry-run is given")
        }
        // An empty path is the working directory to `Path` and nothing at all to `File`, so the
        // checks below would pass it and the creation fail on it, once every input was read.
        if (outputRepo.output?.toString()?.isEmpty() == true) {
            throw UsageError("-o/--output names no directory; give the path the output is written to")
        }
        // Asked now rather than left to the write: the plan is written after the output, and a path
        // that cannot take it would fail a run whose braid is already in place.
        reporting.planOut?.let { file ->
            unwritable(file)?.let { throw UsageError("--plan-out '$file' cannot be written: $it") }
        }

        if (placement.scan == null && inputs.isEmpty()) {
            throw UsageError("give at least one input repository, or --scan a directory of them")
        }

        val specs = inputs.map(::parseRepoSpec)
        for (spec in specs) refuseUnusableName(spec.name, spec, spec.location, InputRemedy(spec))
        for ((spec, raw) in specs.zip(inputs)) checkSplit(spec, raw)

        val merged = resolveInputs(specs)
        // Asked now rather than when the output is created, after every input is read and the
        // braid planned: a dry run never reaches that, and would pass an -o the real run refuses.
        // After the inputs, so that one of them being the output is said as that.
        outputRepo.output?.let { output ->
            TargetRepository.refusal(output, outputRepo.force, outputContent.bare)?.let { throw CliktError(it) }
        }
        val patterns = patterns(merged.map { it.name })

        val request = MergeRequest(
            inputs = merged,
            output = outputRepo.output,
            force = outputRepo.force,
            bare = outputContent.bare,
            keepRemotes = outputContent.keepRemotes,
            orderBy = history.orderBy,
            mainline = patterns.mainline,
            refs = patterns.refs,
            interleaveRefs = patterns.interleave,
            labelRefs = patterns.labels,
            notes = outputContent.notes,
            splice = placement.splice,
            dissolveSubmodules = placement.dissolveSubmodules,
            writeOptions = WriteOptions(
                subjectPrefix = outputContent.subjectPrefix,
                tagPrefix = outputContent.tagPrefix,
                branchPrefix = outputContent.branchPrefix,
                notesPrefix = outputContent.notesPrefix,
                lightweightTags = outputContent.lightweightTags,
                provenance = outputContent.provenance,
                provenanceTrailer = outputContent.provenanceTrailer,
            ),
            dryRun = reporting.dryRun,
        )
        // What the drawing may be spelled in: read off stderr, or said outright by --ascii or
        // --no-ascii. The terminal and the spinners have to agree, so it is worked out once.
        val charset = Glyphs.charsetFor(reporting.ascii, reporting.noAscii)
        val progress = Progress(
            level = Progress.level(reporting.quiet, reporting.verbose),
            sink = { echo(it, err = true) },
            terminal = when {
                reporting.noProgress -> null
                else -> Progress.stderrTerminal(
                    currentContext.terminal, force = reporting.progress, charset = charset
                )
            },
            // Mordant's width, which is the real terminal's when there is one, COLUMNS when that is
            // set, and its own fallback otherwise — so a heading is ruled to the same width the
            // help is laid out to.
            width = currentContext.terminal.size.width,
            charset = charset,
        )

        val result = try {
            MergeRunner(request, progress).run()
        } catch (e: GitCommandException) {
            throw CliktError(e.message ?: "a git subprocess failed")
        } catch (e: IllegalStateException) {
            throw CliktError(e.message ?: "the merge could not be completed")
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message ?: "invalid input")
        } finally {
            // Before the refusal above is thrown, not after: whatever is still being drawn has a
            // thread painting it, and a message printed into a running bar is a message nobody can
            // read. This is also what gives the cursor back.
            progress.stopDrawing()
        }

        report(result)
    }

    /** Every input as [resolveInputs] placed it, for a later refusal to say where each is. */
    private var landings: List<Landing> = emptyList()

    /**
     * The inputs the run will use: what `--scan` found, renamed by the corrections, what the
     * `<repo>` arguments named, and `--root-repo` applied over all of them.
     *
     * An argument with a location is always another input, and two naming one local repository are
     * refused. One naming a repository the scan already found — its git directory, however the
     * argument spells it — is refused rather than read as that finding, since it would otherwise be one
     * input by one spelling and a second by another: the rename it may have meant is a correction,
     * `::<subdir>=<name>`, which names the finding by where it sits and has no location to be spelled
     * two ways.
     */
    private fun resolveInputs(specs: List<RepoSpec>): List<ResolvedInput> {
        val scanned = placement.scan?.let { base ->
            try {
                RepositoryScan.scan(base, outputRepo.output)
            } catch (e: IllegalArgumentException) {
                throw UsageError(e.message ?: "--scan found nothing usable")
            }
        } ?: emptyList()

        // A finding and an argument are one repository when they are one git directory, as the
        // filesystem has it: however the argument spells it, through a symlink or its `.git`.
        val foundPlaces = scanned.associateWith { placeOf(it.path.toString()) }
        val byGitDir = foundPlaces.entries.mapNotNull { (found, local) -> local?.let { it.gitDir to found } }.toMap()
        // The directories the output takes up, which an argument's or a finding's own may not meet.
        val outputPlaces = outputRepo.output?.let { placesOf(it, withGitDir = !outputContent.bare) }
        // The scan leaves the output itself out, however it is spelled, but not a working tree whose
        // git directory the output is: `-o tree/x/.git` beside a found `tree/x` is refused here.
        scanned.firstOrNull { outputPlaces != null && meet(placesOf(it.path), outputPlaces) }?.let {
            throw UsageError(
                "'${shownPath(it.path.toString())}', which --scan found, is the output (-o), which a run " +
                    "cannot braid into itself"
            )
        }
        val renamed = corrections(specs.filter { it.isCorrection }, scanned)
        val extras = ArrayList<Landing>()
        for (spec in specs.filterNot { it.isCorrection }) {
            val local = if (spec.isRemote) null else placeOf(spec.location)
            local?.let { byGitDir[it.gitDir] }?.let { found ->
                throw UsageError(
                    "'${spec.format()}' names a repository --scan already found, at " +
                        "${subdirName(found.subdir)} -- an argument with a location is always another " +
                        "input; to rename that one, write ${InputRemedy("", found.subdir).named()}"
                )
            }
            extras += Landing(
                spec.location, spec.isRemote, spec.name, spec.subdir ?: spec.name, local, InputRemedy(spec),
                spec = spec,
            )
        }
        // Two arguments naming one git directory are one repository, which braided as two inputs would
        // be braided against itself.
        val byRepository = extras.filter { it.local != null }.groupBy { it.local!!.gitDir }
        byRepository.values.firstOrNull { it.size > 1 }?.let { twice ->
            val shown = shownPath(twice.first().local!!.path.toString())
            throw UsageError(
                "${count(twice)} input arguments name one repository, '$shown', placing it at " +
                    "${twice.joinToString(" and ") { "'${it.subdir}'" }} -- give it once"
            )
        }

        val merged = ArrayList<Landing>(scanned.size + extras.size)
        for (found in scanned) {
            val name = renamed[found]
                ?: found.name.also {
                    refuseUnusableName(it, null, found.path.toString(), InputRemedy("", found.subdir))
                }
            merged += Landing(
                found.path.toString(), isRemote = false, name, found.subdir, foundPlaces[found],
                // What renames a finding is a correction naming where it sits; nothing moves one.
                InputRemedy("", found.subdir),
                movable = false,
            )
        }
        merged += extras

        // The input --root-repo names lands at the root, whatever subdirectory it was written
        // with; two it names are refused below.
        refuseSharedSubdirs(merged.filter { it.name != placement.rootRepo })
        if (outputContent.keepRemotes) refuseSharedRemotes(merged)
        placement.rootRepo?.let { root ->
            val named = merged.filter { it.name == root }
            if (named.isEmpty()) throw UsageError("--root-repo '$root' is not one of the inputs")
            if (named.size > 1) {
                throw UsageError(
                    "--root-repo '$root' names ${count(named)} inputs: ${listed(named)} -- give one of " +
                        "them another name, as ${named.joinToString(" or ") { it.remedy.named() }}"
                )
            }
            merged.firstOrNull { it.subdir == null && it.name != root }?.let { base ->
                throw UsageError(
                    "--root-repo '$root' conflicts with --scan: the base directory is itself a " +
                        "repository ('${base.name}') and lands at the output root, where nothing " +
                        "moves it from -- leave --root-repo out, or --scan a directory that is no " +
                        "repository"
                )
            }
        }
        // The input at the root lends its name to `{subdir}` too, and only now is it known which.
        for (input in merged.filter { it.name == placement.rootRepo || it.subdir == null }) {
            refuseUnusableName(input.name, input.spec, input.location, input.remedy, atRoot = true)
        }
        val resolved = merged.map { input ->
            // Refused only now, after the refusals that speak of the whole command line.
            if (!input.isRemote && input.local == null) throw CliktError("no git repository at ${input.location}")
            val subdir = input.subdir.takeIf { input.name != placement.rootRepo }
            ResolvedInput(input.location, input.name, subdir, input.local, input.movable)
        }
        resolved.firstOrNull { input ->
            val local = input.local
            local != null && outputPlaces != null && meet(placesOf(local.path), outputPlaces)
        }?.let { throw UsageError("'${it.location}' is the output (-o), which a run cannot braid into itself") }
        landings = merged
        return resolved
    }

    /**
     * The names [corrections] give the findings of [scanned], each matched by the subdirectory it
     * names, as a string: `::libs/core=core-lib` renames the finding at `libs/core`, and `::=<name>`
     * the base directory, found at the output root.
     *
     * A correction renames and does not move: where the scan put a finding is where the directory
     * sits, and `--root-repo` is what moves one, to the root. So one has to give a name, and name a
     * subdirectory the scan found something at, once; and without a scan there is nothing to
     * correct.
     */
    private fun corrections(
        corrections: List<RepoSpec>,
        scanned: List<ScannedRepository>,
    ): Map<ScannedRepository, String> {
        val renamed = LinkedHashMap<ScannedRepository, String>()
        for (spec in corrections) {
            val written = spec.format()
            if (placement.scan == null) {
                throw UsageError(
                    "'$written' has no location, so it corrects a repository --scan found, and there " +
                        "is no --scan -- put the repository's location before the '::'"
                )
            }
            val name = spec.given ?: throw UsageError(
                "'$written' corrects a repository --scan found and gives it no name, which is all a " +
                    "correction does -- write ${InputRemedy("", spec.subdir).named()}"
            )
            val found = scanned.firstOrNull { it.subdir == spec.subdir } ?: throw UsageError(
                "'$written' corrects the repository --scan found at ${subdirName(spec.subdir)}, and it " +
                    "found none there; it found one at " + scanned.joinToString { subdirName(it.subdir) }
            )
            if (renamed.put(found, name) != null) {
                throw UsageError(
                    "two corrections rename the repository --scan found at ${subdirName(found.subdir)}"
                )
            }
        }
        return renamed
    }

    /** Where a finding at [subdir] sits, as a refusal names it. */
    private fun subdirName(subdir: String?): String = subdir?.let { "'$it'" } ?: "the output root"

    /**
     * An input's subdirectory and name, and where it is on disk: `null` for a remote input, and for a local
     * one holding no repository, which is refused once the command line as a whole has been.
     */
    private class Landing(
        val location: String,
        val isRemote: Boolean,
        val name: String,
        val subdir: String?,
        val local: LocalPlace?,
        /** The way out of a refusal about this input, spelled on the argument that gave it. */
        val remedy: InputRemedy,
        /** Whether an argument placed this input and another can place it elsewhere; not a finding. */
        val movable: Boolean = true,
        /** The argument that gave this input, which a refusal quotes; `null` for a finding. */
        val spec: RepoSpec? = null,
    )

    /**
     * Where the repository at [location] is, as the filesystem has it, or `null` when there is none
     * there: its git directory looked up once, before a remote input is cloned or any input read.
     *
     * A [CliktError] rather than a usage error when the location cannot be read, as the refusal of
     * a missing repository was when opening the input made it: the command line is well formed, and
     * what it names is not there.
     */
    private fun placeOf(location: String): LocalPlace? =
        try {
            val path = Path.of(location)
            SourceRepository.gitDirOf(path)?.let { gitDir ->
                LocalPlace(gitDir.toPath().toRealPath(), path.toRealPath(), SourceRepository.absolute(path).toString())
            }
        } catch (e: InvalidPathException) {
            null
        } catch (e: IOException) {
            throw CliktError("cannot read '$location': ${e.message}")
        }

    /**
     * Refuses a name git would not accept where the options put it in a ref name, naming the
     * option, the ref, and the way out; and, wherever the name is used, one holding whitespace or a
     * `:`, or opening with `^`.
     *
     * Those are refused always because an `<input>::` scope has to be able to spell every input's
     * name: the scope ends at the first `::`, which a `:` in the name could bring too early, a
     * pattern option splits its value on whitespace, and it reads a `^` before the scope as a
     * subtraction written a scope too early.
     *
     * A name is a label, and only a template that substitutes it makes it part of a ref: `{repo}`
     * in a prefix (the notes prefix only under `--notes`) or in a pattern's destination, `{subdir}`
     * for the input at the root, and under `--keep-remotes` the remote named after the input. Those
     * prefixes and the remote are asked here, on the command line, with nothing yet read, so the tag
     * and branch prefixes count whether or not the run writes a ref of their kind; `{subdir}` in a
     * prefix is asked of the input at the root, [atRoot], once the inputs say which that is. The rest,
     * and a template that fails whatever the name, as `{repo}.lock/` does, are left to the check
     * every ref name gets once the refs are known. Left to the run, a name would be refused only
     * once every input was read, with the first ref carrying it, or not at all where no ref carried
     * it.
     *
     * Giving the input a name is the way out whichever part the name came from, and the remedy spells
     * it on the argument as written, its subdirectory kept: `::a..b` names the input `a..b` and
     * places it at `a..b/`, and only `::a..b=<name>` keeps the one while changing the other. A name
     * the suffix gave, or took from its subdirectory, is refused like the suffix's other parts too,
     * since the `::` may have belonged to the location.
     */
    private fun refuseUnusableName(
        name: String,
        spec: RepoSpec?,
        location: String,
        remedy: InputRemedy,
        atRoot: Boolean = false,
    ) {
        val where = if (spec == null) "found by --scan at ${shownPath(location)}" else "in '${spec.format()}'"
        // A name the suffix gave, or took from its subdirectory, may be one the location meant to keep.
        val orLocation = spec?.takeIf { !it.isCorrection && (it.given != null || it.subdir != null) }
            ?.let { "; or, " + InputRemedy(it.format()).wholeLocation() } ?: ""
        fun refuse(said: String): Nothing = throw UsageError(said + orLocation)
        if (name.any(Char::isWhitespace) || ':' in name || name.startsWith('^')) {
            refuse(
                "'$name' cannot be a repository name ($where): an <input>:: scope has to be able to spell " +
                    "it, and a scope ends at the first '::', a pattern option splits its value on whitespace " +
                    "and reads a leading '^' as a subtraction -- give the input a name, as ${remedy.named()}"
            )
        }
        val uses = listOfNotNull(
            Triple("--tag-prefix '${outputContent.tagPrefix}'", Constants.R_TAGS, outputContent.tagPrefix),
            Triple("--branch-prefix '${outputContent.branchPrefix}'", Constants.R_HEADS, outputContent.branchPrefix),
            Triple("--notes-prefix '${outputContent.notesPrefix}'", Constants.R_NOTES, outputContent.notesPrefix)
                .takeIf { outputContent.notes },
            Triple("--keep-remotes", Constants.R_REMOTES, "{repo}/").takeIf { outputContent.keepRemotes },
        ).filter { (_, _, template) -> "{repo}" in template || atRoot && "{subdir}" in template }
        fun refName(namespace: String, template: String, name: String) =
            namespace + template.replace("{repo}", name).replace("{subdir}", if (atRoot) name else "x") + "x"
        for ((option, namespace, template) in uses) {
            if (TargetRepository.isRefName(refName(namespace, template, name))) continue
            if (!TargetRepository.isRefName(refName(namespace, template, "x"))) continue
            val ref = refName(namespace, template, name).removeSuffix("x")
            refuse(
                "'$name' cannot be a repository name ($where): $option puts it in '$ref', and " +
                    "git will not have that in a ref name -- give the input a name, as ${remedy.named()}"
            )
        }
    }

    /**
     * A location as the user would recognise it: relative to where the command was run when it sits
     * under it, and as the user wrote it otherwise. `--scan` resolves what it finds, so a scanned
     * input carries an absolute path however short the argument was, and printing that back names
     * the machine rather than the repository.
     */
    private fun shownPath(location: String): String {
        val here = Path.of("").toAbsolutePath()
        val path = runCatching { Path.of(location).toAbsolutePath().normalize() }.getOrNull()
            ?: return location
        // The working directory relativizes to the empty path, which would name nothing at all in a
        // refusal whose whole point is to name both sides.
        return if (path.startsWith(here)) here.relativize(path).toString().ifEmpty { "." } else location
    }

    /**
     * Refuses two inputs placed at one destination, naming both and the way out.
     *
     * The destination is what tells inputs apart, their names being labels two may share, so it has
     * to be unique; asked here, on the command line, rather than by the planner once every input is
     * read. An argument that places nothing lands at its name, so two naming one directory `core` in
     * different places meet here, and each argument among them is offered a subdirectory of its own;
     * a finding stays where it sits.
     */
    private fun refuseSharedSubdirs(inputs: List<Landing>) {
        val shared = inputs.filter { it.subdir != null }.groupBy { it.subdir }.filterValues { it.size > 1 }
        val (subdir, sharing) = shared.entries.firstOrNull() ?: return
        // A finding sits where the directory does, so only an argument can be given another
        // subdirectory.
        val movable = sharing.filter { it.movable }
        val remedy = if (movable.isEmpty()) {
            "each was found by --scan, and a finding cannot be given another subdirectory: rename one of " +
                "their directories"
        } else {
            val which = when {
                movable.size == sharing.size -> "one of them"
                movable.size == 1 -> "the argument"
                else -> "one of the arguments"
            }
            "give $which a subdirectory of its own, as ${movable.joinToString(" or ") { it.remedy.moved() }}"
        }
        throw UsageError("${count(sharing)} inputs would be placed at '$subdir': ${listed(sharing)} -- $remedy")
    }

    /**
     * Refuses two inputs `--keep-remotes` would add as one remote, or as a remote and one below it,
     * naming both and the way out.
     *
     * A remote is named after its input, and two sharing a name would be one remote whose refspec
     * covers both inputs' mirrors, the first fetch deleting whichever of them the URL does not have.
     */
    private fun refuseSharedRemotes(inputs: List<Landing>) {
        inputs.groupBy { it.name }.values.firstOrNull { it.size > 1 }?.let { sharing ->
            throw UsageError(
                "--keep-remotes adds each input as a remote named after it, and ${count(sharing)} are " +
                    "called '${sharing.first().name}': ${listed(sharing)} -- give one of them another " +
                    "name, as ${sharing.joinToString(" or ") { it.remedy.named() }}"
            )
        }
        // Remotes `libs` and `libs/core` share remote-tracking refs: `fetch --prune libs` deletes
        // what `libs/core` fetched, and a branch `core` of `libs` cannot be fetched at all, its ref
        // being the directory `libs/core`'s refs are in.
        for (outer in inputs) {
            val inner = inputs.firstOrNull { it.name.startsWith(outer.name + "/") } ?: continue
            throw UsageError(
                "--keep-remotes adds each input as a remote named after it, and '${outer.name}' " +
                    "would hold the refs of '${inner.name}' among its own: ${listed(listOf(outer, inner))} " +
                    "-- give one of them another name, as ${outer.remedy.named()} or ${inner.remedy.named()}"
            )
        }
    }

    /** [inputs]' locations as a refusal lists them: `a, b and c`. */
    private fun listed(inputs: List<Landing>): String {
        val where = inputs.map { shownPath(it.location) }
        return where.dropLast(1).joinToString(", ") + " and " + where.last()
    }

    /** How many [inputs] there are, spelled out as far as the refusals around it spell things out. */
    private fun count(inputs: List<Landing>): String = when (inputs.size) {
        2 -> "two"
        3 -> "three"
        else -> inputs.size.toString()
    }


    /** What every option naming refs asked for, parsed; see [patterns]. */
    private class Patterns(
        val mainline: MainlineRequest,
        val refs: ScopedPatterns,
        val interleave: ScopedPatterns,
        val labels: ScopedPatterns,
    )

    /**
     * Every option naming refs, parsed against [inputs], their names in the order the run reads
     * them: on the command line, so a mistyped pattern is refused before an input is cloned or
     * read, as the usage error every other mistyped argument gets.
     *
     * The scoped patterns are parsed before the mainlines, so that a mistyped input name in a
     * pattern is reported before anything the mainline value gets wrong.
     */
    private fun patterns(inputs: List<String>): Patterns =
        try {
            val refs = branchPatterns(refPatterns.branches, inputs) +
                ScopedPatterns.parse(refPatterns.refs, "--ref", inputs, destinations = true)
            val labels = ScopedPatterns.parse(refPatterns.labelRefs, "--label-ref", inputs, destinations = true)
            val interleave = ScopedPatterns.parse(refPatterns.interleaveRefs, "--interleave-ref", inputs)
            Patterns(
                refs = ScopedPatterns(refs, "--ref", inputs, emptyMeans = true),
                labels = ScopedPatterns(labels, "--label-ref", inputs, emptyMeans = false),
                interleave = ScopedPatterns(interleave, "--interleave-ref", inputs, emptyMeans = false),
                mainline = MainlineRequest.parse(history.mainlineBranch, inputs),
            )
        } catch (e: SharedScope) {
            // Said as --root-repo says it of the same inputs: where each is, and each one's way out.
            val sharing = landings.filter { it.name == e.input }
            throw UsageError(
                "${e.option} '${e.value}' is for input '${e.input}', and ${count(sharing)} inputs are called " +
                    "that: ${listed(sharing)} -- give one of them another name, as " +
                    sharing.joinToString(" or ") { it.remedy.named() }
            )
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "a ref pattern could not be read")
        }

    private fun report(result: MergeResult) {
        // The report is the last thing said and belongs to no phase, so it is set off from the one
        // that happened to finish before it; under --quiet no phase was printed to set it off from.
        if (!reporting.quiet) echo("", err = true)
        echo("mainline branch: ${result.inputs.mainlineBranch}", err = true)

        // Only the splices --splice enabled are worth a line in the closing report. The repository
        // at the output root contains every other input by definition, so saying so of each of them
        // would say nothing here. The run's own progress does report them, under --verbose, where
        // what each input cost is the point.
        for (splice in result.splices.filter { it.splice.outerSubdir != null }) {
            echo(
                "spliced: ${splice.splice.innerSubdir} inside ${splice.splice.outerPath} at " +
                    "${splice.commits} commits, no collision",
                err = true,
            )
        }

        // A dissolve is worth a line wherever it happened, the output root included: unlike the
        // containment itself, it is not implied by the layout and it changes what the output holds
        // at that path.
        for (splice in result.splices.filter { it.dissolved > 0 }) {
            echo(
                "dissolved: the submodule at ${splice.splice.innerSubdir} in " +
                    "${splice.splice.outer.name} replaced by its own content at " +
                    "${splice.dissolved} commits",
                err = true,
            )
        }
        echo(result.plan.summary())

        reporting.planOut?.let { file ->
            try {
                file.toAbsolutePath().parent?.createDirectories()
                file.writeText(result.plan.render())
            } catch (e: IOException) {
                throw CliktError("could not write the plan to '$file': ${e.message}")
            }
            echo("plan written to $file", err = true)
        }

        result.fetch?.let { fetch ->
            echo(
                "fetched ${fetch.refs} refs from ${fetch.repositories} repositories into ${outputRepo.output}",
                err = true,
            )
        }

        result.write?.let { summary ->
            echo(
                "wrote ${summary.commits} commits and ${summary.trees} trees on top",
                err = true,
            )
            val remotes =
                if (summary.remoteRefs > 0) ", ${summary.remoteRefs} remote-tracking" else ""
            val notes = if (summary.notes > 0) ", ${summary.notes} notes refs" else ""
            val foreign = if (summary.foreign > 0) ", ${summary.foreign} other" else ""
            // What was left out is said here as well as in its phase, because -q prints this alone.
            val labels = result.inputs.labelsSkipped.let { if (it > 0) ", $it labels skipped" else "" }
            val notesLeft = result.inputs.notesSkipped.let { if (it > 0) ", $it notes skipped" else "" }
            echo(
                "refs: ${summary.branches} branches, ${summary.tags} tags$foreign$notes$remotes$labels$notesLeft, " +
                    "HEAD -> ${summary.head}",
                err = true,
            )
        }
        // A dry run writes no refs and so prints no refs: line, but what the run would leave out is
        // said all the same, -q included: a dry run is how a pattern is tuned.
        if (result.write == null) {
            val skipped = listOfNotNull(
                result.inputs.labelsSkipped.takeIf { it > 0 }?.let { "$it labels skipped" },
                result.inputs.notesSkipped.takeIf { it > 0 }?.let { "$it notes skipped" },
            )
            if (skipped.isNotEmpty()) echo(skipped.joinToString(", "), err = true)
        }
    }

    /**
     * The project version Maven bakes into the jar manifest (`Implementation-Version`), or "dev"
     * when running from unpacked classes (an IDE, `mvn exec:java`).
     */
    private fun version(): String =
        MergeCommand::class.java.`package`?.implementationVersion ?: "dev"
}

private const val END_OF_OPTIONS = "\u0000--"

/**
 * The inputs among [tokens], which are the `repo` arguments with [END_OF_OPTIONS] wherever a `--`
 * stood: a token opening with `-` before the first marker is an option nobody defined, and is
 * refused the way clikt refuses one, with its suggestion of the option probably meant.
 */
private fun ArgumentTransformContext.afterOptions(tokens: List<String>): List<String> {
    val end = tokens.indexOf(END_OF_OPTIONS).let { if (it < 0) tokens.size else it }
    tokens.take(end).firstOrNull { it.startsWith("-") }?.let { token ->
        val name = token.substringBefore("=")
        val known = context.command.registeredOptions().filterNot { it.hidden }
            .flatMap { it.names + it.secondaryNames }
        throw NoSuchOption(name, context.suggestTypoCorrection(name, known))
    }
    return tokens.filter { it != END_OF_OPTIONS }
}

/**
 * The directories a repository at [location] takes up, for telling whether two locations are one
 * repository: the location itself; the git directory it holds, as JGit finds it — a `.git`
 * directory, or the one a `.git` file names in a linked worktree or a submodule — and that
 * directory's common one, shared with the main repository of a linked worktree; and for any of these
 * named `.git`, the working tree around it, which git reads through it. Each is resolved as the
 * filesystem has it ([resolved]). [withGitDir] counts `location/.git` whether or not it exists yet,
 * as the git directory a non-bare output is given.
 */
private fun placesOf(location: Path, withGitDir: Boolean = false): Set<Path> {
    val places = mutableSetOf(resolved(location))
    if (withGitDir) places.add(resolved(location.resolve(".git")))
    SourceRepository.gitDirOf(location)?.let { gitDir ->
        places.add(resolved(gitDir.toPath()))
        try {
            places.add(resolved(FS.DETECTED.getCommonDir(gitDir).toPath()))
        } catch (e: IOException) {
            // No common directory to read: the git directory is its own.
        }
    }
    if (location.fileName?.toString() == ".git") location.toAbsolutePath().parent?.let { places.add(resolved(it)) }
    for (place in places.toList()) {
        if (place.fileName?.toString() == ".git") place.parent?.let { places.add(it) }
    }
    return places
}

/**
 * Whether two sets of [placesOf] meet: a directory in both, or two that exist and the filesystem
 * calls one, as it does where it ignores case.
 */
private fun meet(a: Set<Path>, b: Set<Path>): Boolean =
    a.any { it in b } || a.any { x ->
        b.any { y ->
            try {
                Files.exists(x) && Files.exists(y) && Files.isSameFile(x, y)
            } catch (e: IOException) {
                false
            }
        }
    }

/**
 * [path] as the filesystem resolves it: its real path where it exists, a symlink or a `..` past one
 * followed, and otherwise its nearest existing ancestor's real path with the rest appended.
 */
private fun resolved(path: Path): Path {
    val absolute = path.toAbsolutePath()
    var existing: Path? = absolute
    while (existing != null && !Files.exists(existing)) existing = existing.parent
    if (existing == null) return absolute.normalize()
    return try {
        existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
    } catch (e: IOException) {
        absolute.normalize()
    }
}

/**
 * Why [file] cannot take the plan, or `null` when nothing here says it cannot.
 *
 * Asked of the file itself where it exists, and otherwise of the nearest directory above it that
 * does, which is where the write would create it. A write can still fail for a reason no such
 * question sees, a full disk for one, and that is reported where it happens.
 */
private fun unwritable(file: Path): String? {
    if (Files.isDirectory(file)) return "it is a directory"
    if (Files.exists(file)) return if (Files.isWritable(file)) null else "it is not writable"
    var parent = file.toAbsolutePath().parent
    while (parent != null && !Files.exists(parent)) parent = parent.parent
    return when {
        parent == null -> null
        !Files.isDirectory(parent) -> "$parent is not a directory"
        !Files.isWritable(parent) -> "$parent is not writable"
        else -> null
    }
}
