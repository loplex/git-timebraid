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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * Entry point of the `git-timebraid` CLI: parse and validate the options, hand a [MergeRequest] to
 * [MergeRunner], and turn what it returns into the closing report. All the orchestration — cloning,
 * reading, planning, writing — lives in the runner; this class is only the command line.
 */
class MergeCommand : CliktCommand(name = "git-timebraid") {

    /**
     * An input is written `<path-or-url>[::[<name>][=<subdir>]]`, and an absolute path (or a URL)
     * is a token that clikt would otherwise try to read as a long option. Routing unknown
     * option-shaped tokens to the arguments instead lets the positional parser see the whole spec;
     * [run] rejects a real stray `-`/`--` token by hand so the usual protection against a mistyped
     * option is kept.
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
        .help(
            "Repository whose content lands at the output root instead of in a subdirectory, by " +
                "name (see <repo>::<name>).",
        )

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

    private val interleaveRefs by option("--interleave-ref").multiple()
        .help(
            "Let this ref's commits delay a mainline merge that merges them in, so the merge lands " +
                "by their time rather than by its own (repeatable; glob over full ref names, e.g. " +
                "refs/heads/release/*; '*' opts in everything). Off by default: only the mainlines " +
                "themselves decide where the strands interleave."
        )

    private val tagPrefix by option("--tag-prefix").default("{repo}/")
        .help("Prefix prepended to every recreated tag; {repo} is substituted.")

    private val subjectPrefix by option("--subject-prefix").default("{subdir}: ")
        .help("Prefix prepended to every commit subject; {repo} and {subdir} are substituted.")

    private val provenance by option("--provenance").flag("--no-provenance", default = true)
        .help("Record each commit's original sha and parents in a trailer (default: on).")

    private val bare by option("--bare").flag("--no-bare", default = true)
        .help("Write a bare output repository (--no-bare checks out a working tree).")

    private val keepRemotes by option("--keep-remotes").flag()
        .help(
            "Add each input as a remote of the output, its branches under refs/remotes/<repo>/* " +
                "pointing at the original commits."
        )

    private val dryRun by option("--dry-run").flag()
        .help("Compute and summarize the plan, write nothing.")

    private val planOut by option("--plan-out").path()
        .help("Dump the deterministic plan as text to this file.")

    private val quiet by option("-q", "--quiet").flag()
        .help("Only report errors.")

    private val verbose by option("-v", "--verbose").flag()
        .help("Print every git subprocess as it runs.")

    private val inputs by argument("repo")
        .help(
            "Input repository: <path-or-url>[::[<name>][=<subdir>]]. Everything before the last " +
                "'::' is the location, verbatim; append a bare '::' when the location itself " +
                "holds one. The name is the repository's identity (tag prefix, provenance, " +
                "--root-repo) and defaults to the last segment of the location, which an empty " +
                "name asks for. The subdirectory is where the content lands, may be nested " +
                "(::=libs/backend), and defaults to the name. In both, '\\' escapes and a ':' " +
                "has to be written '\\:'.",
        )
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
        for ((spec, raw) in specs.zip(inputs)) checkSplit(spec, raw)

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
            interleaveRefs = interleaveRefs,
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

    /**
     * The one diagnostic the grammar cannot give on its own.
     *
     * `<location>::<name>` is read as exactly that, so a directory whose own name holds a `::` is
     * read as a location and a name — and when what follows happens to *be* a usable name, nothing
     * in the parse looks wrong. The run then fails with "no git repository at <the location, cut
     * short>" and never mentions the part it dropped.
     *
     * This says the rest of it in the one case where it can be shown rather than guessed: the
     * argument as written is a directory and the location alone is not there at all. The reading is
     * never changed by what is on disk — this only refuses to go on, naming the remedy.
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
        if (!whole.isDirectory() || location.exists()) return
        throw UsageError(
            "'$raw' is a directory, but its name holds a '::', so it was read as the location " +
                "'${spec.location}' with the name '${spec.name}' — end the argument with '::' to " +
                "mean the whole path"
        )
    }

    private fun report(result: MergeResult) {
        echo("mainline branch: ${result.braid.mainlineBranch}", err = true)
        echo(result.plan.summary())

        planOut?.let { file ->
            file.toAbsolutePath().parent?.createDirectories()
            file.writeText(result.plan.render())
            echo("plan written to $file", err = true)
        }

        result.fetch?.let { fetch ->
            echo(
                "fetched ${fetch.refs} refs from ${fetch.repositories} repositories into $output",
                err = true,
            )
        }

        result.write?.let { summary ->
            echo(
                "wrote ${summary.commits} commits and ${summary.trees} trees on top",
                err = true,
            )
            val remotes =
                if (summary.remoteBranches > 0) ", ${summary.remoteBranches} remote-tracking" else ""
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
     * nested path (`libs/backend`); what makes a *segment* usable is checked here, because it is a
     * question about this platform's filenames, and what makes the path as a whole usable is left
     * to the planner, which owns the rule that two repositories cannot be placed inside each other.
     */
    val subdir: String?,
)

/**
 * Splits `<path-or-url>[::[<name>][=<subdir>]]`.
 *
 * The name and the subdirectory are separate because they answer separate questions. The name is
 * the repository's identity — the tag prefix, the provenance label, the qualifier on a branch two
 * inputs share, what `--root-repo` matches — and has to be unique, which is the only way two inputs
 * whose directories happen to share a name can be merged at all. The subdirectory is merely where
 * the content lands, and defaults to the name without being tied to it.
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
 * - The name and the subdirectory can hold anything too, because a colon is written `\:` there and
 *   a bare one is refused. An encoded suffix therefore never holds a literal `::`, so the last one
 *   in the argument is always the separator this parser wrote about.
 *
 * The one thing no name can hold is a `/`, which is not a gap: the name is a directory name for the
 * clone of a remote input and a segment of a tag, and single-segment is what it means.
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
                    "repository under its own name)"
            )
        }
        if (!decoded.split('/').all(::isOneSegment)) {
            throw UsageError("'$decoded' is not a usable subdirectory (in '$raw')")
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
 * What to do when the `::` a refusal is about was never meant as the separator.
 *
 * Appended to every refusal that comes out of the suffix, because for all of them the likeliest
 * cause is the same and the way out is not obvious.
 */
private const val REMEDY =
    " — if that '::' belongs to the location, end the argument with '::' to say so"

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
 * [location] as a path, or a usage error naming it.
 *
 * A location the platform cannot spell as a path at all — `a::b` on Windows, where a colon is legal
 * only in a drive letter — is the user's typo, not an internal failure, so it is reported the way
 * every other unusable argument is instead of as an `InvalidPathException` from inside the parser.
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
