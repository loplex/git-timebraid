package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.CommitGraph
import cz.loplex.timebraid.plan.CommitGraphBuilder
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

/** Which of a commit's two timestamps interleaves the strands. */
enum class OrderBy { AUTHOR, COMMITTER }

/**
 * The planner's input, assembled from real repositories: the whole commit graph of every strand,
 * plus everything the writer will need to turn the resulting plan back into a repository.
 */
class BraidInputs(
    val graph: CommitGraph,
    /** Mainline tip per input repository, in the order the repositories were given to [CommitGraphReader.read]. */
    val heads: List<Commit>,
    /**
     * The branch the *output* carries the braid on, and its HEAD. Each input's own mainline is
     * [SourceInputs.mainlineBranch] and need not be this or each other's.
     */
    val mainlineBranch: String,
    /** The original commit behind every commit of [graph]. */
    val commits: Map<Commit, SourceCommit>,
    /** Per input repository, in the order they were given to [CommitGraphReader.read]. */
    val sources: List<SourceInputs>,
    /**
     * Commits whose ancestry may delay a braid commit — the refs matched by `--interleave-ref`,
     * across every input. Empty unless the option was given, which is the default scope: the
     * mainline chains alone (see `BraidInterleave`).
     */
    val interleaveTips: List<Commit> = emptyList(),
    /** Refs attached by `--label-ref`, across every input. */
    val labelsAttached: Int = 0,
    /**
     * Refs `--label-ref` matched whose target is not in the graph, across every input. They are not
     * an error — the flag names what is already there, and asking for a superset of it is the
     * normal way to use one. Counted so that a match this run could not attach is not silent — a
     * pattern matching no ref at all is a different case, and this does not see it.
     */
    val labelsSkipped: Int = 0,
)

/** What was read out of one input repository, with every ref resolved to the commit it names. */
class SourceInputs(
    /**
     * The strand this repository became. [BraidInputs.sources] keeps the order the repositories
     * were given to [CommitGraphReader.read] in, so a caller holding that list pairs each open
     * repository with its [Source] by position, once, and looks one up by the other afterwards.
     */
    val source: Source,
    /**
     * The branch resolved as *this* input's mainline. Inputs need not agree: one may braid along
     * `main` and another along `master`, each named with a scoped `--mainline-branch`. The output
     * carries a single branch, [BraidInputs.mainlineBranch], whatever these say.
     */
    val mainlineBranch: String,
    val branches: List<BraidRef>,
    val tags: List<BraidTag>,
    /**
     * Full names of the refs this repository was read from, mainline first. The output fetches
     * exactly these (see [TargetRepository.fetchFrom]), so the set has to be the one the graph was
     * built from — anything less and the output would be missing objects it points at, anything
     * more and it would carry history the run never read.
     */
    val readRefs: List<String>,
)

/** The two ref namespaces this program reads from, and the two it writes into. */
enum class RefKind(val namespace: String) {
    BRANCH(Constants.R_HEADS),
    TAG(Constants.R_TAGS);

    companion object {
        /** The kind a destination names, or `null` when [namespace] is neither of the two. */
        fun of(namespace: String): RefKind? =
            entries.firstOrNull { it.namespace == namespace || it.namespace == "$namespace/" }
    }
}

/**
 * A branch resolved to the commit it points at.
 *
 * [labelOnly] marks one attached by `--label-ref` rather than selected by `--ref`: it named a commit
 * the graph already held instead of bringing one in, and it is not eligible to weigh on the braid.
 *
 * [writeAs] is the namespace the output writes it into, which the pattern that matched it may set —
 * a branch carried over as a tag is how a dead branch is archived without dropping the commits only
 * it reaches. It changes nothing about the reading: [name] stays the input's own, and so does the
 * namespace every pattern is matched against.
 */
class BraidRef(
    val name: String,
    val commit: Commit,
    val labelOnly: Boolean = false,
    val writeAs: RefKind = RefKind.BRANCH,
)

/** A tag resolved to the commit it peels to, keeping its annotation if it had one. */
class BraidTag(
    val name: String,
    val commit: Commit,
    val annotation: TagAnnotation?,
    /** As [BraidRef.labelOnly]. */
    val labelOnly: Boolean = false,
    /** As [BraidRef.writeAs]. */
    val writeAs: RefKind = RefKind.TAG,
)

/**
 * Reads a set of [SourceRepository] into the single [CommitGraph] the planner works on.
 *
 * The model is deliberately global: load *every* commit of *every* strand at once — reachable from
 * the refs to be recreated — and let the planner reparent only the braid.
 * Off-braid commits keep their original parents, so a branch that exists in one repository still
 * forks off the braid at the right moment with no special handling (see the Branches section of
 * doc/how-it-works.md).
 */
object CommitGraphReader {

    /** Branches tried, in order, when `--mainline-branch` is not given. */
    val MAINLINE_CANDIDATES = listOf("main", "master", "develop")

    /**
     * The `refs` pattern that `-b/--branch` is shorthand for.
     *
     * A git branch name cannot contain a star, so the short form desugars into the general one
     * exactly — there is no name for which the two select differently, and nothing to escape. Nor
     * can it contain a space, which is what lets one argument carry several of them: see [words],
     * through which `-b` passes as every option taking a ref pattern does.
     */
    fun branchPattern(name: String): String = Constants.R_HEADS + name

    /**
     * @param refs glob patterns matched against full ref names — `refs/heads/main` for one branch,
     *   `refs/tags/v1.*` for a release series, a star alone for every ref — selecting which of each
     *   input's branches and tags are loaded and later recreated. Empty selects them all, which is
     *   the default. Branches and tags are selected by one set of patterns rather than one set
     *   each, so a run says what it wants to carry over once and gets exactly that: naming only
     *   branches leaves the tags behind, which is the whole point of being able to narrow.
     *
     *   The resolved mainline is loaded whatever the patterns say, since the braid is built along
     *   it, and the output's mainline branch is written from the braid's tip rather than from this
     *   selection ([BraidWriter] does that unconditionally).
     * @param interleaveRefs glob patterns matched against full ref names — `refs/tags/v1.*` for a
     *   release series, a star alone for every ref — applied in every input repository, or in the
     *   one an `<input>::` scope names. A matched ref's ancestry is allowed to delay a braid
     *   commit; see `BraidInterleave` for what that trades away. Matched against what [refs]
     *   selected, so widening the interleave cannot widen what is loaded.
     * @param labelRefs glob patterns matched against full ref names, recreating every matched ref
     *   whose target the graph *already* holds. Empty matches nothing, the flag being opt-in.
     *
     *   This is the naming axis on its own, and it is separate from [refs] because the two carry
     *   different risks. Selecting a ref decides what is read, and what is read decides what
     *   [interleaveRefs] can match — so a ref named purely to have it in the output can end up
     *   moving the braid, and the refs cheapest to name are the likeliest to: one pointing into the
     *   mainline's own history costs no commits, and its ancestry is exactly the merged-in history
     *   whose arrival into scope makes merges wait.
     *
     *   A label never extends what is read and never becomes an interleave tip, which makes the
     *   safety a property rather than a coincidence of two globs missing each other: **adding any
     *   label to a run cannot change a single commit the run writes.**
     */
    fun read(
        repositories: List<SourceRepository>,
        orderBy: OrderBy,
        mainlineBranch: List<String> = emptyList(),
        refs: List<String> = emptyList(),
        interleaveRefs: List<String> = emptyList(),
        labelRefs: List<String> = emptyList(),
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }
        require(repositories.map { it.name }.toSet().size == repositories.size) {
            "two input repositories have the same name"
        }

        val names = repositories.map { it.name }
        // Before the mainlines, so that a mistyped input name is reported as the mistyped input name
        // rather than being masked by whatever the mainline resolution makes of the run.
        val scopedRefs = ScopedPatterns(refs, "--ref", names, destinations = true)
        val scopedLabels = ScopedPatterns(labelRefs, "--label-ref", names, destinations = true)
        val scopedInterleave = ScopedPatterns(interleaveRefs, "--interleave-ref", names)
        val mainlines = resolveMainlines(repositories, mainlineBranch)
        val outputBranch = mainlines.output
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Commit>(repositories.size)
        val original = HashMap<Commit, SourceCommit>()
        val inputs = ArrayList<SourceInputs>(repositories.size)
        var labelsAttached = 0
        var labelsSkipped = 0

        for (repo in repositories) {
            val source = builder.addSource(repo.name)

            val selected = Selection(scopedRefs.of(repo.name), emptyMeans = true)
            // Empty matches nothing here, the other way round from the selection: a label is
            // something a run asks for, where carrying the refs over is what it does by default.
            val labelled = Selection(scopedLabels.of(repo.name), emptyMeans = false)

            val mainline = mainlines.perInput.getValue(repo.name)
            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            val selectedBranches = repo.branches().filter { selected.matches(Constants.R_HEADS + it.name) }
            val selectedTags = repo.tags().filter { selected.matches(Constants.R_TAGS + it.name) }

            // Labels are what the selection did not already take. A ref matched by both is selected,
            // which is the wider meaning of the two: it is read from as well as recreated. The
            // mainline is no label either: it is read whatever is selected, and the braid's own
            // branch stands for it, so the writer skips it and a count of it would be of nothing.
            val labelledBranches = repo.branches().filter {
                val name = Constants.R_HEADS + it.name
                it.name != mainline && !selected.matches(name) && labelled.matches(name)
            }
            val labelledTags = repo.tags().filter {
                val name = Constants.R_TAGS + it.name
                !selected.matches(name) && labelled.matches(name)
            }

            // The refs the graph is read from, and the objects they point at. Both are needed and
            // they are not the same thing: the walk starts from objects, while the fetch that later
            // fills the output has to name refs.
            val readRefs = LinkedHashSet<String>()
            val tips = LinkedHashSet<ObjectId>()
            readRefs += Constants.R_HEADS + mainline
            tips += mainlineTip
            for (branch in selectedBranches) {
                readRefs += Constants.R_HEADS + branch.name
                tips += branch.target
            }
            for (tag in selectedTags) {
                readRefs += Constants.R_TAGS + tag.name
                tips += tag.target
            }

            for (sourceCommit in repo.readReachable(tips)) {
                val commit = builder.addCommit(
                    source = source,
                    id = sourceCommit.id.name,
                    orderingTime = sourceCommit.time(orderBy),
                    parentIds = sourceCommit.parents.map { it.name },
                )
                original[commit] = sourceCommit
            }

            heads += builder.find(source, mainlineTip.name)
                ?: error("repository '${repo.name}' did not read its own mainline tip")

            // A ref whose target never made it into the graph is dropped rather than rejected: it
            // points at something that is not a commit, which is a fact about the input, not an
            // error in the run.
            //
            // A label reaches the same line by the ordinary route rather than the exceptional one.
            // Nothing above put its target in, so it is there only if something else's ancestry
            // carried it, and the miss is counted instead of being a fact about the input.
            val labelBranchRefs = labelledBranches.mapNotNull { branch ->
                val name = Constants.R_HEADS + branch.name
                builder.find(source, branch.target.name)?.let {
                    BraidRef(branch.name, it, labelOnly = true, writeAs = labelled.writeAs(name) ?: RefKind.BRANCH)
                }
            }
            val labelTagRefs = labelledTags.mapNotNull { tag ->
                val name = Constants.R_TAGS + tag.name
                builder.find(source, tag.target.name)?.let {
                    BraidTag(tag.name, it, tag.annotation, labelOnly = true, writeAs = labelled.writeAs(name) ?: RefKind.TAG)
                }
            }
            labelsAttached += labelBranchRefs.size + labelTagRefs.size
            labelsSkipped +=
                (labelledBranches.size - labelBranchRefs.size) +
                (labelledTags.size - labelTagRefs.size)

            inputs += SourceInputs(
                source = source,
                mainlineBranch = mainline,
                branches = selectedBranches.mapNotNull { branch ->
                    val name = Constants.R_HEADS + branch.name
                    builder.find(source, branch.target.name)?.let {
                        BraidRef(branch.name, it, writeAs = selected.writeAs(name) ?: RefKind.BRANCH)
                    }
                } + labelBranchRefs,
                tags = selectedTags.mapNotNull { tag ->
                    val name = Constants.R_TAGS + tag.name
                    builder.find(source, tag.target.name)?.let {
                        BraidTag(tag.name, it, tag.annotation, writeAs = selected.writeAs(name) ?: RefKind.TAG)
                    }
                } + labelTagRefs,
                readRefs = readRefs.toList(),
            )
        }

        val graph = builder.build()
        check(original.size == graph.size) {
            "the graph has ${graph.size} commits but ${original.size} were read"
        }

        return BraidInputs(
            graph = graph,
            heads = heads,
            mainlineBranch = outputBranch,
            commits = original,
            sources = inputs,
            interleaveTips = interleaveTips(scopedInterleave, inputs),
            labelsAttached = labelsAttached,
            labelsSkipped = labelsSkipped,
        )
    }

    /**
     * The refs the patterns match, as the commits they name, deduplicated.
     *
     * Matching is against the *full* ref name, because a short name cannot say whether `v1.0` is a
     * branch or a tag, and a pattern that cannot express the difference would be a trap. The star
     * spans path separators, so a pattern ending in one covers a whole prefix however deeply nested,
     * and a bare star is every ref the run selected — which puts the whole graph they reach in scope.
     * A label is none of those, whatever the pattern says: see the loop.
     */
    private fun interleaveTips(patterns: ScopedPatterns, inputs: List<SourceInputs>): List<Commit> {
        val tips = LinkedHashSet<Commit>()
        for (input in inputs) {
            val matches = Selection(patterns.of(input.source.name), emptyMeans = false)
            // A label is skipped whatever the pattern says. That is the whole of the separation:
            // the interleave matches what a run chose to read, and a label chose nothing.
            for (branch in input.branches) {
                if (branch.labelOnly) continue
                if (matches.matches("${Constants.R_HEADS}${branch.name}")) tips += branch.commit
            }
            for (tag in input.tags) {
                if (tag.labelOnly) continue
                if (matches.matches("${Constants.R_TAGS}${tag.name}")) tips += tag.commit
            }
        }
        return tips.toList()
    }

    /** One glob, and the namespace the refs it matches are written into when it says. */
    private class RefPattern(val glob: String, val writeAs: RefKind?)

    /**
     * A pattern, the input it speaks for, and where its matches land, written
     * `[<input>::]<pattern>[:<destination>]`: `backend::refs/heads/main` narrows to one input, while
     * a pattern with no `::` speaks for every one of them.
     *
     * **The scope is ended by `::`**, the separator the `<repo>` grammar puts between a location and
     * its suffix, and **a single `:` begins the destination**, the separator git itself puts between
     * the two halves of a refspec, where the left half says what is read and the right half what it
     * is called. Both are decidable rather than a convention this asks to be trusted. Git refuses a
     * colon anywhere in a ref name, and [cz.loplex.timebraid.cli.MergeCommand] refuses one in an
     * input's name, so a colon in one of these values can only ever be a separator.
     *
     * **The empty case stays per input, and keeps the meaning it had.** No pattern for an input is
     * that option's empty case for that input — every ref for [Selection], none for the interleave
     * and the labels. So `--ref backend::refs/heads/main` narrows backend and leaves the other inputs
     * carrying everything, which is the generalisation of *naming any ref leaves out every ref not
     * named* from the run to the input. An unscoped pattern narrows every input exactly as before.
     *
     * @param destinations whether a destination is meaningful at all. It is for the two options that
     *   write refs; for the one that decides what may weigh on the braid it would name a namespace
     *   nothing is ever written to, so it is refused rather than accepted and ignored.
     */
    private class ScopedPatterns(
        raw: List<String>,
        option: String,
        inputs: Collection<String>,
        destinations: Boolean = false,
    ) {
        private val unscoped = ArrayList<RefPattern>()
        private val byInput = HashMap<String, MutableList<RefPattern>>()

        init {
            for (value in words(raw, option)) {
                val (input, written) = scopeOf(option, value, inputs)
                val fields = written.split(':')
                refuseFields(option, value, fields, inputs)
                val glob = fields[0]
                require(glob.isNotEmpty()) { "$option '$value' names no pattern" }

                val writeAs = fields.getOrNull(1)?.let { destination ->
                    require(destinations) {
                        "$option '$value' gives a destination, and $option decides what a run " +
                            "reads rather than what it writes; a destination belongs on --ref or " +
                            "--label-ref"
                    }
                    require(destination.isNotEmpty()) { "$option '$value' names no destination" }
                    RefKind.of(destination) ?: throw IllegalArgumentException(
                        "$option '$value' writes to '$destination'; a destination is " +
                            "${Constants.R_HEADS} or ${Constants.R_TAGS}, the namespace and " +
                            "nothing deeper -- the prefix under it is --branch-prefix or " +
                            "--tag-prefix"
                    )
                }
                val pattern = RefPattern(glob, writeAs)
                if (input == null) unscoped += pattern else byInput.getOrPut(input) { ArrayList() } += pattern
            }
        }

        /**
         * The patterns applying to [input], the ones naming it first.
         *
         * The order only matters to a destination, where the first pattern that matches a ref
         * decides where it lands: a scoped pattern is the more specific of the two, so it is the one
         * that should win over an unscoped pattern covering the same ref.
         */
        fun of(input: String): List<RefPattern> = (byInput[input] ?: emptyList<RefPattern>()) + unscoped
    }

    /**
     * What a set of patterns says about a ref: whether it is taken, and where it is written.
     *
     * [emptyMeans] is the whole difference between the options. No pattern means *every ref* for the
     * selection, so narrowing a run is opt-in and a plain invocation still loads everything; the
     * interleave and the labels read their own empty list the other way, a ref that delays a merge
     * or gets a name it did not earn being the exception rather than the rule.
     */
    private class Selection(patterns: List<RefPattern>, private val emptyMeans: Boolean) {

        private val matchers = patterns.map { glob(it.glob) to it.writeAs }

        fun matches(name: String): Boolean {
            if (matchers.isEmpty()) return emptyMeans
            return matchers.any { (match, _) -> match(name) }
        }

        /**
         * The namespace [name] is written into, or `null` to leave it in the one it came from.
         *
         * The first pattern that matches decides, which is why [ScopedPatterns.of] puts the scoped
         * ones first: *this input's branches as tags, everything else as it stands* has to be
         * writable, and only a more specific pattern winning makes it so.
         */
        fun writeAs(name: String): RefKind? =
            matchers.firstOrNull { (match, _) -> match(name) }?.second
    }

    /**
     * Every value of [values], each split on whitespace, so one shell word may carry a whole list:
     * `--mainline-branch 'A::main B::trunk'`, `-b 'main develop'`.
     *
     * Splitting is safe for the same reason the `::` scope is: git refuses a space anywhere in a
     * ref name, as it refuses a colon, while it accepts `,`, `;` and `|` — so whitespace can never
     * cut a pattern or a branch name in half, and none of the obvious separators could have been
     * used instead. Repeating the option still works and means the same thing.
     */
    internal fun words(values: List<String>, option: String): List<String> =
        values.flatMap { value ->
            val parts = value.split(WHITESPACE).filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "$option was given a value holding nothing but whitespace" }
            parts
        }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Matches a full ref name against [pattern], where `*` is the only metacharacter and it spans
     * path separators — so a pattern ending in one covers a whole prefix however deeply nested, and
     * a bare star is every ref.
     */
    private fun glob(pattern: String): (String) -> Boolean {
        val matcher = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
        return { name -> matcher.matches(name) }
    }

    /** Which branch each input braids along, and the single branch the output carries. */
    private class Mainlines(val perInput: Map<String, String>, val output: String)

    /**
     * Resolves the mainline per input.
     *
     * A value scoped to an input names that input's own; an unscoped one is the default for every
     * input that has no scoped value, and is also what the output's branch is called. With no
     * unscoped value the output takes the first input's — the inputs are given in an order the rest
     * of the run already honours, and one of them has to name it.
     *
     * Detection is left where it was: the first of [MAINLINE_CANDIDATES] present in **all** of the
     * inputs still awaiting one. Doing it per input instead would quietly pick `main` for one and
     * `master` for another wherever both exist, which is a different run from the one that used to
     * happen.
     */
    private fun resolveMainlines(
        repositories: List<SourceRepository>,
        requested: List<String>,
    ): Mainlines {
        val names = repositories.map { it.name }
        var common: String? = null
        val scoped = LinkedHashMap<String, String>()
        for (value in words(requested, "--mainline-branch")) {
            val (input, branch) = scopeOf("--mainline-branch", value, names)
            val colon = branch.indexOf(':')
            require(colon < 0) {
                val before = branch.substring(0, colon)
                if (before in names) {
                    "--mainline-branch '$value' names the branch '$branch', and '$before' is an " +
                        "input: a scope is ended by '::', as '$before::${branch.substring(colon + 1)}'"
                } else {
                    "--mainline-branch '$value' holds a ':'; it names a branch rather than a ref, " +
                        "and takes no destination"
                }
            }
            require(branch.isNotEmpty()) { "--mainline-branch '$value' names no branch" }
            if (input == null) {
                require(common == null) {
                    "--mainline-branch is given twice without naming an input: '$common' and '$value'"
                }
                common = branch
            } else {
                require(scoped.put(input, branch) == null) {
                    "--mainline-branch is given twice for input '$input'"
                }
            }
        }

        val awaiting = repositories.filter { it.name !in scoped }
        val detected = when {
            awaiting.isEmpty() -> null
            common != null -> {
                val missing = awaiting.filter { it.resolveBranch(common) == null }
                require(missing.isEmpty()) {
                    "branch '$common' is missing in: ${missing.joinToString { it.name }}"
                }
                common
            }
            else -> MAINLINE_CANDIDATES.firstOrNull { candidate ->
                awaiting.all { it.resolveBranch(candidate) != null }
            } ?: error(
                "no branch is common to every input (looked for " +
                    "${MAINLINE_CANDIDATES.joinToString()}); pass --mainline-branch to name one, " +
                    "or --mainline-branch <input>::<branch> where they differ"
            )
        }

        val perInput = repositories.associate { repo ->
            repo.name to (scoped[repo.name] ?: detected ?: error("no mainline for '${repo.name}'"))
        }
        return Mainlines(perInput, common ?: perInput.getValue(repositories.first().name))
    }

    /**
     * The input [value] is scoped to and the rest of it, or `null` and the whole of it where it
     * names none.
     *
     * The scope is what stands before the first `::`: an input's name holds no `:`, so the first one
     * ends it, where the `<repo>` grammar takes the last because a location may hold one. An empty
     * one is refused, the unscoped form already saying it, and so is a name that is none of [inputs].
     */
    private fun scopeOf(option: String, value: String, inputs: Collection<String>): Pair<String?, String> {
        val at = value.indexOf(SCOPE)
        if (at < 0) return null to value
        val input = value.substring(0, at)
        require(input.isNotEmpty()) {
            "$option '$value' names no input before its '::'; leave the '::' out to speak for every input"
        }
        require(input in inputs) {
            "$option '$value' is for input '$input', which is not one of: " + inputs.joinToString()
        }
        return input to value.substring(at + SCOPE.length)
    }

    /** The `::` that ends a scope — see [scopeOf]. */
    private const val SCOPE = "::"

    /**
     * Refuses a value of more than two `:`-separated [fields] after its scope, and one whose first
     * field is an input's name: a scope written with one colon, which reads as a pattern and a
     * destination.
     */
    private fun refuseFields(option: String, value: String, fields: List<String>, inputs: Collection<String>) {
        require(fields.size <= 2) {
            "$option '$value' has ${fields.size} ':'-separated fields after its scope; the form is " +
                "[<input>::]<pattern>[:<destination>]"
        }
        require(fields.size == 1 || fields[0] !in inputs) {
            "$option '$value' reads as the pattern '${fields[0]}' written to '${fields[1]}', and " +
                "'${fields[0]}' is an input: a scope is ended by '::', as '${fields[0]}::${fields[1]}'"
        }
    }
}
