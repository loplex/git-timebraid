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
 * The groups below exist for `--help`, which clikt renders one section per group. Twenty options in
 * one flat list make the required ones look like the rare ones; grouped, a reader meets them in the
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
    val mainlineBranch by option("--mainline-branch")
        .help(
            "Branch treated as the mainline in every input." + BR +
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
                "named, tags included."
        )

    val refs by option("--ref").multiple()
        .help(
            "Carry over only the refs matching this glob, branches and tags alike " +
                "(repeatable)." + BR +
                "Patterns are matched against full ref names." + BR +
                "Default: every ref."
        )

    val interleaveRefs by option("--interleave-ref").multiple()
        .help(
            "Let this ref's commits delay a mainline merge that merges them in (repeatable)." +
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
                    "not."
        )

    val tagPrefix by option("--tag-prefix").default("{repo}/")
        .help(
            "Prefix prepended to every recreated tag." + BR +
                "{repo} is substituted." + BR +
                "Default: \"{repo}/\""
        )

    val subjectPrefix by option("--subject-prefix").default("{subdir}: ")
        .help(
            "Prefix prepended to every commit subject." + BR +
                "{repo} and {subdir} are substituted." + BR +
                "Default: \"{subdir}: \""
        )

    val provenance by option("--provenance").flag("--no-provenance", default = true)
        .help(
            "Record each commit's original sha and parents in a trailer." + BR + "Default: on."
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
        .help("Print each git command the tool shells out to, as it runs.")
}

/**
 * Entry point of the `git-timebraid` CLI: parse and validate the options, hand a [MergeRequest] to
 * [MergeRunner], and turn what it returns into the closing report. All the orchestration — cloning,
 * reading, planning, writing — lives in the runner; this class is only the command line.
 */
class MergeCommand : CliktCommand(name = "git-timebraid") {

    /**
     * An input is written `<path-or-url>[::[<name>][=<subdir>]]`, and clikt reads a token that
     * opens with `/` and holds a `=`, an absolute path with a subdirectory, as a long option with
     * its value attached, and refuses it as unknown. Routing unknown option-shaped tokens to the
     * arguments instead lets the positional parser see the whole spec; [inputs] then refuses, as
     * clikt would, a token opening with `-` that stood before any `--`.
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
            "Everything before the last '::' is the location, taken verbatim." + BR +
                "Append a bare '::' when the location itself holds one.\n\n" +
                "<name> is the repository's identity: the tag prefix, the provenance label, and " +
                "what --root-repo matches." + BR +
                "Defaults to the last segment of the location.\n\n" +
                "<subdir> is where its content lands." + BR +
                "May be nested (::=libs/backend)." + BR +
                "Defaults to <name>.",
        )
        .transformAll(nvalues = -1, required = false) { tokens -> afterOptions(tokens) }

    override fun help(context: Context): String =
        "Merge several independent git repositories into one, braided together along the time " +
            "axis.\n\n" +
            "Each <repo> is written <path-or-url>[::[<name>][=<subdir>]]."

    override fun helpEpilog(context: Context): String =
        "More on each option, and what the output holds: " +
            "https://github.com/loplex/git-timebraid/blob/main/doc/usage.md"

    override fun run() {
        if (reporting.quiet && reporting.verbose) {
            throw UsageError("--quiet and --verbose cannot be combined")
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
            refs = history.branches.map(CommitGraphReader::branchPattern) + history.refs,
            interleaveRefs = history.interleaveRefs,
            splice = placement.splice,
            dissolveSubmodules = placement.dissolveSubmodules,
            writeOptions = WriteOptions(
                outputContent.subjectPrefix,
                outputContent.tagPrefix,
                outputContent.provenance,
            ),
            dryRun = reporting.dryRun,
        )
        val progress = Progress(Progress.level(reporting.quiet, reporting.verbose)) {
            echo(it, err = true)
        }

        val result = try {
            MergeRunner(request, progress).run()
        } catch (e: GitCommandException) {
            throw CliktError(e.message ?: "a git subprocess failed")
        } catch (e: IllegalStateException) {
            throw CliktError(e.message ?: "the merge could not be completed")
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message ?: "invalid input")
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
                        "a subdirectory with =<subdir> at the end of its ::<name> suffix " +
                        "(::=<subdir> where it has none) to move it off"
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
                "'$dir::<name>'"
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
            " -- give one of them a name, with ::<name> after its location and before any =<subdir>"
        } else {
            " -- name a scanned repository by giving its directory as an argument, " +
                "e.g. '<base>/libs/core::libs-core'"
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
     * `<location>::<name>` is read as exactly that, so a path whose own name holds a `::` is read
     * as a location and a name — and when what follows happens to *be* a usable name, nothing in
     * the parse looks wrong. The run then fails with "no git repository at <the location, cut
     * short>" and never mentions the part it dropped.
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
            echo(
                "refs: ${summary.branches} branches, ${summary.tags} tags$remotes, " +
                    "HEAD -> ${summary.head}",
                err = true,
            )
        }
    }

    /**
     * The project version Maven bakes into the jar manifest (`Implementation-Version`), or "dev"
     * when running from unpacked classes (an IDE, `mvn exec:java`).
     */
    private fun version(): String =
        MergeCommand::class.java.`package`?.implementationVersion ?: "dev"
}

/** A parsed `<path-or-url>[::[<name>][=<subdir>]]` positional argument. */
private class RepoSpec(
    val location: String,
    val isRemote: Boolean,
    /** Whether the argument held a `::` at all, which only a diagnostic needs — see [checkSplit]. */
    val split: Boolean,
    /** The name written after `::`, or the one implied by the location. */
    val name: String,
    /**
     * Explicit `=<subdir>`, or `null` to place the repository under its own name. It may be a
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
 * Splits `<path-or-url>[::[<name>][=<subdir>]]`.
 *
 * The name and the subdirectory are separate because they answer separate questions. The name is
 * the repository's identity — the tag prefix, the provenance label, the qualifier on a branch two
 * inputs share, what `--root-repo` matches — and has to be unique, which is the only way two inputs
 * whose directories happen to share a name can be merged at all. The subdirectory is where the
 * content lands, and what the default subject prefix names; it defaults to the name without being
 * tied to it.
 *
 * The grammar is anchored on the **last** `::` in the argument, and there is nothing else to it.
 * Everything before that is the location, taken verbatim; everything after it is the name and the
 * subdirectory, where `\` escapes and an empty name means the default one. Nothing is guessed and
 * nothing is guarded: an argument that cannot be read this way is refused, never quietly reread.
 *
 * Two consequences are the whole reason for this shape:
 *
 * - The location needs no escaping and can hold anything, `=` and `::` included, because appending
 *   `::` always ends it exactly where it ends. (If the location ends in *k* colons, the argument
 *   ends in *k+2*, so the last `::` starts at the location's length — for every location there is.)
 * - The suffix needs no care either, because a colon is written `\:` there and a bare one is
 *   refused. An encoded suffix therefore never holds a literal `::`, so the last one in the
 *   argument is always the separator this parser wrote about. The subdirectory can hold anything
 *   this way, and the name anything git takes in a ref.
 *
 * No name can hold a `/`, nor anything else git refuses in a ref name, which is not a gap: the name
 * is a directory name for the clone of a remote input and a segment of a tag, and one segment of a
 * ref is what it means.
 */
private fun parseRepoSpec(raw: String): RepoSpec {
    val at = raw.lastIndexOf(SEPARATOR)
    val location = if (at < 0) raw else raw.substring(0, at)
    val suffix = if (at < 0) "" else raw.substring(at + SEPARATOR.length)

    val (nameText, subdirText) = splitAtEquals(suffix)
    val name = decode(nameText, "name", raw).ifEmpty { null }
    if (name != null && !isOneSegment(name)) throw unusableName(name, raw, fromSuffix = true)
    val subdir = subdirText?.let { text ->
        val decoded = decode(text, "subdirectory", raw)
        if (decoded.isEmpty()) {
            throw UsageError(
                "'$raw' names no subdirectory after its '=' (leave the '=' out to place the " +
                    "repository under its own name)" + REMEDY
            )
        }
        if (!decoded.split('/').all(::isOneSegment)) {
            throw UsageError("'$decoded' is not a usable subdirectory (in '$raw')" + REMEDY)
        }
        decoded
    }

    val remote = isRemoteLocation(location)
    val derived = name
        ?: if (remote) repoNameFromLocation(location)
        else SourceRepository.defaultName(localPath(location, raw))
    // The name becomes the default subdirectory, the tag prefix and the provenance label, so an
    // input that yields none is rejected here rather than failing later as an unusable subdirectory.
    if (derived.isEmpty()) throw UsageError("cannot work out a repository name from '$raw'")
    // It also becomes a directory name: a remote input is cloned into `<clone root>/<name>.git`.
    // `Path.resolve` on a name that is rooted or carries a separator leaves the clone root instead
    // of descending into it, so such a name is refused before anything is written anywhere.
    if (!isOneSegment(derived)) throw unusableName(derived, raw, fromSuffix = false)
    if (!isRefComponent(derived)) throw unusableRefName(derived, raw, fromSuffix = name != null)
    return RepoSpec(location, remote, at >= 0, derived, subdir)
}

/** The `::` that separates the location from the name — see [parseRepoSpec]. */
private const val SEPARATOR = "::"

/** The characters a `\` stands in front of; anything else after one is two ordinary characters. */
private const val ESCAPABLE = "\\=:"

/**
 * An unusable name, and — when the argument wrote one — the way out if it never meant to.
 *
 * A location holding a `::` (an IPv6 URL, a directory somebody named that way) is read as a location
 * and a name, which is what the grammar says and cannot be guessed around. Naming the remedy is what
 * keeps that from being a dead end, because the remedy is not obvious: end the argument with `::`
 * and the whole of it is the location.
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
 * A name git would not accept inside a ref.
 *
 * Said here rather than left to the write, which is where it used to surface: the first ref
 * carrying the name, an input's tag under the default prefix, was refused once the braid was
 * written, and an input no such ref carried went through.
 */
private fun unusableRefName(name: String, raw: String, fromSuffix: Boolean): UsageError {
    val said = "'$name' cannot be a repository name (in '$raw'): it becomes a tag prefix, and " +
        "git will not have it in a ref name"
    // A name the suffix gave is refused like the suffix's other parts; one derived from the
    // location is the one case where giving a name is the way out.
    return UsageError(
        if (fromSuffix) said + REMEDY
        else "$said -- give the input a name, with ::<name> after its location and before any =<subdir>"
    )
}

/**
 * What to do when the `::` a refusal is about was never meant as the separator.
 *
 * Appended to every refusal that comes out of the suffix, because for all of them the likeliest
 * cause is the same and the way out is not obvious.
 */
private const val REMEDY =
    " -- if that '::' belongs to the location, end the argument with '::' to say so"

/**
 * [suffix] split at the first `=` that is not escaped: the name, and the subdirectory or `null`
 * when there is no `=` at all.
 */
private fun splitAtEquals(suffix: String): Pair<String, String?> {
    var i = 0
    while (i < suffix.length) {
        val c = suffix[i]
        if (c == '\\' && i + 1 < suffix.length && suffix[i + 1] in ESCAPABLE) {
            i += 2
            continue
        }
        if (c == '=') return suffix.substring(0, i) to suffix.substring(i + 1)
        i++
    }
    return suffix to null
}

/**
 * [text] with its escapes resolved, or a usage error naming [part] and [raw].
 *
 * A bare `:` is refused rather than passed through, and that refusal is what the grammar rests on:
 * with every colon written `\:`, an encoded name or subdirectory cannot hold a literal `::`, so the
 * last `::` in the argument is always the separator. Accepting a lone colon here would make that
 * only true of arguments nobody wrote carelessly.
 */
private fun decode(text: String, part: String, raw: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            out.append(text[i + 1])
            i += 2
            continue
        }
        if (c == ':') {
            throw UsageError("a ':' in the $part has to be written '\\:' (in '$raw')" + REMEDY)
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * Whether [name] can stand as one component of a ref name.
 *
 * The name becomes a tag prefix (`refs/tags/<name>/v1.2`), the qualifier on a branch two inputs
 * share, and the namespace under `refs/remotes/` that `--keep-remotes` writes. A spelling git will
 * not accept in a ref is therefore not a quirk of taste but an argument that cannot be carried out.
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
