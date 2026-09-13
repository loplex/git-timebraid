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
    /** The branch name resolved as the mainline in every repository. */
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

/**
 * A branch resolved to the commit it points at.
 *
 * [labelOnly] marks one attached by `--label-ref` rather than selected by `--ref`: it named a commit
 * the graph already held instead of bringing one in, and it is not eligible to weigh on the braid.
 */
class BraidRef(val name: String, val commit: Commit, val labelOnly: Boolean = false)

/** A tag resolved to the commit it peels to, keeping its annotation if it had one. */
class BraidTag(
    val name: String,
    val commit: Commit,
    val annotation: TagAnnotation?,
    /** As [BraidRef.labelOnly]. */
    val labelOnly: Boolean = false,
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
     * exactly — there is no name for which the two select differently, and nothing to escape.
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
     *   it, and the output's branch of that name is written from the braid's tip rather than from
     *   this selection ([BraidWriter] does that unconditionally).
     * @param interleaveRefs glob patterns matched against full ref names — `refs/tags/v1.*` for a
     *   release series, a star alone for every ref — applied in every input repository. A matched
     *   ref's ancestry is allowed to delay a braid commit; see `BraidInterleave` for what that
     *   trades away. Matched against what [refs] selected, so widening the interleave cannot widen
     *   what is loaded.
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
        mainlineBranch: String? = null,
        refs: List<String> = emptyList(),
        interleaveRefs: List<String> = emptyList(),
        labelRefs: List<String> = emptyList(),
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }
        require(repositories.map { it.name }.toSet().size == repositories.size) {
            "two input repositories have the same name"
        }

        val mainline = resolveMainline(repositories, mainlineBranch)
        val selected = selection(refs)
        // Empty matches nothing here, the other way round from the selection: a label is something
        // a run asks for, where carrying the refs over is what it does by default.
        val labelled = if (labelRefs.isEmpty()) ({ _: String -> false }) else globs(labelRefs)
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Commit>(repositories.size)
        val original = HashMap<Commit, SourceCommit>()
        val inputs = ArrayList<SourceInputs>(repositories.size)
        var labelsAttached = 0
        var labelsSkipped = 0

        for (repo in repositories) {
            val source = builder.addSource(repo.name)

            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            val selectedBranches = repo.branches().filter { selected("${Constants.R_HEADS}${it.name}") }
            val selectedTags = repo.tags().filter { selected("${Constants.R_TAGS}${it.name}") }

            // Labels are what the selection did not already take. A ref matched by both is selected,
            // which is the wider meaning of the two: it is read from as well as recreated. The
            // mainline is no label either: it is read whatever is selected, and the braid's own
            // branch stands for it, so the writer skips it and a count of it would be of nothing.
            val labelledBranches = repo.branches().filter {
                val name = "${Constants.R_HEADS}${it.name}"
                it.name != mainline && !selected(name) && labelled(name)
            }
            val labelledTags = repo.tags().filter {
                val name = "${Constants.R_TAGS}${it.name}"
                !selected(name) && labelled(name)
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
                builder.find(source, branch.target.name)
                    ?.let { BraidRef(branch.name, it, labelOnly = true) }
            }
            val labelTagRefs = labelledTags.mapNotNull { tag ->
                builder.find(source, tag.target.name)
                    ?.let { BraidTag(tag.name, it, tag.annotation, labelOnly = true) }
            }
            labelsAttached += labelBranchRefs.size + labelTagRefs.size
            labelsSkipped +=
                (labelledBranches.size - labelBranchRefs.size) +
                (labelledTags.size - labelTagRefs.size)

            inputs += SourceInputs(
                source = source,
                branches = selectedBranches.mapNotNull { branch ->
                    builder.find(source, branch.target.name)?.let { BraidRef(branch.name, it) }
                } + labelBranchRefs,
                tags = selectedTags.mapNotNull { tag ->
                    builder.find(source, tag.target.name)
                        ?.let { BraidTag(tag.name, it, tag.annotation) }
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
            mainlineBranch = mainline,
            commits = original,
            sources = inputs,
            interleaveTips = interleaveTips(interleaveRefs, inputs),
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
    private fun interleaveTips(patterns: List<String>, inputs: List<SourceInputs>): List<Commit> {
        if (patterns.isEmpty()) return emptyList()
        val matches = globs(patterns)
        val tips = LinkedHashSet<Commit>()
        for (input in inputs) {
            // A label is skipped whatever the pattern says. That is the whole of the separation:
            // the interleave matches what a run chose to read, and a label chose nothing.
            for (branch in input.branches) {
                if (branch.labelOnly) continue
                if (matches("${Constants.R_HEADS}${branch.name}")) tips += branch.commit
            }
            for (tag in input.tags) {
                if (tag.labelOnly) continue
                if (matches("${Constants.R_TAGS}${tag.name}")) tips += tag.commit
            }
        }
        return tips.toList()
    }

    /**
     * Which refs a run carries over, as a predicate over full ref names.
     *
     * No pattern means every ref, so the two options that narrow a run are opt-in and a plain
     * invocation still loads everything. This is the one place the empty case means *all* rather
     * than *none*; the interleave reads its own empty list the other way, since a ref that delays a
     * merge is the exception there rather than the rule.
     */
    private fun selection(patterns: List<String>): (String) -> Boolean {
        if (patterns.isEmpty()) return { true }
        return globs(patterns)
    }

    /**
     * Matches a full ref name against any of [patterns], where `*` is the only metacharacter and it
     * spans path separators — so a pattern ending in one covers a whole prefix however deeply
     * nested, and a bare star is every ref.
     */
    private fun globs(patterns: List<String>): (String) -> Boolean {
        val matchers = patterns.map { p -> Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) }
        return { name -> matchers.any { it.matches(name) } }
    }

    private fun resolveMainline(repositories: List<SourceRepository>, requested: String?): String {
        if (requested != null) {
            val missing = repositories.filter { it.resolveBranch(requested) == null }
            require(missing.isEmpty()) {
                "branch '$requested' is missing in: ${missing.joinToString { it.name }}"
            }
            return requested
        }
        for (candidate in MAINLINE_CANDIDATES) {
            if (repositories.all { it.resolveBranch(candidate) != null }) return candidate
        }
        error(
            "no branch is common to every input (looked for ${MAINLINE_CANDIDATES.joinToString()}); " +
                "pass --mainline-branch to name one"
        )
    }
}
