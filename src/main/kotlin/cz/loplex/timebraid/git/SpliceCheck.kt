package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId

/**
 * One input placed inside another, as their destinations put it.
 *
 * Only the *immediate* container is named. With a repository at the output root, one at `libs` and
 * one at `libs/backend`, the splices are root→`libs` and `libs`→`libs/backend`, and no third one:
 * what the output holds at `libs` is the middle repository's tree, so it is the only one the
 * innermost can collide with.
 */
class Splice(
    /** The repository whose tree is opened up. */
    val outer: Source,
    /** The repository placed inside it. */
    val inner: Source,
    /** Where [outer]'s content sits, or `null` for the repository at the output root. */
    val outerSubdir: String?,
    /** Where [inner]'s content sits — always inside [outerSubdir]. */
    val innerSubdir: String,
) {

    /** [innerSubdir] below [outerSubdir], segment by segment; never empty. */
    val below: List<String> =
        (if (outerSubdir == null) innerSubdir else innerSubdir.removePrefix("$outerSubdir/"))
            .split('/')

    /** Where the containing repository's content sits, for a message. */
    val outerPath: String get() = outerSubdir ?: "the output root"

    init {
        require(outerSubdir == null || innerSubdir.startsWith("$outerSubdir/")) {
            "'$innerSubdir' does not lie inside '$outerSubdir'"
        }
        require(below.isNotEmpty() && below.none { it.isEmpty() }) {
            "'$innerSubdir' does not lie inside '$outerSubdir'"
        }
    }
}

/** What the check found for one [Splice], for the closing report. */
class SpliceReport(
    val splice: Splice,
    /** Braid positions where both repositories have content, so the splice is actually made. */
    val commits: Int,
    /** Distinct trees of the containing repository the splice was checked against. */
    val trees: Int,
)

/**
 * Answers, before anything is written, the one question about a splice the planner cannot: whether
 * the repository being opened up actually has something in the way where another is placed inside
 * it.
 *
 * The planner decides *whether* a destination may contain another — `--splice`, or the repository at
 * the output root, which every destination is inside of. Whether that placement collides is a
 * question about a tree, and a repository's tree is different at every commit, so the answer can be
 * yes at one point of the braid and no at another. Left to the writer alone it surfaces as a run
 * that copies most of a history and then stops, which is why it is asked here instead: one pass over
 * the plan, no objects written, and the same answer.
 *
 * The pass is cheap because the trees repeat. A containing repository stands still for as many braid
 * positions as the other inputs have commits, and its tree is examined once per *distinct* tree
 * rather than once per position.
 */
class SpliceCheck(
    private val plan: MergePlan,
    private val inputs: BraidInputs,
    private val repoOf: Map<Source, SourceRepository>,
) {

    /**
     * One report per splice the plan makes, or an error naming every splice that collides.
     *
     * A collision is reported once per splice — at the first commit that shows it — because the
     * same one usually repeats over a long stretch of the braid, and one locatable example is what
     * a remedy needs.
     */
    fun check(): List<SpliceReport> {
        val splices = splices()
        if (splices.isEmpty()) return emptyList()

        val commits = IntArray(splices.size)
        val checked = Array(splices.size) { HashSet<ObjectId>() }
        val collisions = arrayOfNulls<String>(splices.size)

        for (planned in plan.commits) {
            val content = plan.contentOf(planned.commit)
            for ((i, splice) in splices.withIndex()) {
                val holder = content[splice.outer] ?: continue
                if (splice.inner !in content) continue
                commits[i]++
                if (collisions[i] != null) continue
                val tree = treeOf(holder)
                if (!checked[i].add(tree)) continue
                collisionAt(splice, tree)?.let { collisions[i] = "$it at ${planned.commit}" }
            }
        }

        val found = collisions.filterNotNull()
        require(found.isEmpty()) {
            "one repository cannot be placed inside another where it is:\n" +
                found.joinToString("\n") { "  - $it" } +
                "\n  give that repository another subdirectory with <repo>::=<subdir>"
        }
        return splices.mapIndexed { i, splice -> SpliceReport(splice, commits[i], checked[i].size) }
    }

    /**
     * The message for the first thing in the way along [splice], walking [tree] — the containing
     * repository's root tree at one commit — or `null` when nothing is.
     *
     * A segment the containing repository does not have at all stops the walk with no collision:
     * the braid builds the tree there itself. A segment above the destination has to be a directory,
     * because that is what the braid descends into; the destination itself has to be free, because
     * what is written there is the inner repository's own tree object and nothing fits beside it.
     */
    private fun collisionAt(splice: Splice, tree: ObjectId): String? {
        val repo = repoOf.getValue(splice.outer)
        var current = tree
        var path = splice.outerSubdir ?: ""
        for ((depth, segment) in splice.below.withIndex()) {
            val here = if (path.isEmpty()) segment else "$path/$segment"
            val entry = repo.entriesOf(current).firstOrNull { it.name == segment } ?: return null
            if (depth == splice.below.size - 1) {
                return "subdirectory '$here' collides with an entry of the same name in " +
                    repo.name
            }
            if (entry.mode != FileMode.TREE) {
                return "'$here' is not a directory in ${repo.name}, so no repository can be " +
                    "placed inside it"
            }
            current = entry.id
            path = here
        }
        return null
    }

    /** The original root tree of [commit], which is what its content is in the output. */
    private fun treeOf(commit: Commit): ObjectId =
        inputs.commits[commit]?.tree ?: error("$commit is not a commit of these inputs")

    /**
     * Every input that lies inside another, paired with the one it lies *directly* inside — the
     * longest destination that is a proper prefix of its own, the repository at the output root
     * being the shortest of them.
     */
    private fun splices(): List<Splice> {
        val subdirs = plan.graph.sources.associateWith { plan.subdirOf(it) }
        val splices = ArrayList<Splice>()
        for ((inner, innerSubdir) in subdirs) {
            if (innerSubdir == null) continue
            val outer = subdirs.entries
                .filter { (source, subdir) ->
                    source !== inner && (subdir == null || innerSubdir.startsWith("$subdir/"))
                }
                .maxByOrNull { it.value?.length ?: -1 }
                ?: continue
            splices += Splice(outer.key, inner, outer.value, innerSubdir)
        }
        return splices
    }
}
