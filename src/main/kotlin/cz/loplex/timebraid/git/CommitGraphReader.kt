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

/** A branch resolved to the commit it points at. */
class BraidRef(val name: String, val commit: Commit)

/** A tag resolved to the commit it peels to, keeping its annotation if it had one. */
class BraidTag(val name: String, val commit: Commit, val annotation: TagAnnotation?)

/**
 * Reads a set of [SourceRepository] into the single [CommitGraph] the planner works on.
 *
 * The model is deliberately global: load *every* commit of *every* strand at once — reachable from
 * the branches to be recreated and from all tags — and let the planner reparent only the braid.
 * Off-braid commits keep their original parents, so a branch that exists in one repository still
 * forks off the braid at the right moment with no special handling (see the Branches section of
 * doc/how-it-works.md).
 */
object CommitGraphReader {

    /** Branches tried, in order, when `--mainline-branch` is not given. */
    val MAINLINE_CANDIDATES = listOf("main", "master", "develop")

    /**
     * @param branches short branch names to load (and later recreate); `null` loads them all. The
     *   resolved mainline branch is always loaded even if it is not in this set — the braid needs it.
     * @param interleaveRefs glob patterns matched against full ref names — `refs/tags/v1.*` for a
     *   release series, a star alone for every ref — applied in every input repository. A matched
     *   ref's ancestry is allowed to delay a braid commit; see `BraidInterleave` for what that
     *   trades away.
     */
    fun read(
        repositories: List<SourceRepository>,
        orderBy: OrderBy,
        mainlineBranch: String? = null,
        branches: Set<String>? = null,
        interleaveRefs: List<String> = emptyList(),
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }
        require(repositories.map { it.name }.toSet().size == repositories.size) {
            "two input repositories have the same name"
        }

        val mainline = resolveMainline(repositories, mainlineBranch)
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Commit>(repositories.size)
        val original = HashMap<Commit, SourceCommit>()
        val inputs = ArrayList<SourceInputs>(repositories.size)

        for (repo in repositories) {
            val source = builder.addSource(repo.name)

            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            val selectedBranches = repo.branches().filter { branches == null || it.name in branches }
            val selectedTags = repo.tags()

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
            inputs += SourceInputs(
                source = source,
                branches = selectedBranches.mapNotNull { branch ->
                    builder.find(source, branch.target.name)?.let { BraidRef(branch.name, it) }
                },
                tags = selectedTags.mapNotNull { tag ->
                    builder.find(source, tag.target.name)
                        ?.let { BraidTag(tag.name, it, tag.annotation) }
                },
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
        )
    }

    /**
     * The refs the patterns match, as the commits they name, deduplicated.
     *
     * Matching is against the *full* ref name, because a short name cannot say whether `v1.0` is a
     * branch or a tag, and a pattern that cannot express the difference would be a trap. The star
     * spans path separators, so a pattern ending in one covers a whole prefix however deeply nested,
     * and a bare star is every ref — which puts the whole graph they reach in scope.
     */
    private fun interleaveTips(patterns: List<String>, inputs: List<SourceInputs>): List<Commit> {
        if (patterns.isEmpty()) return emptyList()
        val matchers = patterns.map { glob(it) }
        val tips = LinkedHashSet<Commit>()
        for (input in inputs) {
            for (branch in input.branches) {
                if (matchers.any { it.matches("${Constants.R_HEADS}${branch.name}") }) {
                    tips += branch.commit
                }
            }
            for (tag in input.tags) {
                if (matchers.any { it.matches("${Constants.R_TAGS}${tag.name}") }) tips += tag.commit
            }
        }
        return tips.toList()
    }

    /** A glob over ref names: `*` is the only metacharacter and it spans path separators. */
    private fun glob(pattern: String): Regex =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })

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
