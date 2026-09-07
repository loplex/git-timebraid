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
import cz.loplex.timebraid.plan.BraidInterleave
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

/** What a run produced. [write] is `null` for a dry run. */
class MergeResult(
    val braid: BraidInputs,
    val plan: MergePlan,
    val write: WriteSummary?,
)

/**
 * Ties the whole pipeline together: make every input a local repository (cloning the remote ones),
 * read them into one graph, plan the braid, and — unless this is a dry run — write it out, optionally
 * keeping the inputs as remotes and checking out a working tree.
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
            val plan = MergePlan.build(
                graph = braid.graph,
                heads = braid.heads,
                subdirs = request.inputs.map { it.subdir },
                braid = BraidInterleave.compute(braid.graph, braid.heads, braid.interleaveTips),
            )

            val output = request.output
            if (request.dryRun || output == null) return MergeResult(braid, plan, null)

            val summary = writeOutput(output, sources, braid, plan)
            if (request.keepRemotes) keepRemotes(output, locations)
            if (!request.bare) {
                progress.step("checking out ${braid.mainlineBranch}")
                git.checkout(output, braid.mainlineBranch)
            }
            return MergeResult(braid, plan, summary)
        } finally {
            sources.forEach { it.close() }
        }
    }

    private fun writeOutput(
        output: Path,
        sources: List<SourceRepository>,
        braid: BraidInputs,
        plan: MergePlan,
    ): WriteSummary {
        progress.step("writing ${plan.commits.size} commits into $output")
        TargetRepository.create(output, braid.mainlineBranch, request.force, request.bare).use { target ->
            return BraidWriter(target, sources, braid, plan, request.writeOptions).write()
        }
    }

    private fun keepRemotes(output: Path, locations: List<LocalInput>) {
        for (input in locations) {
            progress.step("adding remote ${input.name}")
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
            return request.inputs.map { LocalInput(Path.of(it.location), it.name, it.location) }
        }
        val root = cloneRoot().also { it.createDirectories() }
        return request.inputs.map { input ->
            if (!input.isRemote) return@map LocalInput(Path.of(input.location), input.name, input.location)

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

    private fun cloneRoot(): Path =
        request.output?.toAbsolutePath()?.parent?.resolve(CLONE_DIR)
            ?: Files.createTempDirectory("timebraid-clones-")

    /** An input resolved to a local repository, plus the location to record if `--keep-remotes`. */
    private class LocalInput(val path: Path, val name: String, val remote: String)

    private companion object {
        const val CLONE_DIR = ".timebraid-clones"
    }
}
