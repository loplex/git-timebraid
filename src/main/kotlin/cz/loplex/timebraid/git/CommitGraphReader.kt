package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.CommitGraph
import cz.loplex.timebraid.plan.CommitGraphBuilder
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
    val heads: IntArray,
    /** The branch name resolved as the mainline in every repository. */
    val mainlineBranch: String,
    /** The original commit behind every graph index, indexed exactly like [graph]. */
    val commits: List<SourceCommit>,
    /** Per input repository, in the order they were given to [CommitGraphReader.read]. */
    val sources: List<SourceInputs>,
)

/** What was read out of one input repository, with every ref resolved to a graph index. */
class SourceInputs(
    val name: String,
    val branches: List<BraidRef>,
    val tags: List<BraidTag>,
    /**
     * The objects the commits were walked from. The writer copies the trees and blobs reachable from
     * these into the output, so the set has to be exactly the one the graph was built from.
     */
    val tips: List<ObjectId>,
)

/** A branch resolved to the commit it points at. */
class BraidRef(val name: String, val commit: Int)

/** A tag resolved to the commit it peels to, keeping its annotation if it had one. */
class BraidTag(val name: String, val commit: Int, val annotation: TagAnnotation?)

/**
 * Reads a set of [SourceRepository] into the single [CommitGraph] the planner works on.
 *
 * The model is deliberately global: load *every* commit of *every* strand at once — reachable from
 * the branches to be recreated and from all tags — and let the planner reparent only the braid.
 * Off-braid commits keep their original parents, so a branch that exists in one repository still
 * forks off the braid at the right moment with no special handling (see the README's Branches
 * section).
 */
object CommitGraphReader {

    /** Branches tried, in order, when `--mainline-branch` is not given. */
    val MAINLINE_CANDIDATES = listOf("main", "master", "develop")

    /**
     * @param branches short branch names to load (and later recreate); `null` loads them all. The
     *   resolved mainline branch is always loaded even if it is not in this set — the braid needs it.
     */
    fun read(
        repositories: List<SourceRepository>,
        orderBy: OrderBy,
        mainlineBranch: String? = null,
        branches: Set<String>? = null,
    ): BraidInputs {
        require(repositories.isNotEmpty()) { "no input repositories" }
        require(repositories.map { it.name }.toSet().size == repositories.size) {
            "two input repositories have the same name"
        }

        val mainline = resolveMainline(repositories, mainlineBranch)
        val builder = CommitGraphBuilder()
        val heads = IntArray(repositories.size)
        val commits = ArrayList<SourceCommit?>()
        val inputs = ArrayList<SourceInputs>(repositories.size)

        for ((repoIndex, repo) in repositories.withIndex()) {
            val source = builder.addSource(repo.name)

            val mainlineTip = repo.resolveBranch(mainline)
                ?: error("repository '${repo.name}' has no branch '$mainline'")

            val selectedBranches = repo.branches().filter { branches == null || it.name in branches }
            val selectedTags = repo.tags()

            val tips = LinkedHashSet<ObjectId>()
            tips += mainlineTip
            selectedBranches.forEach { tips += it.target }
            selectedTags.forEach { tips += it.target }

            for (commit in repo.readReachable(tips)) {
                val index = builder.addCommit(
                    source = source,
                    id = commit.id.name,
                    orderingTime = commit.time(orderBy),
                    parentIds = commit.parents.map { it.name },
                )
                while (commits.size <= index) commits.add(null)
                commits[index] = commit
            }

            heads[repoIndex] = builder.indexOf(source, mainlineTip.name)
            inputs += SourceInputs(
                name = repo.name,
                branches = selectedBranches.mapNotNull { resolve(builder, source, it.name, it.target) },
                tags = selectedTags.mapNotNull { tag ->
                    resolve(builder, source, tag.name, tag.target)
                        ?.let { BraidTag(it.name, it.commit, tag.annotation) }
                },
                tips = tips.toList(),
            )
        }

        val graph = builder.build()
        check(commits.size == graph.size && commits.none { it == null }) {
            "the graph has ${graph.size} commits but ${commits.count { it != null }} were read"
        }

        @Suppress("UNCHECKED_CAST")
        return BraidInputs(graph, heads, mainline, commits as List<SourceCommit>, inputs)
    }

    /**
     * A ref whose target never made it into the graph is dropped rather than rejected: it points at
     * something that is not a commit, which is a fact about the input, not an error in the run.
     */
    private fun resolve(
        builder: CommitGraphBuilder,
        source: Int,
        name: String,
        target: ObjectId,
    ): BraidRef? {
        val index = builder.indexOf(source, target.name)
        return if (index == CommitGraph.NO_COMMIT) null else BraidRef(name, index)
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
