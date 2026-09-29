package cz.loplex.timebraid.cli

import com.github.ajalt.clikt.core.UsageError
import cz.loplex.timebraid.git.SourceRepository
import cz.loplex.timebraid.git.TargetRepository
import org.eclipse.jgit.lib.Constants
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** A parsed `<path-or-url>[::[<subdir>][=<name>]]` positional argument. */
internal class RepoSpec(
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
    /** The name as the argument wrote it after `=`, or `null` where it wrote none. */
    val given: String? = null,
) {
    /**
     * The argument this is read from: the exact inverse of [parseRepoSpec], so that changing one
     * field and formatting the rest back keeps every part the argument wrote.
     */
    fun format(): String =
        if (!split) location else location + SEPARATOR + (subdir ?: "") + (given?.let { "=$it" } ?: "")
}

/**
 * The way out a refusal about one input offers, spelled from the argument as it was written.
 *
 * Every form is [RepoSpec.format] over that argument with the field the refusal is about changed,
 * so a remedy keeps the parts it is not about — a subdirectory the argument gave, a location
 * holding a `::` — where one spelled by hand drops them. [moved] also writes out the input's
 * name, which the argument may have left to be derived.
 */
internal class InputRemedy(
    private val location: String,
    private val subdir: String? = null,
    /** The input's name, which [moved] keeps; `null` where none is known. */
    private val name: String? = null,
) {

    constructor(spec: RepoSpec) : this(spec.location, spec.subdir, spec.name)

    /** The argument with a name given, quoted: `'<location>::<subdir>=<name>'`. */
    fun named(): String = quoted(RepoSpec(location, false, split = true, NAME, subdir, given = NAME))

    /**
     * The argument with another subdirectory and the input's name, quoted: `'<location>::<subdir>=<name>'`,
     * or `'<location>::<subdir>'` where no name is known. The name is spelled out because without it
     * the name would follow from the new subdirectory, and moving the input would rename it.
     */
    fun moved(): String = quoted(RepoSpec(location, false, split = true, name ?: SUBDIR, SUBDIR, given = name))

    /**
     * What to do when the `::` a refusal is about was never meant as the separator: the whole of
     * the argument, here the location, ended by a bare `::` — and with a name, where its own last
     * segment cannot be one.
     */
    fun wholeLocation(): String =
        "if that '::' belongs to the location, end the argument with '::' to say so, as " +
            "${quoted(RepoSpec(location, false, split = true, "", null))}, or " +
            "${InputRemedy(location).named()} where its own last segment cannot be a ref name"

    private fun quoted(spec: RepoSpec) = "'${spec.format()}'"

    private companion object {
        const val NAME = "<name>"
        const val SUBDIR = "<subdir>"
    }
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
internal fun parseRepoSpec(raw: String): RepoSpec {
    val at = raw.lastIndexOf(SEPARATOR)
    val location = if (at < 0) raw else raw.substring(0, at)
    val suffix = if (at < 0) "" else raw.substring(at + SEPARATOR.length)

    if (at >= 0) refuseOpenBracket(location, raw)

    val (subdirText, nameText) = splitAtEquals(suffix)
    val subdir = subdirText.ifEmpty { null }?.also { text ->
        refuseSeparators(text, "subdirectory", raw)
        if (!text.split('/').all(::isOneSegment)) {
            throw UsageError("'$text' is not a usable subdirectory (in '$raw') -- " + InputRemedy(raw).wholeLocation())
        }
    }
    val name = nameText?.also { text ->
        if (text.isEmpty()) {
            throw UsageError(
                "'$raw' names no repository after its '=' (leave the '=' out to name it after " +
                    "the subdirectory) -- " + InputRemedy(raw).wholeLocation()
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
    val spec = RepoSpec(location, remote, at >= 0, derived, subdir, name)
    if (!isRefComponent(derived)) throw unusableRefName(spec, raw)
    return spec
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
    return UsageError(if (fromSuffix) "$said -- " + InputRemedy(raw).wholeLocation() else said)
}

/**
 * A name git would not accept inside a ref.
 *
 * Said here rather than left to the write, which is where it used to surface: the first ref
 * carrying the name, an input's tag under the default prefix, was refused once the braid was
 * written, and an input no such ref carried went through.
 *
 * Giving the input a name is the way out whichever part the name came from, and the remedy spells
 * it on the argument as written, its subdirectory kept: `::a..b` names the input `a..b` and places
 * it at `a..b/`, and only `::a..b=<name>` keeps the one while changing the other. A name the suffix
 * gave, or took from its subdirectory, is refused like the suffix's other parts too, since the `::`
 * may have belonged to the location.
 */
private fun unusableRefName(spec: RepoSpec, raw: String): UsageError {
    val said = "'${spec.name}' cannot be a repository name (in '$raw'): it becomes a tag prefix, and " +
        "git will not have it in a ref name -- give the input a name, as ${InputRemedy(spec).named()}"
    val fromSuffix = spec.given != null || spec.subdir != null
    return UsageError(if (fromSuffix) "$said; or, " + InputRemedy(raw).wholeLocation() else said)
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
        throw UsageError(
            "'$location' is not a usable location (in '$raw'): it stops inside a '[' -- " +
                InputRemedy(raw).wholeLocation()
        )
    }
}

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
    val remedy = " -- " + InputRemedy(raw).wholeLocation()
    if (':' in text) throw UsageError("a ':' cannot appear in the $part (in '$raw')$remedy")
    if ('=' in text) throw UsageError("a '=' cannot appear in the $part (in '$raw')$remedy")
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
internal fun isRefComponent(name: String): Boolean =
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
internal fun checkSplit(spec: RepoSpec, raw: String) {
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
                "'${spec.location}' with '$dropped' as its suffix -- " + InputRemedy(raw).wholeLocation()
        } else {
            "there is nothing at '${spec.location}', which is '$raw' with '$dropped' read off " +
                "its end as the suffix -- " + InputRemedy(raw).wholeLocation()
        }
    )
}
