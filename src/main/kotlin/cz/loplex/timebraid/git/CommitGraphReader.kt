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
 * One ref carried over: where it came from, what it points at, and what it is called in the output.
 *
 * The output name is held in two parts because the run may have written either, neither or both of
 * them. [namespace] and [name] are always concatenated to make it; [prefixed] says whether the
 * naming rule for [namespace] — `--branch-prefix`, `--tag-prefix` — goes between the two.
 *
 * That is the whole of how a destination and a prefix get along. A run that gives no destination
 * leaves both to the prefix rule; one that names a namespace takes that half; one that spells out a
 * pattern takes all of it and the prefix stays out. Nothing here has to know which of the three
 * happened.
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
    /** Namespace the output writes it into, `refs/heads/` and the like, with its trailing slash. */
    val namespace: String,
    /** Name below [namespace]. */
    val name: String,
    /** Whether the prefix belonging to [namespace] goes between the two. */
    val prefixed: Boolean = true,
) {
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
     * The `refs` pattern that `-b/--branch` is shorthand for.
     *
     * A git branch name cannot contain a star, so the short form desugars into the general one
     * exactly — there is no name for which the two select differently, and nothing to escape. Nor
     * can it contain a space, which is what lets one argument carry several of them: see [words],
     * through which `-b` passes for the same reason every other option in its group does.
     *
     * The rest of the group's grammar belongs around the branch name rather than inside it, so only
     * the pattern is prefixed and the scope, the subtracting `^` and a destination are held back:
     * `backend::^wip` becomes `backend::^refs/heads/wip`. Neither a `:` nor a `^` can occur in a ref
     * name, so nothing a branch could legitimately be called is mistaken for one of them.
     *
     * What is malformed as a `-b` value is refused here, naming `-b` and the text as written: its
     * fields, and its scope and destination by the rules a `--ref` is held to ([scopeOf],
     * [destinationOf]). Left to the pattern parser the same refusal would name `--ref` and the
     * desugared value, neither of which the user typed.
     *
     * @param inputs the names an `<input>::` scope may take.
     */
    fun branchPattern(value: String, inputs: Collection<String>): String {
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
        val destination = fields.getOrNull(1)?.let { destinationOf("-b", value, it, negated) }
        destination?.let { checkDestination("-b", value, heads, RefPattern(heads, it)) }
        return (if (input == null) "" else input + SCOPE) + (if (negated) "^" else "") + heads +
            (if (destination == null) "" else ":$destination")
    }

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
        mainlineBranch: List<String> = emptyList(),
        refs: List<String> = emptyList(),
        interleaveRefs: List<String> = emptyList(),
        labelRefs: List<String> = emptyList(),
        notes: Boolean = false,
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }
        require(repositories.map { it.name }.toSet().size == repositories.size) {
            "two input repositories have the same name"
        }

        val names = repositories.map { it.name }
        // Before the mainlines, so that a mistyped input name is reported as the mistyped input name
        // rather than being masked by whatever the mainline resolution makes of the run.
        val scopedRefs = ScopedPatterns(refs, "--ref", names, emptyMeans = true, destinations = true)
        val scopedLabels =
            ScopedPatterns(labelRefs, "--label-ref", names, emptyMeans = false, destinations = true)
        val scopedInterleave =
            ScopedPatterns(interleaveRefs, "--interleave-ref", names, emptyMeans = false)
        val mainlines = resolveMainlines(repositories, mainlineBranch)
        val outputBranch = mainlines.output
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Commit>(repositories.size)
        val original = HashMap<Commit, SourceCommit>()
        val inputs = ArrayList<SourceInputs>(repositories.size)
        var labelsAttached = 0
        var labelsSkipped = 0
        var notesAttached = 0
        var notesSkipped = 0

        for (repo in repositories) {
            val source = builder.addSource(repo.name)

            val selected = Selection(scopedRefs.of(repo.name), emptyMeans = true)
            // Empty matches nothing here, the other way round from the selection: a label is
            // something a run asks for, where carrying the refs over is what it does by default.
            val labelled = Selection(scopedLabels.of(repo.name), emptyMeans = false)

            val mainline = mainlines.perInput.getValue(repo.name)
            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            // Namespaces beyond the two known ones are read only where a pattern names one, so a
            // bare star and a star under `refs/` still mean every branch and every tag: a Gerrit
            // change or a clone's own branch is reached by asking for it.
            val foreign = foreignRefs(repo, scopedRefs.of(repo.name) + scopedLabels.of(repo.name))

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
            val selectedRefs = takenBySelection.mapNotNull {
                carried(it, selected.taking(it.fullName), builder, source, repo.name, labelOnly = false)
            }
            val labelledRefs = takenByLabel.mapNotNull {
                carried(it, labelled.taking(it.fullName), builder, source, repo.name, labelOnly = true)
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
            interleaveTips = interleaveTips(scopedInterleave, inputs),
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
     * What [ref] becomes in the output under [pattern], or `null` when its target was never read.
     *
     * The three destination forms land here as the two halves of a name plus one boolean. A
     * namespace destination swaps the namespace and leaves the rest to that namespace's prefix; a
     * spelled-out destination fills both halves and turns the prefix off; no destination at all
     * keeps the ref exactly where it was.
     */
    private fun carried(
        ref: SourceRef,
        pattern: RefPattern?,
        builder: CommitGraphBuilder,
        source: Source,
        repo: String,
        labelOnly: Boolean,
    ): BraidOutRef? {
        val commit = builder.find(source, ref.target.name) ?: return null
        val spelled = pattern?.resolve(ref.fullName, repo)
        return when {
            spelled != null -> BraidOutRef(
                fullName = ref.fullName,
                commit = commit,
                annotation = ref.annotation,
                labelOnly = labelOnly,
                // Split at the last separator so the two halves still join back, and so a name under
                // refs/heads or refs/tags is still counted as the branch or the tag it became.
                namespace = spelled.substringBeforeLast('/') + "/",
                name = spelled.substringAfterLast('/'),
                prefixed = false,
            )
            pattern?.toNamespace == true -> BraidOutRef(
                fullName = ref.fullName,
                commit = commit,
                annotation = ref.annotation,
                labelOnly = labelOnly,
                namespace = pattern.destination!!,
                name = ref.name,
            )
            else -> BraidOutRef(
                fullName = ref.fullName,
                commit = commit,
                annotation = ref.annotation,
                labelOnly = labelOnly,
                namespace = ref.namespace,
                name = ref.name,
            )
        }
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
        for (input in inputs) {
            val opted = Selection(patterns.of(input.source.name), emptyMeans = false)
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
    private class RefPattern(
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
         * [name] is the ref's full name in the input, which is what the glob matched, and [repo] is
         * the input's own name, for a `{repo}` in the destination. Only the spelled-out forms are
         * resolved here; a namespace destination is [toNamespace] and the writer's business.
         */
        fun resolve(name: String, repo: String): String? {
            if (destination == null || toNamespace) return null
            val spelled = destination.replace("{repo}", repo)
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
     * A pattern, the input it speaks for, and where its matches land, written
     * `[<input>::][^]<refspec>`: `backend::refs/heads/main` narrows to one input, while a pattern
     * with no `::` speaks for every one of them.
     *
     * **What follows the scope is git's refspec**, `<pattern>[:<destination>]`, so a value valid as
     * a git refspec means the same here: `refs/heads/main:refs/tags/main` reads the branch and
     * writes it as a tag. The differences are few and each on purpose: a destination may be a
     * namespace (`refs/tags/`) handed to that namespace's prefix, and may hold `{repo}`; a pattern
     * with stars may go without a destination, which `git fetch` allows only in a negative refspec,
     * or name one ref as its destination; and there is no `+`, no empty pattern or destination, and
     * no short name, every pattern matching full ref names.
     *
     * **The scope is ended by `::`**, the separator the `<repo>` grammar puts between a location and
     * its suffix. The separator is decidable rather than a convention: a refspec holds at most one
     * `:`, so never a `::`.
     *
     * **The empty case stays per input, and keeps the meaning it had.** No pattern for an input is
     * that option's empty case for that input — every branch and tag for [Selection], none for the
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
     * @param emptyMeans what no pattern for an input says about that input — every branch and tag,
     *   or none. It is also what a run holding nothing but subtractions resolves against, which is
     *   why it is known here and not only in [Selection]: where the empty case is no ref at all
     *   there is nothing to take back out, and such a run is refused rather than quietly matching
     *   nothing.
     * @param destinations whether a destination is meaningful at all. It is for the two options that
     *   write refs; for the one that decides what may weigh on the braid it would name a namespace
     *   nothing is ever written to, so it is refused rather than accepted and ignored.
     */
    private class ScopedPatterns(
        raw: List<String>,
        option: String,
        inputs: Collection<String>,
        emptyMeans: Boolean,
        destinations: Boolean = false,
    ) {
        private val unscoped = ArrayList<RefPattern>()
        private val byInput = HashMap<String, MutableList<RefPattern>>()

        init {
            for (value in words(raw, option)) {
                val (input, written) = scopeOf(option, value, inputs)
                // A leading '^' is decidable rather than a convention: git refuses a '^' anywhere in
                // a ref name, so one here can only be the mark.
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
                if (input == null) unscoped += pattern else byInput.getOrPut(input) { ArrayList() } += pattern
            }

            // Checked per input rather than per run, because the empty case is per input: an
            // unscoped subtraction and no positive anywhere leaves every input with nothing to take
            // it out of, while one input's scoped subtraction is answered by an unscoped positive.
            if (!emptyMeans) {
                for (input in inputs) {
                    val patterns = of(input)
                    require(patterns.isEmpty() || patterns.any { !it.negated }) {
                        "$option holds nothing but patterns that subtract for input '$input', and " +
                            "its empty case is no ref at all -- so there is nothing to take back " +
                            "out. Say what is opted in first, a star for all of it"
                    }
                }
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
    private fun reaches(pattern: String, namespace: String): Boolean {
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
    private fun foreignNamespace(glob: String): String? {
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
     * one is refused, the unscoped form already saying it; so is a `^` opening it, the mark written a
     * scope too early, and a name that is none of [inputs].
     */
    private fun scopeOf(option: String, value: String, inputs: Collection<String>): Pair<String?, String> {
        val at = value.indexOf(SCOPE)
        if (at < 0) return null to value
        val input = value.substring(0, at)
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
        require(input in inputs) {
            "$option '$value' is for input '$input', which is not one of: " + inputs.joinToString()
        }
        return input to value.substring(at + SCOPE.length)
    }

    /** The `::` that ends a scope — see [scopeOf]. */
    private const val SCOPE = "::"

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
        inputs: Collection<String>,
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
}
