package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoSuchOption
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
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import cz.loplex.timebraid.MergeInput
import cz.loplex.timebraid.MergeRequest
import cz.loplex.timebraid.MergeResult
import cz.loplex.timebraid.MergeRunner
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.GitCommandException
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.RepositoryScan
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.WriteOptions
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
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
private const val BR = "\u0085"

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
                "on disk."
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
    val mainlineBranch by option("--mainline-branch").multiple()
        .help(
            "Branch treated as the mainline in every input (repeatable)." + BR +
                "Prefix with <input>:: to give one input its own, where two do not agree." + BR +
                "One quoted argument may hold several, separated by spaces." + BR +
                "An unscoped value covers the rest and names the output's branch." + BR +
                "Default: the first of " +
                "${CommitGraphReader.MAINLINE_CANDIDATES.joinToString("/")} present in all."
        )

    val orderBy by option("--order-by")
        .choice("author" to OrderBy.AUTHOR, "committer" to OrderBy.COMMITTER)
        .default(OrderBy.COMMITTER)
        .help("Timestamp used to interleave the strands." + BR + "Default: committer.")

    val branches by option("-b", "--branch").multiple()
        .help(
            "Carry over only these branches, by short name (repeatable)." + BR +
                "Shorthand for --ref refs/heads/<name>, so naming one leaves out every ref not " +
                "named, tags included." + BR +
                "One quoted argument may hold several, separated by spaces."
        )

    val refs by option("--ref").multiple()
        .help(
            "Carry over only the refs matching this glob, branches and tags alike " +
                "(repeatable)." + BR +
                "Patterns are matched against full ref names, and may be prefixed <input>:: " +
                "to narrow one input." + BR +
                "A :<destination> after the pattern is a refspec's right half, naming where the matches land." +
                BR +
                "refs/tags/ hands them to --tag-prefix; a destination holding a star spells the " +
                "name out, substituting what the pattern matched." + BR +
                "Beyond refs/heads/ and refs/tags/ a namespace is read only when named, and then " +
                "the destination is required." + BR +
                "One quoted argument may hold several, separated by spaces." + BR +
                "Default: every branch and tag, each in the namespace it came from."
        )

    val labelRefs by option("--label-ref").multiple()
        .help(
            "Also recreate the refs matching this glob whose target the run already holds " +
                "(repeatable)." + BR +
                "Reads nothing extra and never delays a merge, so adding one cannot change a " +
                "commit." + BR +
                "A match whose target was not loaded is skipped, not an error." + BR +
                "Takes an <input>:: prefix and a :<destination>, as --ref does." + BR +
                "One quoted argument may hold several." + BR +
                "Default: none."
        )

    val interleaveRefs by option("--interleave-ref").multiple()
        .help(
            "Let this ref's commits delay a mainline merge that merges them in (repeatable)." +
                BR + "Takes an <input>:: prefix; one quoted argument may hold several." +
                BR + "Default: none."
        )
}

private class OutputContentOptions : OptionGroup(
    name = "What the output repository holds",
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
                "{repo} is substituted; an empty value qualifies nothing." + BR +
                "Two inputs then meeting on one name is refused, not resolved." + BR +
                "Default: \"" + WriteOptions().tagPrefix + "\""
        )

    val branchPrefix by option("--branch-prefix").default(WriteOptions().branchPrefix)
        .help(
            "Prefix prepended to every recreated branch." + BR +
                "{repo} is substituted; an empty value qualifies nothing." + BR +
                "Two inputs then meeting on one name is refused, not resolved." + BR +
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
            "Carry over every input's refs/notes/, rekeyed onto the commits this run writes." + BR +
                "A merge gives every commit a new sha, so a note carried over unchanged would " +
                "be attached to nothing." + BR +
                "A note on an object the run did not write is skipped, and the run says how many." +
                BR + "Default: notes are not read."
        )

    val notesPrefix by option("--notes-prefix").default(WriteOptions().notesPrefix)
        .help(
            "Prefix prepended to every recreated notes ref, below refs/notes/." + BR +
                "{repo} is substituted; an empty value qualifies nothing." + BR +
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
                "{repo}, {commit} and {parents} are substituted." + BR +
                "Dropping {commit} or {parents} gives up what makes the output checkable." + BR +
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
    // run decides for itself, and each of these overrides that decision in one direction. It is
    // also how --quiet and --verbose are spelled a few lines up.
    val progress by option("--progress").flag()
        .help(
            "Draw progress even when stderr is not a terminal." + BR +
                "For watching a log of a run that is taking too long."
        )

    val noProgress by option("--no-progress").flag()
        .help(
            "Draw no progress even when stderr is a terminal." + BR +
                "Default: drawn when stderr is a terminal, and not otherwise."
        )

    // The same two-flag shape, and for the same reason: the default is neither, a run working out
    // for itself what its console can encode. These say what it may draw with when that is wrong —
    // in either direction, since the check can misjudge a console both ways.
    val ascii by option("--ascii").flag()
        .help(
            "Draw progress with ASCII characters only." + BR +
                "For a console that shows the bar as question marks."
        )

    val noAscii by option("--no-ascii").flag()
        .help(
            "Draw progress with the full character set." + BR +
                "Default: whichever of the two the console can encode."
        )
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
        versionOption(version()) { "git-timebraid version $it" }

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
    private val outputContent by OutputContentOptions()
    private val reporting by ReportingOptions()

    private val inputs by argument("repo")
        .help(
            "Everything before the last '::' is the location, used verbatim -- never escaped." +
                BR + "Append a bare '::' when the location itself holds one, and '=<name>' too " +
                "when its last segment cannot be a ref name.\n\n" +
                "<subdir> is where its content lands, and may be nested (::libs/backend)." + BR +
                "Defaults to <name>.\n\n" +
                "<name> is the repository's identity: the tag prefix, the branch prefix, the " +
                "provenance label, and what --root-repo matches." + BR +
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
        for ((spec, raw) in specs.zip(inputs)) checkSplit(spec, raw)

        val merged = resolveInputs(specs)
        // Asked now rather than when the output is created, after every input is read and the
        // braid planned: a dry run never reaches that, and would pass an -o the real run refuses.
        // After the inputs, so that one of them being the output is said as that.
        outputRepo.output?.let { output ->
            TargetRepository.refusal(output, outputRepo.force, outputContent.bare)?.let { throw CliktError(it) }
        }

        val request = MergeRequest(
            inputs = merged,
            output = outputRepo.output,
            force = outputRepo.force,
            bare = outputContent.bare,
            keepRemotes = outputContent.keepRemotes,
            orderBy = history.orderBy,
            mainlineBranch = history.mainlineBranch,
            refs = branchPatterns(history.branches) + history.refs,
            interleaveRefs = history.interleaveRefs,
            labelRefs = history.labelRefs,
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

    /**
     * The inputs the run will use: what `--scan` found, what the `<repo>` arguments named, and
     * `--root-repo` applied over both.
     *
     * An argument whose location is a directory the scan already found is not a second input but a
     * correction to that one. The name it gives wins, and the subdirectory too when it gives one,
     * while an argument that gives none leaves the finding where the scan put it. That is what
     * makes the two composable rather than merely both allowed: renaming is the answer two findings
     * that derive the same name need, and there is no other way to reach one of them.
     */
    private fun resolveInputs(specs: List<RepoSpec>): List<MergeInput> {
        val scanned = placement.scan?.let { base ->
            try {
                RepositoryScan.scan(base, outputRepo.output)
            } catch (e: IllegalArgumentException) {
                throw UsageError(e.message ?: "--scan found nothing usable")
            }
        } ?: emptyList()

        val byPath = scanned.associateBy { it.path }
        val overrides = LinkedHashMap<Path, RepoSpec>()
        val extras = ArrayList<RepoSpec>()
        // The directories the output takes up, which an argument's or a finding's own may not meet.
        val outputPlaces = outputRepo.output?.let { placesOf(it, withGitDir = !outputContent.bare) }
        // The scan leaves the output itself out, however it is spelled, but not a working tree whose
        // git directory the output is: `-o tree/x/.git` beside a found `tree/x` is refused here.
        scanned.firstOrNull { outputPlaces != null && meet(inputPlacesOf(it.path), outputPlaces) }?.let {
            throw UsageError(
                "'${shownPath(it.path.toString())}', which --scan found, is the output (-o), which a run " +
                    "cannot braid into itself"
            )
        }
        for (spec in specs) {
            val path = localPathOf(spec)
            if (path != null && outputPlaces != null && meet(inputPlacesOf(Path.of(spec.location)), outputPlaces)) {
                throw UsageError(
                    "'${spec.location}' is the output (-o), which a run cannot braid into itself"
                )
            }
            if (path == null || path !in byPath) {
                extras += spec
            } else if (overrides.put(path, spec) != null) {
                throw UsageError("two input arguments name '$path', which --scan already found")
            }
        }

        val merged = ArrayList<MergeInput>(scanned.size + extras.size)
        for (found in scanned) {
            val spec = overrides[found.path]
            merged += if (spec == null) {
                refuseScannedName(found.name, found.path)
                MergeInput(found.path.toString(), isRemote = false, name = found.name, subdir = found.subdir)
            } else {
                MergeInput(spec.location, spec.isRemote, spec.name, spec.subdir ?: found.subdir)
            }
        }
        for (spec in extras) {
            merged += MergeInput(spec.location, spec.isRemote, spec.name, spec.subdir ?: spec.name)
        }

        val names = merged.map { it.name }
        if (names.toSet().size != names.size) throw duplicateNames(merged)
        placement.rootRepo?.let { root ->
            if (root !in names) throw UsageError("--root-repo '$root' is not one of the inputs")
            merged.firstOrNull { it.subdir == null && it.name != root }?.let { base ->
                throw UsageError(
                    "--root-repo '$root' conflicts with --scan: the base directory is itself a " +
                        "repository ('${base.name}') and lands at the output root -- give that one " +
                        "a subdirectory to move it off, as '${shownPath(base.location)}::<subdir>'"
                )
            }
        }
        return merged.map { input ->
            if (input.name != placement.rootRepo) input
            else MergeInput(input.location, input.isRemote, input.name, subdir = null)
        }
    }

    /**
     * [spec]'s location as a directory this scan could have found, or `null` when it is not one:
     * a remote, or a location this platform cannot spell as a path.
     *
     * A final `.git` segment is dropped, as [repositoryPlace] drops it: `core/.git` opens the
     * repository the scan found at `core`, and naming it that way corrects that finding rather than
     * adding the same repository a second time.
     */
    private fun localPathOf(spec: RepoSpec): Path? {
        if (spec.isRemote) return null
        return try {
            repositoryPlace(Path.of(spec.location))
        } catch (e: InvalidPathException) {
            null
        }
    }

    /**
     * Refuses a name the scan derived that git would not accept inside a ref, as [parseRepoSpec]
     * refuses one derived from an argument, naming the directory and the argument that renames it.
     *
     * Only a finding no argument renamed is asked: a renamed one carries the argument's own name,
     * which the parser has checked already. Left to the run, the name would have failed at the
     * write of the first ref carrying it, an input's tag under the default prefix, once the braid
     * was written, or not at all where no ref carried it.
     */
    private fun refuseScannedName(name: String, path: Path) {
        if (isRefComponent(name)) return
        val dir = shownPath(path.toString())
        throw UsageError(
            "'$name' cannot be a repository name (found by --scan at $dir): it becomes a " +
                "tag prefix, and git will not have it in a ref name -- give it a name with " +
                "'$dir::=<name>'"
        )
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
     * Inputs sharing a name, with the remedy that applies, naming every location that derived it.
     *
     * Both sides named: under `--scan` the user wrote no argument at all, so the directories the
     * scan found are the only thing there is to act on, and a name alone says nothing about which
     * of them they were. Naming an input is how the clash is settled either way, and with a scan
     * the form is spelled out rather than left to the reader of the `<repo>` grammar.
     */
    private fun duplicateNames(inputs: List<MergeInput>): UsageError {
        val remedy = if (placement.scan == null) {
            " -- give one of them a name, with =<name> at the end of its ::<subdir> suffix (::=<name> " +
                "where it has none)"
        } else {
            " -- name a scanned repository by giving its directory as an argument, " +
                "e.g. '<base>/libs/core::=libs-core'"
        }
        val clashes = inputs.groupBy { it.name }.filterValues { it.size > 1 }.toSortedMap()
        val described = clashes.map { (name, sharing) ->
            val where = sharing.map { shownPath(it.location) }
            val listed = where.dropLast(1).joinToString(", ") + " and " + where.last()
            // Spelled out as far as the refusals around it spell things out, and a digit past that.
            val count = when (sharing.size) {
                2 -> "two"
                3 -> "three"
                else -> sharing.size.toString()
            }
            "$count inputs resolve to the same repository name '$name': $listed"
        }
        return UsageError(described.joinToString("; ") + remedy)
    }

    /**
     * The one diagnostic the grammar cannot give on its own.
     *
     * `<location>::<subdir>` is read as exactly that, so a path whose own name holds a `::` is read
     * as a location and a subdirectory — and when what follows happens to *be* a usable
     * subdirectory, nothing in the parse looks wrong. The run then fails with "no git repository at
     * <the location, cut short>" and never mentions the part it dropped.
     *
     * Both cases here are a location that is not on disk, and neither guesses at what was meant:
     *
     *  * the argument as written *is* a directory, which settles it — that is the repository, and
     *    the `::` in its name was read as a separator;
     *  * the argument as written is no directory either, which settles nothing. The run is going
     *    to fail on the location whichever reading was intended, so the only question is whether
     *    the failure mentions the text it dropped, and it costs nothing to say it here instead.
     *
     * Both quote that text as it was written, `::` and all, rather than as the parts it was read
     * into. An argument ending in the bare `::` dropped nothing, and has nothing to report here.
     *
     * What is on disk never changes the reading — this only refuses to go on, naming the remedy.
     */
    private fun checkSplit(spec: RepoSpec, raw: String) {
        if (spec.isRemote || !spec.split) return
        val whole = try {
            Path.of(raw)
        } catch (e: InvalidPathException) {
            return
        }
        val location = try {
            Path.of(spec.location)
        } catch (e: InvalidPathException) {
            return
        }
        if (location.exists()) return
        val dropped = raw.substring(spec.location.length)
        if (dropped == SEPARATOR) return
        throw UsageError(
            if (whole.isDirectory()) {
                "'$raw' is a directory, but its name holds a '::', so it was read as the location " +
                    "'${spec.location}' with '$dropped' as its suffix -- end the argument with '::' " +
                    "to mean the whole path"
            } else {
                "there is nothing at '${spec.location}', which is '$raw' with '$dropped' read off " +
                    "its end as the suffix -- end the argument with '::' if the location itself " +
                    "holds the '::'"
            }
        )
    }

    private fun report(result: MergeResult) {
        // The report is the last thing said and belongs to no phase, so it is set off from the one
        // that happened to finish before it; under --quiet no phase was printed to set it off from.
        if (!reporting.quiet) echo("", err = true)
        echo("mainline branch: ${result.braid.mainlineBranch}", err = true)

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
            val labels = result.braid.labelsSkipped.let { if (it > 0) ", $it labels skipped" else "" }
            val notesLeft = result.braid.notesSkipped.let { if (it > 0) ", $it notes skipped" else "" }
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
                result.braid.labelsSkipped.takeIf { it > 0 }?.let { "$it labels skipped" },
                result.braid.notesSkipped.takeIf { it > 0 }?.let { "$it notes skipped" },
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

/** A parsed `<path-or-url>[::[<subdir>][=<name>]]` positional argument. */
private class RepoSpec(
    val location: String,
    val isRemote: Boolean,
    /** Whether the argument held a `::` at all, which only a diagnostic needs — see [checkSplit]. */
    val split: Boolean,
    /** The name written after `=`, or the one implied by the subdirectory or the location. */
    val name: String,
    /**
     * Explicit `::<subdir>`, or `null` to place the repository under its own name. It may be a
     * nested path (`libs/backend`); what this platform can spell as a path segment is checked here,
     * and every rule about what git will store and check out is the planner's — `.git` at any
     * level, and two repositories placed inside each other.
     */
    val subdir: String?,
)

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
 * Splits `<path-or-url>[::[<subdir>][=<name>]]`.
 *
 * The subdirectory and the name answer separate questions. The subdirectory is merely where the
 * content lands. The name is the repository's identity — the tag prefix, the branch prefix, the
 * provenance label, what `--root-repo` matches — and has to be unique, which is the only way two
 * inputs whose directories happen to share a name can be merged at all.
 *
 * The subdirectory comes first because placing an input is what most arguments do, and the name
 * follows from it unless it is given: `::apps/webui` places and names in one token. The `=` is for
 * the case the two have to differ, which is a decision rather than an afterthought.
 *
 * The grammar is anchored on the **last** `::` in the argument, and there is nothing else to it.
 * Everything before that is the location, taken verbatim; everything after it is the subdirectory
 * and the name. Nothing is guessed and nothing is guarded: an argument that cannot be read this way
 * is refused, never quietly reread.
 *
 * Two consequences are the whole reason for this shape:
 *
 * - The location needs no escaping and can hold anything, `=` and `::` included, because appending
 *   `::` always ends it exactly where it ends. (If the location ends in *k* colons, the argument
 *   ends in *k+2*, so the last `::` starts at the location's length — for every location there is.)
 * - The suffix holds no `:` at all, because one is refused rather than escaped. It cannot then hold
 *   a `::` of its own, so the last one in the argument is always the separator.
 *
 * Refusing the colon costs less than escaping it would, and not only in a parser nobody reads: a
 * `:` is illegal in a git ref name, so a name holding one could never become the tag prefix it
 * exists to be. There was nothing on the other side of that trade.
 *
 * Nor can a name hold a `/`, a written `=`, or anything else git refuses in a ref name; the `/` is
 * not a gap either: the name is a directory name for the clone of a remote input and a segment of a
 * tag, and single-segment is what it means.
 */
private fun parseRepoSpec(raw: String): RepoSpec {
    val at = raw.lastIndexOf(SEPARATOR)
    val location = if (at < 0) raw else raw.substring(0, at)
    val suffix = if (at < 0) "" else raw.substring(at + SEPARATOR.length)

    if (at >= 0) refuseOpenBracket(location, raw)

    val (subdirText, nameText) = splitAtEquals(suffix)
    val subdir = subdirText.ifEmpty { null }?.also { text ->
        refuseSeparators(text, "subdirectory", raw)
        if (!text.split('/').all(::isOneSegment)) {
            throw UsageError("'$text' is not a usable subdirectory (in '$raw')" + REMEDY)
        }
    }
    val name = nameText?.also { text ->
        if (text.isEmpty()) {
            throw UsageError(
                "'$raw' names no repository after its '=' (leave the '=' out to name it after " +
                    "the subdirectory)" + REMEDY
            )
        }
        refuseSeparators(text, "name", raw)
        if (!isOneSegment(text)) throw unusableName(text, raw, fromSuffix = true)
    }

    val remote = isRemoteLocation(location)
    // The name is looked for in the argument, then in the subdirectory it was given, and only then
    // in the location — each source being what the writer said most recently about this input.
    val derived = name
        ?: subdir?.substringAfterLast('/')
        ?: if (remote) repoNameFromLocation(location)
        else SourceRepository.defaultName(localPath(location, raw))
    // The name becomes the default subdirectory and, under the default templates, the tag prefix,
    // the branch prefix and the provenance label, so an input that yields none is rejected here
    // rather than failing later as an unusable subdirectory.
    if (derived.isEmpty()) throw UsageError("cannot work out a repository name from '$raw'")
    // It also becomes a directory name: a remote input is cloned into `<clone root>/<name>.git`.
    // `Path.resolve` on a name that is rooted or carries a separator leaves the clone root instead
    // of descending into it, so such a name is refused before anything is written anywhere.
    if (!isOneSegment(derived)) throw unusableName(derived, raw, fromSuffix = false)
    if (!isRefComponent(derived)) {
        throw unusableRefName(derived, raw, fromSuffix = name != null || subdir != null)
    }
    return RepoSpec(location, remote, at >= 0, derived, subdir)
}

/** The `::` that separates the location from the suffix — see [parseRepoSpec]. */
private const val SEPARATOR = "::"

/**
 * An unusable name, and — when the argument wrote one — the way out if it never meant to.
 *
 * A location holding a `::` (an IPv6 URL, a directory somebody named that way) is read as a location
 * and a suffix, which is what the grammar says and cannot be guessed around. Naming the remedy is
 * what keeps that from being a dead end, because the remedy is not obvious: end the argument with
 * `::` and the whole of it is the location.
 *
 * It is only offered for a name that came from the suffix, though. A name *derived* from the
 * location is unusable for its own reasons — a last segment of `..`, a trailing separator — and
 * there the remedy would be advice that does not apply, either because the argument holds no `::`
 * at all or because it already ends in one.
 */
private fun unusableName(name: String, raw: String, fromSuffix: Boolean): UsageError {
    val said = "'$name' is not a usable name (in '$raw')"
    return UsageError(if (fromSuffix) said + REMEDY else said)
}

/**
 * `-b` values as the `--ref` patterns they are shorthand for.
 *
 * The reader refuses a malformed value with an [IllegalArgumentException], the way it does for
 * every other caller. It becomes a [UsageError] here because the request is built before `run`'s
 * own catch, so an unwrapped one would reach the user as a stack trace rather than as the usage
 * error every other mistyped argument gets.
 */
private fun branchPatterns(values: List<String>): List<String> =
    try {
        CommitGraphReader.words(values, "-b").map(CommitGraphReader::branchPattern)
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "a -b value could not be read")
    }

/**
 * A name git would not accept inside a ref.
 *
 * Said here rather than left to the write, which is where it used to surface: the first ref
 * carrying the name, an input's tag under the default prefix, was refused once the braid was
 * written, and an input no such ref carried went through.
 */
private fun unusableRefName(name: String, raw: String, fromSuffix: Boolean): UsageError {
    val said = "'$name' cannot be a repository name (in '$raw'): it becomes a tag prefix, and " +
        "git will not have it in a ref name"
    // A name the suffix gave, or took from its subdirectory, is refused like the suffix's other
    // parts; one derived from the location is the one case where giving a name is the way out.
    return UsageError(
        if (fromSuffix) said + REMEDY
        else "$said -- give the input a name, with =<name> at the end of its ::<subdir> suffix (::=<name> " +
            "where it has none)"
    )
}

/**
 * Refuses a location cut short inside a bracketed host.
 *
 * Both spellings git accepts for a literal IPv6 address carry a `::`, so the last one in
 * `https://[fe80::1]/repo.git` falls inside the address: the argument reads as a location of
 * `https://[fe80` and a subdirectory of `1]/repo.git`, a parse with nothing visibly wrong about it
 * that then fails on a location nobody wrote. The bracket left open is proof rather than a guess —
 * no location ends inside one — so this refuses and names the remedy without changing the reading.
 *
 * It earns its place only since the suffix took `/`: a suffix that had to be a single segment
 * refused `1]/repo.git` on its own, and this is what that refusal used to do.
 */
private fun refuseOpenBracket(location: String, raw: String) {
    val opened = location.lastIndexOf('[')
    if (opened >= 0 && location.indexOf(']', opened) < 0) {
        throw UsageError("'$location' is not a usable location (in '$raw'): it stops inside a '['" + REMEDY)
    }
}

/**
 * What to do when the `::` a refusal is about was never meant as the separator.
 *
 * Appended to every refusal that comes out of the suffix, because for all of them the likeliest
 * cause is the same and the way out is not obvious.
 */
private const val REMEDY =
    " -- if that '::' belongs to the location, end the argument with '::' to say so, and add " +
        "'=<name>' where its own last segment cannot be a ref name"

/**
 * [suffix] split at its first `=`: the subdirectory, and the name or `null` when there is no `=`.
 */
private fun splitAtEquals(suffix: String): Pair<String, String?> {
    val at = suffix.indexOf('=')
    return if (at < 0) suffix to null else suffix.substring(0, at) to suffix.substring(at + 1)
}

/**
 * Refuses the two characters the suffix cannot hold, naming [part] and [raw].
 *
 * Both are refused rather than escaped, and the grammar rests on the first of them: with no colon
 * anywhere in a suffix, a suffix cannot hold a `::`, so the last `::` in the argument is always the
 * separator. An escape would have bought a name git could not use in a ref anyway.
 *
 * A second `=` reaches this only in the name, the first one having ended the subdirectory.
 */
private fun refuseSeparators(text: String, part: String, raw: String) {
    if (':' in text) throw UsageError("a ':' cannot appear in the $part (in '$raw')" + REMEDY)
    if ('=' in text) throw UsageError("a '=' cannot appear in the $part (in '$raw')" + REMEDY)
}

/**
 * Whether [name] can stand as one component of a ref name.
 *
 * The name becomes a tag prefix (`refs/tags/<name>/v1.2`), a branch prefix
 * (`refs/heads/<name>/wip`), and the namespace under `refs/remotes/` that `--keep-remotes` writes. A
 * spelling git will not accept in a ref is therefore not a quirk of taste but an argument that
 * cannot be carried out.
 * Asked of [TargetRepository.isRefName], the rule every ref the output is given is held to.
 */
private fun isRefComponent(name: String): Boolean =
    TargetRepository.isRefName(Constants.R_TAGS + name + "/x")

/**
 * [location] as a path, or a usage error naming it.
 *
 * A location the platform cannot spell as a path at all — `a::b::` on Windows, where a colon is
 * legal only in a drive letter — is the user's typo, not an internal failure, so it is reported the
 * way every other unusable argument is instead of as an `InvalidPathException` from inside the
 * parser.
 */
private fun localPath(location: String, raw: String): Path =
    try {
        Path.of(location)
    } catch (e: InvalidPathException) {
        throw UsageError("'$location' is not a usable path (in '$raw')")
    }

/**
 * Whether [name] can serve as a single directory name on this platform.
 *
 * Which characters that admits is the platform's business, not this parser's: a backslash is an
 * ordinary character in a POSIX filename and a separator in a Windows one, and Windows rejects a
 * handful of others outright. Asking [Path] is what keeps the rule the filesystem's own.
 */
private fun isOneSegment(name: String): Boolean {
    if (name.isBlank() || name == "." || name == "..") return false
    val path = try {
        Path.of(name)
    } catch (e: InvalidPathException) {
        return false
    }
    return !path.isAbsolute && path.nameCount == 1
}

/**
 * Where a repository named by [path] sits, for telling whether two paths name one: absolute,
 * normalized, and without a final `.git` segment, so `x` and `x/.git`, a working tree and its own
 * git directory, come out the same on either side. The `.git` ending a bare `x.git` is part of its
 * name, and stays.
 */
private fun repositoryPlace(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    return if (absolute.fileName?.toString() == ".git") absolute.parent ?: absolute else absolute
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
    gitDirOf(location)?.let { gitDir ->
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
 * The directories an input at [location] takes up ([placesOf]), and those of the git directory its
 * objects are fetched from: `TargetRepository.fetchFrom` names the input by its path normalized as
 * text, which JGit's local transport resolves with `FileKey.resolve`, a sibling `<location>.git` it
 * guesses included. The run reads the input in both places, so the output may meet neither.
 */
private fun inputPlacesOf(location: Path): Set<Path> {
    val places = placesOf(location).toMutableSet()
    RepositoryCache.FileKey.resolve(location.toAbsolutePath().normalize().toFile(), FS.DETECTED)?.let { gitDir ->
        places.addAll(placesOf(gitDir.toPath()))
    }
    return places
}

/**
 * The git directory an existing [location] is or holds, looked up as [SourceRepository.open] looks
 * an input up — the location itself when it is a repository, else its `.git`, a directory or a
 * file naming one elsewhere as a linked worktree's or a submodule's does — or `null`. Nothing is
 * guessed beside it, and the path is not normalized: a `..` past a symlink is the filesystem's.
 */
private fun gitDirOf(location: Path): File? {
    val dir = location.toAbsolutePath().toFile()
    if (RepositoryCache.FileKey.isGitRepository(dir, FS.DETECTED)) return dir
    if (!File(dir, ".git").exists()) return null
    val builder = FileRepositoryBuilder()
    dir.parentFile?.let { builder.addCeilingDirectory(it) }
    return builder.findGitDir(dir).gitDir
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

/** `scheme://…` or the scp-like `user@host:path` — anything git clones over the network. */
private val REMOTE_LOCATION = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://|^[^/\\]+@[^/\\]+:""")

private fun isRemoteLocation(location: String): Boolean = REMOTE_LOCATION.containsMatchIn(location)

/**
 * The repository name implied by a URL: its last path segment without a trailing `.git`.
 *
 * A backslash separates segments here as well as a slash, because a location can be a Windows path
 * wearing a URL scheme — `file://C:\repos\backend.git`, which is what concatenating `file://`
 * with an absolute path produces there. Cutting that at the colon of the drive letter would read the
 * name as `\repos\backend`, and the name is a directory name.
 */
private fun repoNameFromLocation(location: String): String {
    val trimmed = location.trimEnd('/', '\\')
    val lastSeparator = trimmed.lastIndexOfAny(charArrayOf('/', '\\', ':'))
    return trimmed.substring(lastSeparator + 1).removeSuffix(".git")
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
