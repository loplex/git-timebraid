package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import cz.loplex.timebraid.MergeInput
import cz.loplex.timebraid.MergeRequest
import cz.loplex.timebraid.MergeResult
import cz.loplex.timebraid.MergeRunner
import cz.loplex.timebraid.git.CommitGraphReader
import cz.loplex.timebraid.git.GitCommandException
import cz.loplex.timebraid.git.OrderBy
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.WriteOptions
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Entry point of the `git-timebraid` CLI: parse and validate the options, hand a [MergeRequest] to
 * [MergeRunner], and turn what it returns into the closing report. All the orchestration — cloning,
 * reading, planning, writing — lives in the runner; this class is only the command line.
 */
class MergeCommand : CliktCommand(name = "git-timebraid") {

    /**
     * An input is written `<path-or-url>[=<subdir>]`, and an absolute path (or a URL) is a token that
     * clikt would otherwise try to read as a long option. Routing unknown option-shaped tokens to
     * the arguments instead lets the positional parser see the whole spec; [run] rejects a real stray
     * `-`/`--` token by hand so the usual protection against a mistyped option is kept.
     */
    override val treatUnknownOptionsAsArgs: Boolean = true

    init {
        versionOption(version()) { "git-timebraid version $it" }
    }

    private val output by option("-o", "--output").path()
        .help("Output repository (must not exist unless --force).")

    private val force by option("--force").flag()
        .help("Write into an existing output directory instead of refusing it (deletes nothing).")

    private val rootRepo by option("--root-repo")
        .help("Repository whose content lands at the output root instead of in a subdirectory.")

    private val mainlineBranch by option("--mainline-branch")
        .help(
            "Branch treated as the mainline in every input " +
                "(default: first of ${CommitGraphReader.MAINLINE_CANDIDATES.joinToString("/")} present in all)."
        )

    private val orderBy by option("--order-by")
        .choice("author" to OrderBy.AUTHOR, "committer" to OrderBy.COMMITTER)
        .default(OrderBy.COMMITTER)
        .help("Timestamp used to interleave the strands.")

    private val branches by option("-b", "--branch").multiple()
        .help("Recreate only these branches (repeatable; default: all).")

    private val tagPrefix by option("--tag-prefix").default("{repo}/")
        .help("Prefix prepended to every recreated tag; {repo} is substituted.")

    private val subjectPrefix by option("--subject-prefix").default("{subdir}: ")
        .help("Prefix prepended to every commit subject; {repo} and {subdir} are substituted.")

    private val provenance by option("--provenance").flag("--no-provenance", default = true)
        .help("Record each commit's original sha and parents in a trailer (default: on).")

    private val bare by option("--bare").flag("--no-bare", default = true)
        .help("Write a bare output repository (--no-bare checks out a working tree).")

    private val keepRemotes by option("--keep-remotes").flag()
        .help("Add each input as a remote of the output and fetch it under refs/remotes/<repo>/*.")

    private val dryRun by option("--dry-run").flag()
        .help("Compute and summarize the plan, write nothing.")

    private val planOut by option("--plan-out").path()
        .help("Dump the deterministic plan as text to this file.")

    private val quiet by option("-q", "--quiet").flag()
        .help("Only report errors.")

    private val verbose by option("-v", "--verbose").flag()
        .help("Print every git subprocess as it runs.")

    private val inputs by argument("repo")
        .help("Input repository, optionally with a target subdirectory: <path-or-url>[=<subdir>].")
        .multiple(required = true)

    override fun help(context: Context): String =
        "Merge several independent git repositories into one, braided together along the time axis."

    override fun run() {
        inputs.firstOrNull { it.startsWith("-") }?.let {
            throw UsageError("unknown option '$it' (put options before the input repositories)")
        }
        if (quiet && verbose) throw UsageError("--quiet and --verbose cannot be combined")
        if (output == null && !dryRun) {
            throw UsageError("-o/--output is required unless --dry-run is given")
        }

        val specs = inputs.map(::parseRepoSpec)
        val names = specs.map { it.name }
        if (names.toSet().size != names.size) {
            throw UsageError("two inputs resolve to the same repository name: ${names.sorted()}")
        }
        rootRepo?.let { root ->
            if (root !in names) throw UsageError("--root-repo '$root' is not one of the inputs")
        }

        val request = MergeRequest(
            inputs = specs.map { spec ->
                MergeInput(
                    location = spec.location,
                    isRemote = spec.isRemote,
                    name = spec.name,
                    subdir = if (spec.name == rootRepo) null else spec.subdir ?: spec.name,
                )
            },
            output = output,
            force = force,
            bare = bare,
            keepRemotes = keepRemotes,
            orderBy = orderBy,
            mainlineBranch = mainlineBranch,
            branches = branches.toSet().ifEmpty { null },
            writeOptions = WriteOptions(subjectPrefix, tagPrefix, provenance),
            dryRun = dryRun,
        )
        val progress = Progress(Progress.level(quiet, verbose)) { echo(it, err = true) }

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

    private fun report(result: MergeResult) {
        echo("mainline branch: ${result.braid.mainlineBranch}", err = true)
        echo(result.plan.summary())

        planOut?.let { file ->
            file.toAbsolutePath().parent?.createDirectories()
            file.writeText(result.plan.render())
            echo("plan written to $file", err = true)
        }

        result.write?.let { summary ->
            echo(
                "wrote ${summary.commits} commits, ${summary.trees} trees and " +
                    "${summary.contentObjects} content objects to $output",
                err = true,
            )
            echo(
                "refs: ${summary.branches} branches, ${summary.tags} tags, HEAD -> ${summary.head}",
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

/** A parsed `<path-or-url>[=<subdir>]` positional argument. */
private class RepoSpec(
    val location: String,
    val isRemote: Boolean,
    val subdir: String?,
    val name: String,
)

private fun parseRepoSpec(raw: String): RepoSpec {
    val separator = raw.lastIndexOf('=')
    // A trailing "=x" is a subdirectory only when x is a bare name; ":" or "/" mark it as part of a
    // path or URL instead (`host:path`, `.../a=b/c`).
    val hasSubdir = separator >= 0 &&
        raw.substring(separator + 1).let { it.isNotEmpty() && '/' !in it && ':' !in it }
    val locationPart = if (hasSubdir) raw.substring(0, separator) else raw
    val subdir = if (hasSubdir) raw.substring(separator + 1) else null
    if (subdir != null && subdir.isBlank()) {
        throw UsageError("'$subdir' is not a usable subdirectory name (in '$raw')")
    }

    val remote = isRemoteLocation(locationPart)
    val name =
        if (remote) repoNameFromLocation(locationPart)
        else SourceRepository.defaultName(Path.of(locationPart))
    return RepoSpec(locationPart, remote, subdir, name)
}

/** `scheme://…` or the scp-like `user@host:path` — anything git clones over the network. */
private val REMOTE_LOCATION = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://|^[^/\\]+@[^/\\]+:""")

private fun isRemoteLocation(location: String): Boolean = REMOTE_LOCATION.containsMatchIn(location)

/** The repository name implied by a URL: its last path segment without a trailing `.git`. */
private fun repoNameFromLocation(location: String): String {
    val trimmed = location.trimEnd('/')
    val lastSeparator = trimmed.lastIndexOfAny(charArrayOf('/', ':'))
    return trimmed.substring(lastSeparator + 1).removeSuffix(".git")
}
