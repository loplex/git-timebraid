package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.ArgumentTransformContext
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.transformAll
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
import cz.loplex.timebraid.git.TargetRepository
import cz.loplex.timebraid.git.WriteOptions
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
     * An input is written `<path-or-url>[::<name>][=<subdir>]`, and clikt reads a token that opens
     * with `/` and holds a `=`, an absolute path with a subdirectory, as a long option with its
     * value attached, and refuses it as unknown. Routing unknown option-shaped tokens to the
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
    }

    private val output by option("-o", "--output").path()
        .help(
            "Output repository (must not exist, or must be an empty directory; --force also takes a " +
                "non-empty one).",
        )

    private val force by option("--force").flag()
        .help(
            "Write into a non-empty output directory instead of refusing it, over whatever it already " +
                "holds.",
        )

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
        .help("Compute and summarize the plan, write no output.")

    private val planOut by option("--plan-out").path()
        .help("Dump the deterministic plan as text to this file.")

    private val quiet by option("-q", "--quiet").flag()
        .help("Say nothing but the closing report and any error.")

    private val verbose by option("-v", "--verbose").flag()
        .help("Print each git command the tool shells out to, as it runs.")

    private val inputs by argument("repo")
        .help(
            "Input repository: <path-or-url>[::<name>][=<subdir>]. The name is the repository's " +
                "identity (tag prefix, provenance, --root-repo) and defaults to the last segment " +
                "of the path; the subdirectory is where its content lands and defaults to the name.",
        )
        .transformAll(nvalues = -1, required = true) { tokens -> afterOptions(tokens) }

    override fun help(context: Context): String =
        "Merge several independent git repositories into one, braided together along the time axis."

    override fun run() {
        if (quiet && verbose) throw UsageError("--quiet and --verbose cannot be combined")
        if (output == null && !dryRun) {
            throw UsageError("-o/--output is required unless --dry-run is given")
        }
        // An empty path is the working directory to `Path` and nothing at all to `File`, so the
        // checks below would pass it and the creation fail on it, once every input was read.
        if (output?.toString()?.isEmpty() == true) {
            throw UsageError("-o/--output names no directory; give the path the output is written to")
        }
        // Asked now rather than left to the write: the plan is written after the output, and a path
        // that cannot take it would fail a run whose braid is already in place.
        planOut?.let { file ->
            unwritable(file)?.let { throw UsageError("--plan-out '$file' cannot be written: $it") }
        }

        val specs = inputs.map(::parseRepoSpec)
        val names = specs.map { it.name }
        if (names.toSet().size != names.size) {
            throw UsageError("two inputs resolve to the same repository name: ${names.sorted()}")
        }
        rootRepo?.let { root ->
            if (root !in names) throw UsageError("--root-repo '$root' is not one of the inputs")
        }
        // A local input that is the output would be read and written at once: fetched into itself,
        // then given the braid on top of its own history.
        output?.let { out ->
            val outputPlaces = placesOf(out, withGitDir = !bare)
            // A location this platform cannot spell as a path is no path the output could be.
            fun pathOf(location: String) = try { Path.of(location) } catch (e: InvalidPathException) { null }
            val input = specs.firstOrNull { spec ->
                !spec.isRemote && pathOf(spec.location)?.let { meet(inputPlacesOf(it), outputPlaces) } == true
            }
            input?.let {
                throw UsageError("'${it.location}' is the output (-o), which a run cannot braid into itself")
            }
            // Asked now rather than when the output is created, after every input is read and the
            // braid planned: a dry run never reaches that, and would pass an -o the real run refuses.
            // After the inputs, so that one of them being the output is said as that.
            TargetRepository.refusal(out, force, bare)?.let { throw CliktError(it) }
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

    private fun report(result: MergeResult) {
        echo("mainline branch: ${result.braid.mainlineBranch}", err = true)
        echo(result.plan.summary())

        planOut?.let { file ->
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
                "fetched ${fetch.refs} refs from ${fetch.repositories} repositories into $output",
                err = true,
            )
        }

        result.write?.let { summary ->
            echo(
                "wrote ${summary.commits} commits and ${summary.trees} root trees on top",
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

/** A parsed `<path-or-url>[::<name>][=<subdir>]` positional argument. */
private class RepoSpec(
    val location: String,
    val isRemote: Boolean,
    /** `::<name>` if given, otherwise the name implied by the location. */
    val name: String,
    /** Explicit `=<subdir>`, or `null` to place the repository under its own name. */
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
    return tokens.filter { it != END_OF_OPTIONS }.ifEmpty { throw MissingArgument(argument) }
}

/**
 * Splits `<path-or-url>[::<name>][=<subdir>]`.
 *
 * The two are separate because they answer separate questions. The name is the repository's
 * identity — the tag prefix, the provenance label, the qualifier on a branch two inputs share, what
 * `--root-repo` matches — and has to be unique, which is the only way two inputs whose directories
 * happen to share a name can be merged at all. The subdirectory is where the content lands, and
 * what the default subject prefix names; it defaults to the name without being tied to it.
 *
 * Each suffix is recognised only when what follows it is a bare word: a `/` or a `:` means the
 * character belonged to the location instead (`host:path`, `.../a=b/c`, `https://[::1]/repo`).
 */
private fun parseRepoSpec(raw: String): RepoSpec {
    val (beforeSubdir, subdir) = splitSuffix(raw, "=")
    val (location, name) = splitSuffix(beforeSubdir, "::")
    for (part in listOfNotNull(name, subdir)) {
        if (part.isBlank()) throw UsageError("'$part' is not a usable name (in '$raw')")
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
    if (!isOneSegment(derived)) throw UsageError("'$derived' is not a usable name (in '$raw')")
    return RepoSpec(location, remote, derived, subdir)
}

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

/** [raw] split at the last [marker] that is followed by a bare word, or the whole of it and `null`. */
private fun splitSuffix(raw: String, marker: String): Pair<String, String?> {
    val at = raw.lastIndexOf(marker)
    val suffix = if (at < 0) null else raw.substring(at + marker.length)
    if (suffix.isNullOrEmpty() || '/' in suffix || ':' in suffix) return raw to null
    return raw.substring(0, at) to suffix
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
