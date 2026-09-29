package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants

/*
 * The ref-pattern language every option naming refs shares: -b, --ref, --label-ref,
 * --interleave-ref and --mainline-branch. Parsed here, on the command line and before any input is
 * cloned or read, into the values [CommitGraphReader.read] takes; what a pattern matches in an
 * input, and what a ref it took is called in the output, are the reader's and [RefNames]'s.
 */

/** The two ref namespaces this program enumerates by default, and names a rule for. */
enum class RefKind(val namespace: String) {
    BRANCH(Constants.R_HEADS),
    TAG(Constants.R_TAGS);

    companion object {
        /** The kind a namespace names, or `null` when it is neither of the two. */
        fun of(namespace: String): RefKind? =
            entries.firstOrNull { it.namespace == namespace || it.namespace == "$namespace/" }
    }
}

/**
 * One glob, and the right half of the refspec it may carry.
 *
 * [destination] is `null` where the pattern only selects. Where it is given it says how much of
 * the output name the run is writing itself: a namespace (`refs/tags/`), leaving the rest to
 * that namespace's prefix; or a name holding a `*`, which is substituted with whatever the
 * glob's own `*` matched and leaves no room for a prefix at all; or a plain name, for the one
 * ref a pattern without a star can reach.
 *
 * [negated] is a pattern written with a leading `^`, as `^refs/heads/wip`, which subtracts
 * rather than selects: it takes back every ref it matches, whatever else brought them in. It
 * carries no destination, nothing landing that could be named, and it reads no namespace of its
 * own for the same reason.
 */
class RefPattern(
    val glob: String,
    val destination: String?,
    val negated: Boolean = false,
) {

    /** Whether the destination is a namespace to hand back to the prefix rules. */
    val toNamespace: Boolean =
        destination != null && !destination.contains('*') && destination.endsWith("/")

    /**
     * Where a ref this pattern matched is written, or `null` to leave it where it came from.
     *
     * [name] is the ref's full name in the input, which is what the glob matched, and [repo] and
     * [subdir] are the input's own name and destination, for a `{repo}` and a `{subdir}` in the
     * destination. Only the spelled-out forms are
     * resolved here; a namespace destination is [toNamespace] and [OutputName]'s business.
     */
    fun resolve(name: String, repo: String, subdir: String = repo): String? {
        if (destination == null || toNamespace) return null
        val spelled = destination.replace("{repo}", repo).replace("{subdir}", subdir)
        if (!spelled.contains('*')) return spelled
        return spelled.replace("*", captured(name))
    }

    /** What this pattern's own star matched in [name], which the destination's star receives. */
    private fun captured(name: String): String {
        val head = glob.substringBefore('*')
        val tail = glob.substringAfter('*')
        return name.substring(head.length, name.length - tail.length)
    }
}

/**
 * The patterns one option was given, each with the input it speaks for, written
 * `[<input>::][^]<refspec>` and parsed by [parse] on the command line before any input is read.
 *
 * **What follows the scope is git's refspec**, `<pattern>[:<destination>]`, so a value valid as a
 * git refspec means the same here: `refs/heads/main:refs/tags/main` reads the branch and writes it
 * as a tag. The differences are few and each on purpose: a destination may be a namespace
 * (`refs/tags/`) handed to that namespace's prefix, and may hold `{repo}` and `{subdir}`; a pattern
 * with stars may go without a destination, which `git fetch` allows only in a negative refspec, or
 * name one ref as its destination; and there is no `+`, no empty pattern or destination, and no
 * short name, every pattern matching full ref names.
 *
 * **The scope is ended by `::`**, the separator the `<repo>` grammar puts between a location and
 * its suffix: `backend::refs/heads/main` speaks for one input, and `refs/heads/main` for every one.
 * A scope cannot be empty — `::refs/…` is refused, the unscoped form already saying it. The
 * separator is decidable rather than a convention: a refspec holds at most one `:`, so never a
 * `::`.
 *
 * **The empty case stays per input, and keeps the meaning it had.** No pattern for an input is
 * that option's empty case for that input — every branch and tag for the selection, none for the
 * interleave and the labels. So `--ref backend::refs/heads/main` narrows backend and leaves the
 * other inputs carrying everything, which is the generalisation of *naming any ref leaves out
 * every ref not named* from the run to the input. An unscoped pattern narrows every input
 * exactly as before.
 *
 * **A `^` in front of the pattern subtracts instead of selecting**, the way git has written a
 * negative refspec since 2.29, and in the same place, before the refspec. It is decidable rather
 * than a convention, git refusing a `^` anywhere in a ref name. The mark goes after the scope
 * rather than in front of the whole value: `^backend::refs/heads/wip` would read as *not
 * backend*, which is a meaning this never has.
 *
 * @param inputs the inputs' names, in the order the run reads them, which is how a pattern's
 *   scope is looked up: [of] is asked by position.
 * @param emptyMeans what no pattern for an input says about that input — every branch and tag,
 *   or none. It is also what a run holding nothing but subtractions resolves against, which is
 *   why it is known here and not only in the reader's selection: where the empty case is no ref
 *   at all there is nothing to take back out, and such a run is refused rather than quietly
 *   matching nothing.
 */
class ScopedPatterns(
    entries: List<ScopedPattern>,
    option: String,
    inputs: List<String>,
    emptyMeans: Boolean,
) {
    private val unscoped = entries.filter { it.input == null }.map { it.pattern }
    private val byInput = entries.mapNotNull { entry -> entry.input?.let { it to entry.pattern } }
        .groupBy({ it.first }, { it.second })

    init {
        // Checked per input rather than per run, because the empty case is per input: an
        // unscoped subtraction and no positive anywhere leaves every input with nothing to take
        // it out of, while one input's scoped subtraction is answered by an unscoped positive.
        if (!emptyMeans) {
            for ((index, input) in inputs.withIndex()) {
                val patterns = of(index)
                require(patterns.isEmpty() || patterns.any { !it.negated }) {
                    "$option holds nothing but patterns that subtract for input '$input', and " +
                        "its empty case is no ref at all -- so there is nothing to take back " +
                        "out. Say what is opted in first, a star for all of it"
                }
            }
        }
    }

    /**
     * The patterns applying to the input at [input], the ones naming it first.
     *
     * The order only matters to a destination, where the first pattern that matches a ref
     * decides where it lands: a scoped pattern is the more specific of the two, so it is the one
     * that should win over an unscoped pattern covering the same ref.
     */
    fun of(input: Int): List<RefPattern> = (byInput[input] ?: emptyList()) + unscoped

    companion object {

        /** No pattern at all, for every input: each option's own empty case. */
        val NONE = ScopedPatterns(emptyList(), "", emptyList(), emptyMeans = true)

        /**
         * [raw], the values one option was given, as the patterns they spell.
         *
         * @param destinations whether a destination is meaningful at all. It is for the two options
         *   that write refs; for the one that decides what may weigh on the braid it would name a
         *   namespace nothing is ever written to, so it is refused rather than accepted and ignored.
         */
        fun parse(
            raw: List<String>,
            option: String,
            inputs: List<String>,
            destinations: Boolean = false,
        ): List<ScopedPattern> = words(raw, option).map { value ->
            val (input, written) = scopeOf(option, value, inputs)
            // A leading '^' is decidable rather than a convention: git refuses a '^' anywhere in a
            // ref name, so one here can only be the mark.
            val negated = written.startsWith('^')
            val fields = (if (negated) written.substring(1) else written).split(':')
            refuseFields(option, value, fields, inputs, negated, "<pattern>")
            val glob = fields[0]
            require(glob.isNotEmpty()) {
                if (negated) "$option '$value' subtracts no pattern" else "$option '$value' names no pattern"
            }
            require(!glob.contains('^')) {
                "$option '$value' holds a '^' inside its pattern; git refuses one anywhere in " +
                    "a ref name, so it can only be the leading mark that makes a pattern subtract"
            }
            require(!glob.startsWith('+')) {
                "$option '$value' begins its refspec with '+', which git reads as allowing an " +
                    "update that is no fast-forward; a run writes every ref afresh, so there is " +
                    "nothing for it to allow. Leave it out"
            }

            val destination =
                fields.getOrNull(1)?.let { destinationOf(option, value, it, negated, allowed = destinations) }
            val pattern = RefPattern(glob, destination, negated)
            checkDestination(option, value, glob, pattern)
            // After the destination, because whether a pattern may read a namespace the output
            // has no name rule for depends on whether it said what the matches are called. A
            // pattern that subtracts reads no namespace of its own, so it is asked for nothing.
            checkReachable(
                option,
                value,
                glob,
                writes = destinations && !negated,
                named = destination != null,
            )
            ScopedPattern(input, pattern)
        }
    }
}

/** One pattern, and the input it speaks for by position, or `null` for every input. */
class ScopedPattern(val input: Int?, val pattern: RefPattern)

/**
 * The `--ref` patterns that `-b/--branch` values are shorthand for.
 *
 * A git branch name cannot contain a star, so the short form desugars into the general one
 * exactly — there is no name for which the two select differently, and nothing to escape. Nor
 * can it contain a space, which is what lets one argument carry several of them: see [words],
 * through which `-b` passes for the same reason every other option in its group does.
 *
 * The rest of the group's grammar belongs around the branch name rather than inside it, so only
 * the pattern is prefixed and the scope, the subtracting `^` and a destination are held back:
 * `backend::^wip` reads as `backend::^refs/heads/wip`. Neither a `:` nor a `^` can occur in a ref
 * name, so nothing a branch could legitimately be called is mistaken for one of them.
 *
 * What is malformed as a `-b` value is refused here, naming `-b` and the text as written: its
 * fields, and its scope and destination by the rules a `--ref` is held to ([scopeOf],
 * [destinationOf]). Left to the pattern parser the same refusal would name `--ref` and the
 * desugared value, neither of which the user typed.
 *
 * @param inputs the names an `<input>::` scope may take, in the order the run reads them.
 */
fun branchPatterns(values: List<String>, inputs: List<String>): List<ScopedPattern> =
    words(values, "-b").map { branchPattern(it, inputs) }

private fun branchPattern(value: String, inputs: List<String>): ScopedPattern {
    val (input, written) = scopeOf("-b", value, inputs)
    val negated = written.startsWith('^')
    val fields = (if (negated) written.substring(1) else written).split(':')
    refuseFields("-b", value, fields, inputs, negated, "<branch>")
    val name = fields[0]
    require(name.isNotEmpty()) { if (negated) "-b '$value' subtracts no branch" else "-b '$value' names no branch" }
    require(!name.contains('^')) {
        "-b '$value' holds a '^' inside the branch name; git refuses one anywhere in a ref " +
            "name, so it can only be the leading mark that makes a pattern subtract"
    }
    val heads = Constants.R_HEADS + name
    val pattern = RefPattern(heads, fields.getOrNull(1)?.let { destinationOf("-b", value, it, negated) }, negated)
    checkDestination("-b", value, heads, pattern)
    return ScopedPattern(input, pattern)
}

/**
 * Refuses a refspec of more than two `:`-separated [fields], and one whose first field is an
 * input's name: a scope written with one colon, which reads as a pattern and a destination.
 *
 * @param negated whether a `^` stood in front of [fields], which the form offered keeps, once.
 * @param form what the first field is called in the form a refusal quotes.
 */
private fun refuseFields(
    option: String,
    value: String,
    fields: List<String>,
    inputs: List<String>,
    negated: Boolean,
    form: String,
) {
    require(fields.size <= 2) {
        "$option '$value' has ${fields.size} ':'-separated fields after its scope; the form is " +
            "[<input>::][^]$form[:<destination>]"
    }
    require(fields.size == 1 || fields[0] !in inputs) {
        val mark = if (negated && !fields[1].startsWith('^')) "^" else ""
        "$option '$value' reads as the pattern '${fields[0]}' written to '${fields[1]}', and " +
            "'${fields[0]}' is an input: a scope is ended by '::', as '${fields[0]}::$mark${fields[1]}'"
    }
}

/**
 * Which branch each input braids along, as `--mainline-branch` asked: [common] for every input
 * no value names, [scoped] for the ones a value does, by position. Parsed on the command line;
 * whether the inputs have those branches, and which one detection picks where none is asked for,
 * is the reader's to find out.
 */
class MainlineRequest(val common: String?, val scoped: Map<Int, String>) {

    companion object {

        /** Nothing asked: every input's mainline is detected. */
        val NONE = MainlineRequest(null, emptyMap())

        /**
         * The values `--mainline-branch` was given. A value scoped to an input names that input's
         * own; an unscoped one is the default for every input that has no scoped value, and is
         * also what the output's branch is called.
         */
        fun parse(requested: List<String>, inputs: List<String>): MainlineRequest {
            var common: String? = null
            val scoped = LinkedHashMap<Int, String>()
            for (value in words(requested, "--mainline-branch")) {
                val (index, branch) = scopeOf("--mainline-branch", value, inputs)
                val colon = branch.indexOf(':')
                require(colon < 0) {
                    val before = branch.substring(0, colon)
                    if (before in inputs) {
                        "--mainline-branch '$value' names the branch '$branch', and '$before' is an " +
                            "input: a scope is ended by '::', as '$before::${branch.substring(colon + 1)}'"
                    } else {
                        "--mainline-branch '$value' holds a ':'; it names a branch rather than a ref, " +
                            "and takes no destination"
                    }
                }
                require(branch.isNotEmpty()) { "--mainline-branch '$value' names no branch" }
                if (index == null) {
                    require(common == null) {
                        "--mainline-branch is given twice without naming an input: '$common' and '$value'"
                    }
                    common = branch
                } else {
                    require(scoped.put(index, branch) == null) {
                        "--mainline-branch is given twice for input '${inputs[index]}'"
                    }
                }
            }
            return MainlineRequest(common, scoped)
        }
    }
}

/**
 * The input [value] is scoped to, by its position among [inputs], and the rest of it; or `null`
 * and the whole of it where it names none. A name two inputs share is refused, as is one no input
 * has.
 *
 * The scope is what stands before the first `::`: an input's name holds no `:`, so the first one
 * ends it, even where the refspec after it opens with a `:` of its own; the `<repo>` grammar takes
 * the last because a location may hold one. An empty one is refused, as is a `^` opening it, the
 * mark written a scope too early; so is a name that is none of [inputs].
 */
private fun scopeOf(option: String, value: String, inputs: List<String>): Pair<Int?, String> {
    val at = value.indexOf(SCOPE)
    if (at < 0) return null to value
    val input = value.substring(0, at)
    val rest = value.substring(at + SCOPE.length)
    require(input.isNotEmpty()) {
        "$option '$value' names no input before its '::'; leave the '::' out to speak for every input"
    }
    require(!input.startsWith('^')) {
        if (option == "--mainline-branch") {
            "$option '$value' begins with '^', where the input goes, and a mainline is a branch to " +
                "braid along, not a pattern a '^' could subtract from"
        } else {
            // -b names a branch, so the pattern it is offered is a branch's name too: typed back
            // into -b, a full name would subtract a branch called refs/heads/..., which is none.
            val pattern = if (option == "-b") "wip/*" else "refs/heads/wip/*"
            "$option '$value' begins with '^', where the input goes. The mark belongs in front of " +
                "the pattern: 'backend::^$pattern' subtracts in one input, '^$pattern' in every one"
        }
    }
    val named = inputs.indices.filter { inputs[it] == input }
    require(named.isNotEmpty()) {
        "$option '$value' is for input '$input', which is not one of: " + inputs.distinct().joinToString()
    }
    // A name is a label two inputs may share, and a scope naming both would speak for either.
    if (named.size > 1) throw SharedScope(option, value, input, named.size)
    return named.single() to rest
}

/**
 * A scope naming a name [count] inputs share, refused. Its own exception so that a caller that knows
 * where those inputs are, and how each was written, can say so: [scopeOf] knows only their names.
 */
class SharedScope(val option: String, val value: String, val input: String, count: Int) : IllegalArgumentException(
    "$option '$value' is for input '$input', and $count inputs are called that; give one of them another name"
)

/** The `::` that ends a scope — see [scopeOf]. */
private const val SCOPE = "::"

/**
 * The [destination] a refspec gave, refused on a pattern that subtracts, since nothing lands from
 * it, on an option that takes none, and when it is empty; whether it can be carried out is
 * [checkDestination]'s to say.
 *
 * @param allowed whether [option] takes a destination at all.
 */
private fun destinationOf(
    option: String,
    value: String,
    destination: String,
    negated: Boolean,
    allowed: Boolean = true,
): String {
    require(!negated) {
        "$option '$value' gives a destination to a pattern that subtracts; nothing " +
            "lands from it, so there is nothing to name"
    }
    require(allowed) {
        "$option '$value' gives a destination, and $option decides what a run " +
            "reads rather than what it writes; a destination belongs on -b, --ref " +
            "or --label-ref"
    }
    require(destination.isNotEmpty()) { "$option '$value' names no destination" }
    return destination
}

/**
 * Every value of [values], each split on whitespace, so one shell word may carry a whole list:
 * `--mainline-branch 'A::main B::trunk'`, `-b 'main develop'`.
 *
 * Splitting is safe for the same reason the `::` scope is: git refuses a space anywhere in a ref
 * name, as it refuses a colon, while it accepts `,`, `;` and `|` — so whitespace can never cut a
 * pattern or a branch name in half, and none of the obvious separators could have been used
 * instead. Repeating the option still works and means the same thing.
 */
fun words(values: List<String>, option: String): List<String> =
    values.flatMap { value ->
        val parts = value.split(WHITESPACE).filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "$option was given a value holding nothing but whitespace" }
        parts
    }

private val WHITESPACE = Regex("\\s+")

/**
 * Whether [pattern] can match any name under [namespace] at all.
 *
 * Decided on the pattern's literal head, the part before its first `*`: with nothing to match
 * loosely, the pattern itself has to sit under the namespace, and with a star anywhere the head
 * and the namespace have to agree as far as the shorter of the two runs. So `refs/tags/v1.*`
 * reaches the tags, a star alone or `refs/` with one reaches both, and a pattern under
 * `refs/notes/` reaches neither.
 *
 * Conservative in the one direction that is safe: the head is all this looks at, so a pattern
 * whose star sits inside a namespace's own text (`re*fs/heads/x`) is let through on a head of
 * `re` agreeing with `refs/heads/`, whatever the pattern as a whole then goes on to match —
 * with the star standing for nothing, `refs/heads/x` among other things. What matters is that
 * nothing which *could* match is refused.
 */
internal fun reaches(pattern: String, namespace: String): Boolean {
    val head = pattern.substringBefore('*')
    if (head.length == pattern.length) return pattern.startsWith(namespace)
    return namespace.startsWith(head) || head.startsWith(namespace)
}

/**
 * Refuses a destination that cannot be carried out.
 *
 * A star in the destination is a substitution, so there has to be exactly one thing to
 * substitute: the glob must hold exactly one star of its own. A destination without a star names
 * a namespace or one ref outright, and neither asks anything of the glob.
 *
 * A namespace destination has to be one of the two the program names a prefix for. Anywhere else
 * it would be a namespace and a missing naming rule, which is what the star form is for.
 */
private fun checkDestination(option: String, value: String, glob: String, pattern: RefPattern) {
    val destination = pattern.destination ?: return
    // The same refusal the read side gives a pattern under refs/notes/, for the same reason:
    // what would land there is a commit, and a notes ref names a tree keyed by object shas.
    // Checked before the branches below, because the namespace branch's refusal suggests
    // '<destination>*', which under refs/notes/ the star branch would otherwise accept.
    require(!destination.startsWith(Constants.R_NOTES)) {
        "$option '$value' writes into ${Constants.R_NOTES}, which is not where a commit goes: " +
            "a notes ref points at a tree keyed by shas. Notes are carried by --notes, which " +
            "rekeys them onto the commits this run writes"
    }
    if (destination.contains('*')) {
        require(destination.count { it == '*' } == 1) {
            "$option '$value' has more than one star in its destination; one substitutes what " +
                "the pattern matched, and a second would have nothing of its own to stand for"
        }
        require(glob.count { it == '*' } == 1) {
            "$option '$value' substitutes a star into its destination, so the pattern needs " +
                "exactly one of its own to say what is substituted"
        }
        require(destination.startsWith(Constants.R_REFS)) {
            "$option '$value' writes '$destination', which is not a ref name: every one " +
                "begins ${Constants.R_REFS}"
        }
        return
    }
    if (pattern.toNamespace) {
        requireNotNull(RefKind.of(destination)) {
            "$option '$value' hands '$destination' back to a prefix rule, and there is none " +
                "for it -- ${Constants.R_HEADS} and ${Constants.R_TAGS} have --branch-prefix " +
                "and --tag-prefix. Spell the name out instead, with a star: '$destination*'"
        }
        return
    }
    require(destination.startsWith(Constants.R_REFS)) {
        "$option '$value' writes '$destination', which is not a ref name: every one begins " +
            "${Constants.R_REFS}"
    }
}

/**
 * Refuses a pattern that could never reach a ref, and one whose matches would have no name.
 *
 * A well-formed selection matching nothing is indistinguishable, from the command line, from an
 * input that simply does not have what was asked for — so a pattern under `refs/notes/` was as
 * quiet as `--ref 'refs/tags/v9.*'` against a repository with no v9. One of those is a fact
 * about the input and one is a fact about this program, and only the second can be said here.
 *
 * Outside `refs/heads/` and `refs/tags/` the question is not whether a ref *can* be read — a
 * Gerrit change and a forge's pull ref name commits like any other — but what it would be
 * called afterwards. Nothing qualifies a namespace the program has never heard of, so a pattern
 * that reads one has to say where its matches land, and the destination field is that answer.
 *
 * @param writes whether this option decides what is read and written, as opposed to what may
 *   weigh on the braid. Only the first kind needs a destination: the interleave matches refs
 *   some other pattern already brought in, so a foreign namespace there is already named.
 */
private fun checkReachable(
    option: String,
    value: String,
    glob: String,
    writes: Boolean,
    named: Boolean,
) {
    require(!glob.startsWith(Constants.R_NOTES)) {
        "$option '$value' names ${Constants.R_NOTES}, which is not history: a notes ref points " +
            "at a tree keyed by shas rather than at a commit anyone braids. Notes are carried " +
            "by --notes, which rekeys them onto the commits this run writes"
    }
    if (RefKind.entries.any { reaches(glob, it.namespace) }) return

    val namespace = requireNotNull(foreignNamespace(glob)) {
        "$option '$value' can match no ref: every ref name begins ${Constants.R_REFS}, and " +
            "'$glob' cannot"
    }
    require(!writes || named) {
        // A destination is the refspec's right half, so it goes at the end, the scope kept.
        val refused = "$option '$value' reads '$namespace', which the output has no naming " +
            "rule for -- --branch-prefix and --tag-prefix speak for ${Constants.R_HEADS} and " +
            "${Constants.R_TAGS} alone."
        // The destination offered has to be one checkDestination takes, and a star there needs
        // exactly one in the glob: so a glob with none is offered its name spelled out, and one
        // with several is offered nothing, no one destination being able to stand for it.
        when (val stars = glob.count { it == '*' }) {
            1 -> "$refused Say what its matches are called: '$value:$namespace{repo}/*'"
            0 -> "$refused Say what it is called: " +
                "'$value:$namespace{repo}/${glob.removePrefix(namespace)}'"
            else -> "$refused A destination substitutes one star, and this has $stars: split " +
                "it into patterns of one star each, and give each its destination"
        }
    }
}

/**
 * The namespace a pattern outside the two known ones reads from, or `null` if it can reach no
 * ref at all.
 *
 * The literal head cut back to its last `/`, which is the deepest prefix that is certainly a
 * namespace rather than half a ref name: `refs/changes/` for a Gerrit pattern, `refs/pull/` for
 * one reaching into a pull ref, and plain `refs/` for a pattern naming a single ref such as the
 * stash. Enumerating that prefix and letting the glob filter it is exact either way; a shorter
 * prefix only costs a wider listing.
 */
internal fun foreignNamespace(glob: String): String? {
    val head = glob.substringBefore('*')
    if (!head.contains('/')) return null
    val namespace = head.substringBeforeLast('/') + "/"
    return if (namespace.startsWith(Constants.R_REFS)) namespace else null
}

/**
 * Matches a full ref name against [pattern], where `*` is the only metacharacter and it spans
 * path separators — so a pattern ending in one covers a whole prefix however deeply nested, and
 * a bare star is every ref.
 */
internal fun glob(pattern: String): (String) -> Boolean {
    val matcher = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    return { name -> matcher.matches(name) }
}
