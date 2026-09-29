package cz.loplex.timebraid

import cz.loplex.timebraid.cli.Progress
import cz.loplex.timebraid.git.BraidInputs
import cz.loplex.timebraid.git.BraidWriter
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.GitCommand
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.Relocation
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.SpliceCheck
import cz.loplex.timebraid.git.SpliceReport
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.WriteOptions
import cz.loplex.timebraid.git.WriteSummary
import cz.loplex.timebraid.plan.Source
import cz.loplex.timebraid.plan.MergePlan
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * One input to the merge, as the CLI layer parsed it and the filesystem resolved it: once, on the
 * command line, before anything is cloned or read.
 */
class ResolvedInput(
    /**
     * A local filesystem path or a remote URL as the user wrote it, or, for a repository `--scan`
     * found, the absolute path it was found at.
     */
    val location: String,
    val name: String,
    /**
     * Where the content lands in the output — one name or a nested path — or `null` for the
     * repository placed at the root.
     */
    val subdir: String?,
    /** Where a local input is on disk, or `null` for a remote one, which is nowhere until cloned. */
    val local: LocalPlace?,
) {
    val isRemote: Boolean get() = local == null
}

/** A local input's repository, as the filesystem has it. */
class LocalPlace(
    /**
     * The git directory's real path, so `core`, `core/.git` and a symlink to either are one
     * directory: what tells two inputs, or an input and the output, apart.
     */
    val gitDir: Path,
    /**
     * The location's real path, which the input is opened and fetched from, so a symlink or a `..`
     * past one leads both to the same directory.
     */
    val path: Path,
    /**
     * What `--keep-remotes` records as the input's URL: the location as [SourceRepository.absolute]
     * makes it, a symlink in it kept as written unless the text leads somewhere other than [path],
     * as a `..` past a symlink can; that one is recorded as [path] itself.
     */
    val remote: String,
)

/** Everything the runner needs, already validated by the CLI layer. */
class MergeRequest(
    val inputs: List<ResolvedInput>,
    val output: Path?,
    val force: Boolean,
    val bare: Boolean,
    val keepRemotes: Boolean,
    val orderBy: OrderBy,
    /**
     * Which branch each input braids along. A value may be scoped to one input as
     * `<input>::<branch>`; an unscoped one is the default for the rest and names the output's
     * branch. Empty leaves it to detection.
     */
    val mainlineBranch: List<String>,
    /**
     * Ref patterns, as `--ref` takes them, selecting which of each input's refs are loaded and
     * recreated. Empty selects every branch and tag, which is the default.
     */
    val refs: List<String>,
    /**
     * Glob patterns over full ref names, scoped as [refs] are, whose ancestry may delay a braid
     * commit. Empty means the default scope, the mainline chains alone.
     */
    val interleaveRefs: List<String>,
    /**
     * Glob patterns over full ref names, scoped as [refs] are, recreated when the run already holds
     * their target. Empty matches nothing. Reads nothing extra and never weighs on the braid — see
     * [CommitGraphReader.read].
     */
    val labelRefs: List<String>,
    /**
     * Whether every input's `refs/notes/` is carried over, rekeyed onto the commits this run writes.
     * Off by default — see [CommitGraphReader.read].
     */
    val notes: Boolean,
    /** Whether one input's destination may lie inside another's, the two spliced into one tree. */
    val splice: Boolean,
    /**
     * Whether an input landing on a gitlink of the repository around it replaces that gitlink with
     * its own content, rather than colliding with it — see [cz.loplex.timebraid.git.TreeAssembler].
     */
    val dissolveSubmodules: Boolean,
    val writeOptions: WriteOptions,
    val dryRun: Boolean,
)

/** What the transfer that fills the output brought into it. */
class FetchSummary(val repositories: Int, val refs: Int)

/** What a run produced. [fetch] and [write] are `null` for a dry run. */
class MergeResult(
    val inputs: BraidInputs,
    val plan: MergePlan,
    /**
     * One per splice the plan makes, each checked against every tree before anything was written
     * into the output. An input can lie inside more than one other along the braid, and then has
     * one for each — see [SpliceCheck.check].
     */
    val splices: List<SpliceReport>,
    val fetch: FetchSummary?,
    val write: WriteSummary?,
)

/**
 * Ties the whole pipeline together: make every input a local repository (cloning the remote ones),
 * read them into one graph, plan the braid, and — unless this is a dry run — fetch the inputs into
 * the output and write the braid on top, optionally keeping the inputs as remotes and checking out a
 * working tree.
 *
 * This is the only place that knows the order of those steps. Everything it calls is either pure
 * (the planner) or a narrow git wrapper ([SourceRepository], [TargetRepository], [GitCommand]).
 */
class MergeRunner(
    private val request: MergeRequest,
    private val progress: Progress,
) {

    private val git = GitCommand { progress.detail(it) }

    /** The directory [cloneRoot] made when there was no output to put the clones beside. */
    private var temporaryClones: Path? = null

    fun run(): MergeResult =
        try {
            merge()
        } finally {
            removeTemporaryClones()
        }

    private fun merge(): MergeResult {
        val localInputs = localInputs()
        // Opened inside the try, so that an input refused on opening closes the ones before it.
        val sources = ArrayList<SourceRepository>(localInputs.size)
        try {
            localInputs.mapTo(sources) { SourceRepository.open(it.path, it.name) }
            progress.phase("reading the inputs and planning the braid")
            val inputs = progress.whileWorking(
                "reading ${sources.size} repositories",
                finished = { "${sources.size} repositories, ${it.graph.size} commits" },
            ) {
                CommitGraphReader.read(
                    repositories = sources,
                    orderBy = request.orderBy,
                    mainlineBranch = request.mainlineBranch,
                    refs = request.refs,
                    interleaveRefs = request.interleaveRefs,
                    labelRefs = request.labelRefs,
                    notes = request.notes,
                )
            }

            if (inputs.labelsAttached > 0 || inputs.labelsSkipped > 0) {
                val skipped =
                    if (inputs.labelsSkipped == 0) ""
                    else ", skipping ${inputs.labelsSkipped} that matched nothing loaded"
                progress.result("${inputs.labelsAttached} refs attached by label$skipped")
            }
            if (inputs.notesAttached > 0 || inputs.notesSkipped > 0) {
                val skipped =
                    if (inputs.notesSkipped == 0) ""
                    else ", skipping ${inputs.notesSkipped} attached to objects this run did not write"
                progress.result("${inputs.notesAttached} notes rekeyed onto the new commits$skipped")
            }
            // Through detail and not result: this count is of what --interleave-ref asked for — the
            // commits its refs name, whose ancestry was allowed to widen the scope — and only a
            // reader tuning that option has a use for it.
            if (inputs.interleaveTips.isNotEmpty()) {
                progress.detail(
                    "${inputs.interleaveTips.size} commits opted into the interleave, so a merge can " +
                        "wait for them"
                )
            }
            val plan = progress.whileWorking(
                "planning",
                finished = { "${it.commits.size} commits planned, ${it.braid.size} on the braid" },
            ) {
                inputs.graph
                    .braid(inputs.heads, inputs.interleaveTips)
                    .plan(
                        // The strands are in the order the inputs were given, so the two are paired
                        // by position here rather than by asking a Source where it sits.
                        inputs.graph.sources
                            .mapIndexed { index, source -> source to request.inputs[index].subdir }
                            .toMap(),
                        splice = request.splice,
                    )
            }

            // The one place that pairs the two: these are the repositories handed to read() above,
            // and it gives back one SourceInputs per repository in the same order, each naming the
            // strand that repository became, so they are paired by position here, once. Everything
            // downstream looks a repository up by its Source and never has to know the order again.
            val repoOf = inputs.sources.map { it.source }.zip(sources).toMap()

            // Before anything is written into the output, and on a dry run too — a splice that
            // collides does so at one commit of the braid rather than at all of them, so a dry run
            // that skipped this would report a plan it cannot carry out.
            val splices = progress.whileWorking("checking the splices") {
                SpliceCheck(plan, inputs, repoOf, request.dissolveSubmodules, relocation).check()
            }
            for (splice in splices) {
                val dissolved =
                    if (splice.dissolved == 0) ""
                    else ", dissolving a submodule there at ${splice.dissolved} of them"
                progress.detail(
                    "${splice.splice.innerSubdir} lies inside ${splice.splice.outerPath}, " +
                        "spliced at ${splice.commits} commits over ${splice.trees} distinct " +
                        "trees$dissolved"
                )
            }

            val output = request.output
            // Before anything is written into the output, and on a dry run too. A remote the output
            // already records under an input's name is that input's own when its URL is the input's,
            // as on a rerun into the same output, and is left as it is; under another URL it is
            // someone else's, and `git remote add` would refuse it only once the braid was written.
            val remotes = if (request.keepRemotes && output != null) {
                TargetRepository.remotesOf(output, request.bare)
            } else {
                emptyMap()
            }
            for (input in localInputs) {
                if (input.name !in remotes) continue
                val url = remotes[input.name]
                require(url == input.remote) {
                    "the output already has a remote '${input.name}' at '$url', and --keep-remotes would " +
                        "record input '${input.name}' there as '${input.remote}' -- remove that remote from " +
                        "the output, or give the input another name, with =<name> at the end of its " +
                        "::<subdir> suffix (::=<name> where it has none)"
                }
            }
            if (request.dryRun || output == null) {
                return MergeResult(inputs, plan, splices, null, null)
            }

            val written = writeOutput(output, repoOf, inputs, plan)
            if (request.keepRemotes) keepRemotes(output, localInputs.filter { it.name !in remotes })
            if (!request.bare) {
                progress.phase("checking out ${inputs.mainlineBranch}")
                // Its own progress, drawn as it arrives. Reading the stream is what made it look
                // as though git had nothing to say here; it had, and now it is passed on.
                progress.gitProgress().use { git.checkout(output, inputs.mainlineBranch, it) }
            }
            return MergeResult(inputs, plan, splices, written.fetch, written.write)
        } finally {
            sources.forEach { it.close() }
        }
    }

    /**
     * The remedy a refusal about where an input lands offers, for the input at a destination: its
     * location with another subdirectory, the argument that moves it. A repository `--scan` found
     * is moved the same way, by naming its directory, which corrects the finding.
     */
    private val relocation = Relocation { destination ->
        request.inputs.firstOrNull { it.subdir == destination }
            ?.let { "give the repository at '$destination' another subdirectory, as '${it.location}::<subdir>'" }
            ?: Relocation.UNSPELLED.remedy(destination)
    }

    /**
     * Fills the output: the objects the refs read from each input reach first, then the braid on
     * top of them.
     *
     * The trees the braid writes are built from the inputs' trees and blobs, bar the root
     * `.gitmodules` it writes itself. Nothing checks those are there while the braid is written,
     * but a ref that reaches an object the repository does not hold leaves it broken — so the
     * transfer comes first, and is complete before any of the braid's refs is written. What it
     * leaves behind, the refs parked so that a later input's fetch leaves out the history an
     * earlier one brought, is dropped once the output has refs of its own.
     */
    private fun writeOutput(
        output: Path,
        repoOf: Map<Source, SourceRepository>,
        inputs: BraidInputs,
        plan: MergePlan,
    ): Written {
        // Opened before the output is created: creating it is the first thing the run does to the
        // output, and under `-v` it says so in a command line of its own, which belongs under this
        // heading rather than under the planning one before it.
        progress.phase("fetching the inputs into the output")
        TargetRepository.create(
            output,
            inputs.mainlineBranch,
            request.force,
            request.bare,
            log = { progress.detail(it) },
        ).use { target ->
            val fetch = fetchInputs(target, repoOf, inputs)

            progress.phase("writing the braid")
            val write = BraidWriter(
                target = target,
                repoOf = repoOf,
                inputs = inputs,
                plan = plan,
                options = request.writeOptions,
                mirrorRemotes = request.keepRemotes,
                dissolveSubmodules = request.dissolveSubmodules,
                relocation = relocation,
                onCommitWritten = progress.counter(plan.commits.size, "commits written"),
                publishing = { publish -> progress.whileWorking("publishing the refs", work = publish) },
            ).write()

            progress.whileWorking("dropping the refs the transfer parked") { target.dropFetchRefs() }
            return Written(fetch, write)
        }
    }

    /**
     * Fetches each input into [target], narrowed to the refs its strand was read from and, under
     * `--notes`, its notes refs.
     */
    private fun fetchInputs(
        target: TargetRepository,
        repoOf: Map<Source, SourceRepository>,
        inputs: BraidInputs,
    ): FetchSummary {
        var refs = 0
        for (input in inputs.sources) {
            val repo = repoOf.getValue(input.source)
            // The notes refs ride along: they contribute no commit, but the blobs a note is made
            // of have to be in the output before a tree of the output's own can point at one.
            val wanted = input.readRefs + input.noteRefs
            // Ahead of the transfer, because it says what is about to be asked for; what came of
            // it is what the transfer's own tasks leave behind.
            progress.result("[${repo.name}] ${wanted.size} refs")
            refs += target.fetchFrom(repo, wanted, progress.monitor(repo.name))
        }
        return FetchSummary(inputs.sources.size, refs)
    }

    /**
     * Records each of [localInputs] as a remote of the output; one the output records already, under
     * its own URL, is not among them. The remote-tracking refs themselves are written
     * by [BraidWriter] along with everything else, so all that is left here is the configuration
     * that lets a later `git fetch <name>` pick up what the input has gained since.
     */
    private fun keepRemotes(output: Path, localInputs: List<LocalInput>) {
        progress.phase("recording the inputs as remotes")
        for (input in localInputs) {
            git.addRemote(output, input.name, input.remote)
            progress.result("${input.name} -> ${input.remote}")
        }
    }

    /**
     * Every input as a local path. A remote input is cloned next to the output under
     * `.timebraid-clones/`, and a clone that is already there is refreshed rather than remade, so a
     * second run over the same URLs does not pay the download again. When there is no output to sit
     * beside (`--dry-run` with no `-o`), the clones go to a temporary directory instead, which the
     * run removes when it ends.
     */
    private fun localInputs(): List<LocalInput> {
        // A local input is used where the command line resolved it.
        fun LocalPlace.input(name: String) = LocalInput(path, name, remote)
        if (request.inputs.none { it.isRemote }) {
            return request.inputs.mapNotNull { input -> input.local?.input(input.name) }
        }
        progress.phase("making every input a local repository")
        val root = cloneRoot().also {
            try {
                it.createDirectories()
            } catch (e: IOException) {
                // Beside the output. The command line asked whether it could write there only for an -o
                // not there yet, so what fails here is the clones' own directory, a file in its place
                // say, or the parent of an -o that already exists. The refusal says which -o put the
                // clones there.
                throw IllegalArgumentException(
                    "cannot create '$it' for the remote inputs' clones, beside the output " +
                        "'${request.output}': ${e.message}",
                    e,
                )
            }
        }
        return request.inputs.map { input ->
            input.local?.let { return@map it.input(input.name) }

            val dir = root.resolve("${input.name}.git")
            if (RepositoryCache.FileKey.isGitRepository(dir.toFile(), FS.DETECTED)) {
                refuseOtherOrigin(dir, input)
                progress.gitProgress(input.name).use { git.fetch(dir, it) }
                progress.result("[${input.name}] refreshed in $dir")
            } else {
                progress.gitProgress(input.name).use { git.cloneMirror(input.location, dir, it) }
                progress.result("[${input.name}] cloned into $dir")
            }
            LocalInput(dir, input.name, input.location)
        }
    }

    /**
     * Refuses to refresh [dir] for [input] when it is a clone of another location.
     *
     * A clone is found by the input's name alone, and two locations can derive one name — two
     * forks' `webui`, for one. Refreshing the clone the other one left would braid a repository this
     * run never named, and report it as this input.
     */
    private fun refuseOtherOrigin(dir: Path, input: ResolvedInput) {
        val origin = FileRepositoryBuilder().setGitDir(dir.toFile()).build().use {
            it.config.getString("remote", "origin", "url")
        }
        require(origin == input.location) {
            "$dir is a clone of $origin, not of ${input.location}; remove that directory, or give " +
                "the input another name, with =<name> at the end of its ::<subdir> suffix (::=<name> " +
                "where it has none)"
        }
    }

    private fun cloneRoot(): Path =
        request.output?.toAbsolutePath()?.parent?.resolve(CLONE_DIR)
            ?: Files.createTempDirectory("timebraid-clones-").also { temporaryClones = it }

    /**
     * Deletes the clones a run made in a temporary directory. Nothing would ever reuse them: the
     * next run without an output makes a directory of its own, so each such run would leave a full
     * copy of every remote input behind.
     */
    private fun removeTemporaryClones() {
        val root = temporaryClones ?: return
        if (!root.toFile().deleteRecursively()) {
            progress.result("could not remove every clone under $root")
        }
    }

    /**
     * An input resolved to a local repository, plus the location to record if `--keep-remotes`.
     *
     * A local input's is the location made absolute, as [LocalPlace.remote] says: `git remote add`
     * stores the string verbatim, and every later `git fetch` runs with the *output* as its working
     * directory, so a relative path would be resolved against the wrong directory. A remote input
     * keeps its URL.
     */
    private class LocalInput(val path: Path, val name: String, val remote: String)

    /** The two halves of filling an output: what was fetched in, and what the braid wrote on top. */
    private class Written(val fetch: FetchSummary, val write: WriteSummary)

    private companion object {
        const val CLONE_DIR = ".timebraid-clones"
    }
}
