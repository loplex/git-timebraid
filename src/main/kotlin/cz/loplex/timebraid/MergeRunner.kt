package cz.loplex.timebraid

import cz.loplex.timebraid.cli.Progress
import cz.loplex.timebraid.git.BraidInputs
import cz.loplex.timebraid.git.BraidWriter
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.GitCommand
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.WriteOptions
import cz.loplex.timebraid.git.WriteSummary
import cz.loplex.timebraid.plan.MergePlan
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.util.FS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** One input to the merge, as the CLI layer parsed it. */
class MergeInput(
    /** A local filesystem path or a remote URL, exactly as the user wrote it. */
    val location: String,
    val isRemote: Boolean,
    val name: String,
    /** Subdirectory in the output, or `null` for the repository placed at the root. */
    val subdir: String?,
)

/** Everything the runner needs, already validated by the CLI layer. */
class MergeRequest(
    val inputs: List<MergeInput>,
    val output: Path?,
    val force: Boolean,
    val bare: Boolean,
    val keepRemotes: Boolean,
    val orderBy: OrderBy,
    val mainlineBranch: String?,
    val branches: Set<String>?,
    /**
     * Glob patterns over full ref names whose ancestry may delay a braid commit. Empty means the
     * default scope, the mainline chains alone.
     */
    val interleaveRefs: List<String>,
    val writeOptions: WriteOptions,
    val dryRun: Boolean,
)

/** What the transfer that fills the output brought into it. */
class FetchSummary(val repositories: Int, val refs: Int)

/** What a run produced. [fetch] and [write] are `null` for a dry run. */
class MergeResult(
    val braid: BraidInputs,
    val plan: MergePlan,
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

    fun run(): MergeResult {
        val locations = resolveInputs()
        val sources = locations.map { SourceRepository.open(it.path, it.name) }
        try {
            progress.step("reading ${sources.size} repositories")
            val braid = CommitGraphReader.read(
                repositories = sources,
                orderBy = request.orderBy,
                mainlineBranch = request.mainlineBranch,
                branches = request.branches,
                interleaveRefs = request.interleaveRefs,
            )

            progress.step("planning the braid over ${braid.graph.size} commits")
            if (braid.interleaveTips.isNotEmpty()) {
                progress.detail(
                    "${braid.interleaveTips.size} refs opted into the interleave, so a merge can " +
                        "wait for them"
                )
            }
            val plan = braid.graph
                .braid(braid.heads, braid.interleaveTips)
                .plan(
                    // The strands are in the order the inputs were given, so the two are paired by
                    // position here rather than by asking a Source where it sits.
                    braid.graph.sources
                        .mapIndexed { index, source -> source to request.inputs[index].subdir }
                        .toMap()
                )

            val output = request.output
            if (request.dryRun || output == null) return MergeResult(braid, plan, null, null)

            val written = writeOutput(output, sources, braid, plan)
            if (request.keepRemotes) keepRemotes(output, locations)
            if (!request.bare) {
                progress.step("checking out ${braid.mainlineBranch}")
                git.checkout(output, braid.mainlineBranch)
            }
            return MergeResult(braid, plan, written.fetch, written.write)
        } finally {
            sources.forEach { it.close() }
        }
    }

    /**
     * Fills the output: every input's objects first, then the braid on top of them.
     *
     * The order is the one git forces. A commit cannot be written before the tree it points at
     * exists, and every tree the braid points at is an input's — so the transfer has to come first
     * and be complete. What it leaves behind, the refs it needed to name what to fetch, is dropped
     * once the output has refs of its own.
     */
    private fun writeOutput(
        output: Path,
        sources: List<SourceRepository>,
        braid: BraidInputs,
        plan: MergePlan,
    ): Written {
        TargetRepository.create(output, braid.mainlineBranch, request.force, request.bare).use { target ->
            val fetch = fetchInputs(target, sources, braid)

            progress.step("writing ${plan.commits.size} commits into $output")
            val write = BraidWriter(
                target = target,
                sources = sources,
                inputs = braid,
                plan = plan,
                options = request.writeOptions,
                mirrorRemotes = request.keepRemotes,
            ).write()

            target.dropFetchRefs()
            return Written(fetch, write)
        }
    }

    /** Fetches each input into [target], narrowed to the refs its strand was read from. */
    private fun fetchInputs(
        target: TargetRepository,
        sources: List<SourceRepository>,
        braid: BraidInputs,
    ): FetchSummary {
        var refs = 0
        for ((index, source) in sources.withIndex()) {
            val wanted = braid.sources[index].readRefs
            progress.step("fetching ${wanted.size} refs from ${source.name}")
            refs += target.fetchFrom(source, wanted)
        }
        return FetchSummary(sources.size, refs)
    }

    /**
     * Records each input as a remote of the output. The remote-tracking refs themselves are written
     * by [BraidWriter] along with everything else, so all that is left here is the configuration
     * that lets a later `git fetch <name>` pick up what the input has gained since.
     */
    private fun keepRemotes(output: Path, locations: List<LocalInput>) {
        for (input in locations) {
            progress.step("recording remote ${input.name}")
            git.addRemote(output, input.name, input.remote)
        }
    }

    /**
     * Every input as a local path. A remote input is cloned next to the output under
     * `.timebraid-clones/`, and a clone that is already there is refreshed rather than remade, so a
     * second run over the same URLs does not pay the download again. When there is no output to sit
     * beside (`--dry-run` with no `-o`), the clones go to a temporary directory instead.
     */
    private fun resolveInputs(): List<LocalInput> {
        if (request.inputs.none { it.isRemote }) {
            return request.inputs.map { LocalInput(Path.of(it.location), it.name, localRemote(it.location)) }
        }
        val root = cloneRoot().also { it.createDirectories() }
        return request.inputs.map { input ->
            if (!input.isRemote) {
                return@map LocalInput(Path.of(input.location), input.name, localRemote(input.location))
            }

            val dir = root.resolve("${input.name}.git")
            if (RepositoryCache.FileKey.isGitRepository(dir.toFile(), FS.DETECTED)) {
                progress.step("refreshing ${input.name} in $dir")
                git.fetch(dir)
            } else {
                progress.step("cloning ${input.location} into $dir")
                git.cloneMirror(input.location, dir)
            }
            LocalInput(dir, input.name, input.location)
        }
    }

    /**
     * A local input recorded as its own remote URL, made absolute.
     *
     * `git remote add` stores the string verbatim, and every later `git fetch` runs with the
     * *output* repository as its working directory — so a relative path, which is the ordinary way
     * to name a repository on the command line, would be resolved against the wrong directory and
     * the fetch would fail. A remote input keeps its URL, which needs no such treatment.
     */
    private fun localRemote(location: String): String =
        Path.of(location).toAbsolutePath().normalize().toString()

    private fun cloneRoot(): Path =
        request.output?.toAbsolutePath()?.parent?.resolve(CLONE_DIR)
            ?: Files.createTempDirectory("timebraid-clones-")

    /** An input resolved to a local repository, plus the location to record if `--keep-remotes`. */
    private class LocalInput(val path: Path, val name: String, val remote: String)

    /** The two halves of filling an output: what was fetched in, and what the braid wrote on top. */
    private class Written(val fetch: FetchSummary, val write: WriteSummary)

    private companion object {
        const val CLONE_DIR = ".timebraid-clones"
    }
}
