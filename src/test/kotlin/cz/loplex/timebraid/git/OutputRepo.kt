package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path

/**
 * Reads a braided output repository back as plain data, keyed by the *original* sha each commit
 * records in its provenance trailer. This is the view the integration tests assert against: they know
 * the inputs by the shas TestRepoBuilder produced, and the trailer is the only bridge from those to
 * the rewritten commits.
 */
object OutputRepo {

    class Commit(
        val id: ObjectId,
        val parents: List<ObjectId>,
        val tree: ObjectId,
        val message: String,
        /** Original sha, from `commit=<sha>` in the trailer. */
        val originalSha: String,
        /** Original parent shas, from `parents=<sha,sha>` in the trailer. */
        val originalParents: List<String>,
    )

    class Contents(val commits: List<Commit>, val byOriginalSha: Map<String, Commit>)

    private val COMMIT = Regex("commit=([0-9a-f]{40})")
    private val PARENTS = Regex("parents=([0-9a-f,]*)]")

    fun read(dir: Path): Contents {
        SourceRepository.open(dir).use { repo ->
            // Every ref, not only the branches and the tags: a run may carry a namespace of its
            // own, and a commit only such a ref reaches is still a commit the braid wrote. Notes
            // are the exception — their commits are the output's own bookkeeping and carry no
            // provenance trailer to read. An output written with --keep-remotes cannot be read
            // here at all: its refs/remotes/ point at the inputs' own commits, which carry none
            // either.
            val tips = repo.refsUnder(Constants.R_REFS)
                .filterNot { it.name.startsWith(Constants.R_NOTES.removePrefix(Constants.R_REFS)) }
                .map { it.target }
            val commits = repo.readReachable(tips).map { source ->
                val original = COMMIT.find(source.message)?.groupValues?.get(1)
                    ?: error("no provenance trailer on:\n${source.message}")
                val parents = PARENTS.find(source.message)?.groupValues?.get(1)
                    ?: error("no parent list in the trailer of:\n${source.message}")
                Commit(
                    id = source.id,
                    parents = source.parents,
                    tree = source.tree,
                    message = source.message,
                    originalSha = original,
                    originalParents = parents.split(",").filter { it.isNotEmpty() },
                )
            }
            return Contents(commits, commits.associateBy { it.originalSha })
        }
    }

    /**
     * The machine check behind the tool's central guarantee: read every commit's `parents=`
     * from the trailer and assert every one of those edges exists in the rewritten repository. This
     * is the guarantee — 100% of original edges preserved — stated as a test.
     */
    fun assertEveryOriginalEdgePreserved(dir: Path) {
        val contents = read(dir)
        for (commit in contents.commits) {
            for (parentSha in commit.originalParents) {
                val rewritten = contents.byOriginalSha[parentSha]
                    ?: error("original parent $parentSha of ${commit.originalSha} was not written")
                assertTrue(
                    rewritten.id in commit.parents,
                    "the edge $parentSha -> ${commit.originalSha} was lost in the braid",
                )
            }
        }
    }
}
