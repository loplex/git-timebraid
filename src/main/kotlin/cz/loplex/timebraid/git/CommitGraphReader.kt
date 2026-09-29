package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.CommitGraph
import cz.loplex.timebraid.plan.CommitGraphBuilder
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent

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
    /** Notes carried over onto the commits this run wrote, across every input. */
    val notesAttached: Int = 0,
    /**
     * Notes whose annotated object is not in the graph, across every input. A note on a commit an
     * unselected ref reached, or on a blob or a tree, has nowhere to go: the output never wrote that
     * object under a name of its own. Counted rather than refused, for the same reason a label this
     * run could not attach is.
     */
    val notesSkipped: Int = 0,
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
    /**
     * Where [mainlineBranch] pointed in the input, as the input's own sha.
     *
     * Carried separately from [refs] because the mainline is read whatever the selection says,
     * so a run narrowed away from it holds its commits with nothing in [refs] naming them.
     */
    val mainlineTip: ObjectId,
    /**
     * Every ref this input contributes to the output, in the order they were read: its branches,
     * its tags, and whatever a pattern named beyond those two namespaces.
     *
     * One list rather than one per kind, because a destination unties an output ref from the kind
     * it had at home — a branch may arrive as a tag and a Gerrit change as either — and a split by
     * the input's kind would say nothing about what the output holds while a split by the output's
     * would lose what the patterns are matched against.
     */
    val refs: List<BraidOutRef>,
    /** Notes refs carried over, empty unless the run asked for them. */
    val notes: List<BraidNotes> = emptyList(),
    /**
     * Full names of this repository's notes refs, which the fetch has to name as well.
     *
     * Kept apart from [readRefs] because they answer different questions. [readRefs] is what the
     * graph was built from and must stay exactly that; these contribute no commit to it, but their
     * blobs have to reach the output before a tree can point at one. A notes ref's own history
     * comes along with it and stays unreferenced in the output, whose notes commit has no parent.
     */
    val noteRefs: List<String> = emptyList(),
    /**
     * Full names of the refs this repository was read from, mainline first. The output fetches
     * these (see [TargetRepository.fetchFrom]), plus [noteRefs], so the set has to be the one the
     * graph was built from — anything less and the output would be missing objects it points at,
     * anything more and it would carry history the run never read. The notes refs fetched beside
     * them are the one such addition, and [noteRefs] says why.
     */
    val readRefs: List<String>,
)

/**
 * One notes ref of an input, its keys resolved to the commits the braid rewrote.
 *
 * The output writes its own notes commit from this: a merge gives every commit a new sha, so a note
 * keyed by the old one would be attached to nothing. Rekeying is the same move the provenance
 * trailer makes, from the other side.
 */
class BraidNotes(
    /** The ref's name below `refs/notes/`, before the output's own prefix goes on. */
    val name: String,
    /** The commit a note is attached to, to the blob holding that note's text. */
    val entries: Map<Commit, ObjectId>,
    val author: PersonIdent,
    val committer: PersonIdent,
    val message: String,
)

/**
 * One ref carried over: where it came from, what it points at, and the pattern that took it.
 *
 * What it is called in the output follows from those and the options, and is [OutputName]'s to
 * work out beside the other names the output's refs get: nothing the reader decides depends on it.
 */
class BraidOutRef(
    /**
     * What this ref is called in the input, in full.
     *
     * Every pattern is matched against this — the selection, the labels and the interleave alike —
     * because it is the only name that exists on both sides of a rename, and the only one a caller
     * can see before the run.
     */
    val fullName: String,
    val commit: Commit,
    /** Present when the input ref was an annotated tag object, and only then. */
    val annotation: TagAnnotation? = null,
    /**
     * Whether this ref was attached by `--label-ref` rather than selected by `--ref`: it named a
     * commit the graph already held instead of bringing one in, and it is not eligible to weigh on
     * the braid.
     */
    val labelOnly: Boolean = false,
    /**
     * The namespace the ref was found under in the input, `refs/heads/` and the like, with its
     * trailing slash: the part of [fullName] a prefix goes after.
     */
    val namespace: String,
    /** The pattern that took this ref, with the destination it may give, or `null` for none. */
    val takenBy: RefPattern? = null,
) {
    /** What the ref is called below [namespace]. */
    val name: String get() = fullName.removePrefix(namespace)

    /** Whether this ref is the one the braid already speaks for, and must not be written twice. */
    fun isMainlineOf(input: SourceInputs): Boolean =
        fullName == Constants.R_HEADS + input.mainlineBranch
}

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
     * Every pattern arrives parsed ([ScopedPatterns.parse]), each scoped to an input by its position
     * in [repositories], so nothing here reads option text.
     *
     * @param requested which branch each input braids along, as `--mainline-branch` asked for it.
     * @param refs glob patterns matched against full ref names — `refs/heads/main` for one branch,
     *   `refs/tags/v1.*` for a release series, a star alone for every branch and tag — selecting
     *   which of each input's refs are loaded and later recreated. Empty selects every branch and
     *   tag, which is the default, and a `^` in front of a pattern subtracts from whatever the rest
     *   selected — so `^refs/heads/wip` alone drops that branch and keeps every other branch and
     *   tag, without the run having to name the ones it wants. Branches and tags are selected by one set of
     *   patterns rather than one set each, so a run says what it wants to carry over once and gets
     *   exactly that: naming only branches leaves the tags behind, which is the whole point of
     *   narrowing.
     *
     *   The resolved mainline is loaded whatever the patterns say, since the braid is built along
     *   it, and the output's mainline branch is written from the braid's tip rather than from this
     *   selection ([BraidWriter] does that unconditionally).
     * @param interleaveRefs glob patterns matched against full ref names — `refs/tags/v1.*` for a
     *   release series, a star alone for every branch and tag, a ref of another namespace only by a
     *   pattern naming that namespace — applied in every input repository, or in the one an
     *   `<input>::` scope names. A matched ref's ancestry is allowed to delay a braid
     *   commit; see `BraidInterleave` for what that trades away. Matched against what [refs]
     *   selected, so widening the interleave cannot widen what is loaded.
     *
     *   A `^` in front of a pattern subtracts, which is how *opt in broadly, except these* is
     *   written: a pattern reaching the mainline tips opts them in when [refs] carries them, as it
     *   does by default, and their ancestry is everything the mainlines ever merged, so every
     *   branch with `^refs/heads/main` taken back out is every side branch. A bare star opts in
     *   every tag as well, and a tag on a mainline reaches what that mainline had merged. Since the
     *   empty case here is *no ref*, a value holding nothing but subtractions has nothing to take
     *   back out and is refused.
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
     * @param notes whether every input's `refs/notes/` is read and rekeyed onto the commits this run
     *   writes. Off by default: reading a third namespace is work a run that has no notes should not
     *   pay for, and rewriting one is a decision rather than a detail.
     *
     *   It is a flag rather than a pattern because notes are not history. A note contributes no
     *   commit and reaches no ancestry, so it cannot be part of the selection that decides what is
     *   read — which is also why [refs] refuses a pattern aimed at `refs/notes/`.
     */
    fun read(
        repositories: List<SourceRepository>,
        orderBy: OrderBy,
        requested: MainlineRequest = MainlineRequest.NONE,
        refs: ScopedPatterns = ScopedPatterns.NONE,
        interleaveRefs: ScopedPatterns = ScopedPatterns.NONE,
        labelRefs: ScopedPatterns = ScopedPatterns.NONE,
        notes: Boolean = false,
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }

        val mainlines = resolveMainlines(repositories, requested)
        val outputBranch = mainlines.output
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Commit>(repositories.size)
        val original = HashMap<Commit, SourceCommit>()
        val inputs = ArrayList<SourceInputs>(repositories.size)
        var labelsAttached = 0
        var labelsSkipped = 0
        var notesAttached = 0
        var notesSkipped = 0

        for ((index, repo) in repositories.withIndex()) {
            val source = builder.addSource(repo.name)

            val selected = Selection(refs.of(index), emptyMeans = true)
            // Empty matches nothing here, the other way round from the selection: a label is
            // something a run asks for, where carrying the refs over is what it does by default.
            val labelled = Selection(labelRefs.of(index), emptyMeans = false)

            val mainline = mainlines.perInput[index]
            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            // Namespaces beyond the two known ones are read only where a pattern names one, so a
            // bare star and a star under `refs/` still mean every branch and every tag: a Gerrit
            // change or a clone's own branch is reached by asking for it.
            val foreign = foreignRefs(repo, refs.of(index) + labelRefs.of(index))

            // Every ref this input offers, under the namespace it was found in, so that one loop
            // decides what is read and one decides what it is called.
            val offered =
                repo.branches().map { SourceRef(Constants.R_HEADS, it.name, it.target) } +
                    repo.tags().map { SourceRef(Constants.R_TAGS, it.name, it.target, it.annotation) } +
                    foreign

            val takenBySelection = offered.filter { selected.matches(it.fullName) }
            // Labels are what the selection did not already take. A ref matched by both is selected,
            // which is the wider meaning of the two: it is read from as well as recreated. The
            // mainline is no label either: it is read whatever is selected, and the braid's own
            // branch stands for it, so the writer skips it and a count of it would be of nothing.
            val takenByLabel = offered.filter {
                it.fullName != Constants.R_HEADS + mainline &&
                    !selected.matches(it.fullName) && labelled.matches(it.fullName)
            }

            // The refs the graph is read from, and the objects they point at. Both are needed and
            // they are not the same thing: the walk starts from objects, while the fetch that later
            // fills the output has to name refs.
            val readRefs = LinkedHashSet<String>()
            val tips = LinkedHashSet<ObjectId>()
            readRefs += Constants.R_HEADS + mainline
            tips += mainlineTip
            for (ref in takenBySelection) {
                readRefs += ref.fullName
                tips += ref.target
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
            fun carried(ref: SourceRef, pattern: RefPattern?, labelOnly: Boolean): BraidOutRef? {
                val commit = builder.find(source, ref.target.name) ?: return null
                return BraidOutRef(ref.fullName, commit, ref.annotation, labelOnly, ref.namespace, pattern)
            }
            val selectedRefs = takenBySelection.mapNotNull {
                carried(it, selected.taking(it.fullName), labelOnly = false)
            }
            val labelledRefs = takenByLabel.mapNotNull {
                carried(it, labelled.taking(it.fullName), labelOnly = true)
            }
            labelsAttached += labelledRefs.size
            labelsSkipped += takenByLabel.size - labelledRefs.size

            // After the commits, because a note is keyed by the object it annotates and the graph is
            // the only thing that can say whether this run wrote that object at all.
            val sourceNotes = if (!notes) emptyList() else repo.notes().map { notesRef ->
                val rekeyed = LinkedHashMap<Commit, ObjectId>(notesRef.entries.size)
                for ((sha, blob) in notesRef.entries) {
                    val commit = builder.find(source, sha)
                    if (commit == null) notesSkipped++ else rekeyed[commit] = blob
                }
                notesAttached += rekeyed.size
                BraidNotes(notesRef.name, rekeyed, notesRef.author, notesRef.committer, notesRef.message)
            }

            inputs += SourceInputs(
                source = source,
                mainlineBranch = mainline,
                mainlineTip = mainlineTip,
                refs = selectedRefs + labelledRefs,
                notes = sourceNotes.filter { it.entries.isNotEmpty() },
                noteRefs = sourceNotes.map { Constants.R_NOTES + it.name },
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
            interleaveTips = interleaveTips(interleaveRefs, inputs),
            labelsAttached = labelsAttached,
            labelsSkipped = labelsSkipped,
            notesAttached = notesAttached,
            notesSkipped = notesSkipped,
        )
    }

    /**
     * Every ref of every namespace [patterns] names beyond `refs/heads/` and `refs/tags/`.
     *
     * Driven by the patterns rather than by a listing of everything, which is what keeps the empty
     * case where it was: a run that names no foreign namespace reads none, so a bare star and a
     * star under `refs/` still mean every branch and every tag, and nothing a forge or a clone left
     * lying about.
     *
     * The two known namespaces are excluded whatever a pattern's head enumerated, since a branch is
     * already read as a branch; without that, a pattern whose head cuts back to plain `refs/` would
     * offer every branch a second time, under the rules meant for what the program does not know.
     */
    private fun foreignRefs(
        repo: SourceRepository,
        patterns: List<RefPattern>,
    ): List<SourceRef> {
        val namespaces = patterns
            // A pattern that subtracts names nothing to read; it only takes back what something
            // else brought in, so it cannot be what opens a namespace.
            .filterNot { it.negated }
            .filterNot { pattern -> RefKind.entries.any { reaches(pattern.glob, it.namespace) } }
            .mapNotNull { foreignNamespace(it.glob) }
            .toSortedSet()
        if (namespaces.isEmpty()) return emptyList()

        val seen = LinkedHashMap<String, SourceRef>()
        for (namespace in namespaces) {
            for (ref in repo.refsUnder(namespace)) {
                val full = namespace + ref.name
                if (RefKind.entries.any { full.startsWith(it.namespace) }) continue
                seen.getOrPut(full) { SourceRef(namespace, ref.name, ref.target, ref.annotation) }
            }
        }
        return seen.values.toList()
    }

    /**
     * One ref of an input as it stands there, before anything decides what it is called after.
     *
     * [namespace] is the one it was enumerated under, so [name] is what a prefix would go in front
     * of and the two still join back into the name every pattern is matched against.
     */
    private class SourceRef(
        val namespace: String,
        val name: String,
        val target: ObjectId,
        val annotation: TagAnnotation? = null,
    ) {
        val fullName: String get() = namespace + name
    }

    /**
     * The refs the patterns match, as the commits they name, deduplicated.
     *
     * Matching is against the *full* ref name, because a short name cannot say whether `v1.0` is a
     * branch or a tag, and a pattern that cannot express the difference would be a trap. The star
     * spans path separators, so a pattern ending in one covers a whole prefix however deeply nested,
     * and a bare star is every branch and every tag the run selected — which puts the whole graph
     * they reach in scope. A ref of another namespace is taken only by a pattern that names that
     * namespace, as the selection takes one.
     * A label is none of those, whatever the pattern says: see the loop.
     *
     * A `^` pattern subtracts, and [Selection] does it here by ref name, before a commit is reached
     * — so a commit two refs name is opted in by the one that was not subtracted rather than by
     * neither.
     */
    private fun interleaveTips(
        patterns: ScopedPatterns,
        inputs: List<SourceInputs>,
    ): List<Commit> {
        val tips = LinkedHashSet<Commit>()
        for ((index, input) in inputs.withIndex()) {
            val opted = Selection(patterns.of(index), emptyMeans = false)
            // A label is skipped whatever the pattern says. That is the whole of the separation:
            // the interleave matches what a run chose to read, and a label chose nothing.
            for (ref in input.refs) {
                if (ref.labelOnly) continue
                if (opted.matches(ref.fullName)) tips += ref.commit
            }
        }
        return tips.toList()
    }

    /**
     * What a set of patterns says about a ref: whether it is taken, and where it is written.
     *
     * [emptyMeans] is the whole difference between the options. No pattern means *every branch and
     * tag* for the selection, so narrowing a run is opt-in and a plain invocation still loads
     * everything; the interleave and the labels read their own empty list the other way, a ref that
     * delays a merge or gets a name it did not earn being the exception rather than the rule.
     *
     * A pattern that subtracts is applied after the ones that select, and over the empty case as
     * readily as over a positive pattern: with [emptyMeans] true, `^refs/heads/wip` alone is *every
     * branch and tag but that one* — the two namespaces a selection reads, a ref outside them being
     * on offer only where a pattern names it. That is where subtractions alone part company with
     * git, which drops the configured refspec as soon as the command line names one and so gives
     * nothing back for a command line holding only subtractions. The rule is the same either way —
     * a subtraction takes refs out of what was selected — and only the empty case underneath it
     * differs.
     *
     * **Subtraction is by ref name, not by commit.** A commit two refs name is in as long as one of
     * them survives, which is not a shortcut: once a branch is merged into a mainline its commits
     * *are* that mainline's ancestry, and asking for them to be out while the mainline is in asks
     * for a contradiction. So a subtraction bites where a ref carries history of its own, and is a
     * no-op against a ref whose history something else already reaches.
     */
    private class Selection(patterns: List<RefPattern>, private val emptyMeans: Boolean) {

        private val matchers = patterns.filterNot { it.negated }.map { glob(it.glob) to it }
        private val subtractors = patterns.filter { it.negated }.map { glob(it.glob) }

        fun matches(name: String): Boolean = selected(name) && subtractors.none { it(name) }

        /** Whether a pattern that selects took [name], the empty case standing in for having none. */
        private fun selected(name: String): Boolean {
            // A ref outside refs/heads/ and refs/tags/ is on offer only because some pattern named
            // its namespace, and only a pattern that would itself have named it may take it. The
            // condition is [foreignRefs]'s own, applied to the taking rather than to the
            // enumerating: without it a bare star, or `refs/*`, takes what another option's
            // pattern opened — which is how a --label-ref comes to decide what --ref reads.
            val known = RefKind.entries.any { name.startsWith(it.namespace) }
            if (matchers.isEmpty()) return emptyMeans && known
            return matchers.any { (match, pattern) -> match(name) && (known || names(pattern, name)) }
        }

        /** Whether [pattern] is one that would have put a ref named [name] on offer at all. */
        private fun names(pattern: RefPattern, name: String): Boolean =
            RefKind.entries.none { reaches(pattern.glob, it.namespace) } &&
                foreignNamespace(pattern.glob)?.let { name.startsWith(it) } == true

        /**
         * The pattern that took [name], or `null` where none did.
         *
         * The first match decides, which is why [ScopedPatterns.of] puts the scoped patterns first:
         * *this input's branches as tags, everything else as it stands* has to be writable, and only
         * a more specific pattern winning makes it so.
         *
         * A pattern that named [name]'s own namespace comes before that, whatever its position: a
         * ref outside refs/heads/ and refs/tags/ is on offer only because such a pattern asked for
         * it, and it is the one carrying the destination such a ref has to be given. Letting a bare
         * star win instead leaves the ref with no rule for its name, decided by the order two
         * values happen to be written in.
         *
         * A subtracted ref was taken by nothing, whoever else matched it. Callers reach this only
         * for refs [matches] already let through, so the guard says what the answer means rather
         * than changing any of them.
         */
        fun taking(name: String): RefPattern? = when {
            !matches(name) -> null
            else -> matchers.firstOrNull { (match, pattern) -> match(name) && names(pattern, name) }?.second
                ?: matchers.firstOrNull { (match, _) -> match(name) }?.second
        }
    }

    /** Which branch each input braids along, by position, and the single branch the output carries. */
    private class Mainlines(val perInput: List<String>, val output: String)

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
        requested: MainlineRequest,
    ): Mainlines {
        val common = requested.common
        val awaiting = repositories.filterIndexed { index, _ -> index !in requested.scoped }
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

        val perInput = repositories.mapIndexed { index, repo ->
            requested.scoped[index] ?: detected ?: error("no mainline for '${repo.name}'")
        }
        return Mainlines(perInput, common ?: perInput.first())
    }
}
