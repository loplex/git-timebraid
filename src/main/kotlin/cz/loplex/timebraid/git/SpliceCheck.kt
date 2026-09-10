package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId

/**
 * One input placed inside another, as their destinations put it.
 *
 * Which repository an input lies inside can change along the braid. With a repository at the output
 * root, one at `libs` and one at `libs/backend`, the innermost sits in the middle repository's tree
 * wherever that has content, and before the middle repository's first commit it sits in the root
 * repository's own `libs/` instead — which is where [TreeAssembler] puts it. So there are three
 * splices, root→`libs`, `libs`→`libs/backend` and root→`libs/backend`, and at each commit of the
 * braid the innermost input is checked against one of them: the innermost container that has
 * content there.
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
    /**
     * Commits of the output where both repositories have content and no repository between them
     * does, so the splice is made.
     */
    val commits: Int,
    /** Distinct trees of the containing repository the splice was checked against. */
    val trees: Int,
    /**
     * Of [commits], how many have a gitlink at the inner repository's destination that the inner
     * repository's own content replaces. Zero unless the run asked to dissolve, because without that
     * such a gitlink is a collision and there is no report at all.
     */
    val dissolved: Int = 0,
)

/**
 * Answers, before anything is written into the output, the one question about a splice the planner
 * cannot: whether the repository being opened up actually has something in the way where another is
 * placed inside it.
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
    /** Whether an input may land on a gitlink instead of colliding with it — see [TreeAssembler]. */
    private val dissolveSubmodules: Boolean = false,
    /** How a refusal tells the user to give an input another subdirectory. */
    private val relocation: Relocation = Relocation.UNSPELLED,
) {

    /**
     * One report per splice the plan makes, or an error naming every splice that collides.
     *
     * Each input inside another is reported against the repository it lies directly inside, and
     * against any further one out only where the braid made that splice too — where the one
     * between them had no content yet.
     *
     * A collision is reported once per splice — at the first commit that shows it — because the
     * same one usually repeats over a long stretch of the braid, and one locatable example is what
     * a remedy needs.
     */
    fun check(): List<SpliceReport> {
        // Every splice once, and each chain as the indices of its own, innermost first.
        val splices = ArrayList<Splice>()
        val chains = chains().map { chain -> chain.map { splices += it; splices.lastIndex } }
        if (chains.isEmpty()) return emptyList()

        val commits = IntArray(splices.size)
        val dissolved = IntArray(splices.size)
        val checked = Array(splices.size) { HashMap<ObjectId, Verdict>() }
        val collisions = arrayOfNulls<String>(splices.size)

        for (planned in plan.commits) {
            val content = plan.contentOf(planned.commit)
            for (chain in chains) {
                if (splices[chain.first()].inner !in content) continue
                // The innermost container with content here is what the inner input lands in: the
                // writer descends through a container that has none into the next one out.
                val i = chain.firstOrNull { splices[it].outer in content } ?: continue
                val splice = splices[i]
                val holder = content.getValue(splice.outer)
                commits[i]++
                if (collisions[i] != null) continue
                val tree = treeOf(holder)
                val verdict = checked[i].getOrPut(tree) { verdictAt(splice, tree) }
                if (verdict.dissolve) dissolved[i]++
                verdict.collision?.let { collisions[i] = "$it at ${planned.commit}" }
            }
        }

        val found = collisions.filterNotNull()
        require(found.isEmpty()) {
            val placed = splices.indices.filter { collisions[it] != null }.map { splices[it].innerSubdir }.distinct()
            "one repository cannot be placed inside another where it is:\n" +
                found.joinToString("\n") { "  - $it" } +
                placed.joinToString("") { "\n  " + relocation.remedy(it) }
        }
        return chains.flatMap { chain ->
            chain.filterIndexed { depth, i -> depth == 0 || commits[i] > 0 }
                .map { i -> SpliceReport(splices[i], commits[i], checked[i].size, dissolved[i]) }
        }
    }

    /** What the walk down one splice found: nothing in the way, a dissolve, or a collision. */
    private class Verdict(val collision: String?, val dissolve: Boolean) {
        companion object {
            val FREE = Verdict(null, dissolve = false)
            val DISSOLVE = Verdict(null, dissolve = true)
        }
    }

    /**
     * What walking [splice] down [tree] — the containing repository's root tree at one commit —
     * finds at the inner repository's destination.
     *
     * A segment the containing repository does not have at all stops the walk with no collision:
     * the braid builds the tree there itself. A segment above the destination has to be a directory,
     * because that is what the braid descends into; the destination itself has to be free, because
     * what is written there is the inner repository's own tree object and nothing fits beside it.
     *
     * The one entry that may be at the destination is a **gitlink**, on a run that asked to dissolve
     * it: the containing repository was already saying another repository belongs at that exact
     * path, and the input landing there is that repository. A gitlink at a segment *above* the
     * destination is not that case and stays an error — nothing is placed at that path, so there is
     * no content to put in the submodule's stead.
     */
    private fun verdictAt(splice: Splice, tree: ObjectId): Verdict {
        val repo = repoOf.getValue(splice.outer)
        var current = tree
        var path = splice.outerSubdir ?: ""
        for ((depth, segment) in splice.below.withIndex()) {
            val here = if (path.isEmpty()) segment else "$path/$segment"
            val entry = repo.entriesOf(current).firstOrNull { it.name == segment }
                ?: return Verdict.FREE
            if (depth == splice.below.size - 1) {
                if (entry.mode == FileMode.GITLINK) {
                    if (dissolveSubmodules) return Verdict.DISSOLVE
                    return Verdict(
                        "'$here' is a submodule of ${repo.name}, so --dissolve-submodules " +
                            "would replace it with that repository's own content",
                        dissolve = false,
                    )
                }
                return Verdict(
                    "subdirectory '$here' collides with an entry of the same name in " + repo.name,
                    dissolve = false,
                )
            }
            if (entry.mode != FileMode.TREE) {
                return Verdict(
                    "'$here' is not a directory in ${repo.name}, so no repository can be " +
                        "placed inside it",
                    dissolve = false,
                )
            }
            current = entry.id
            path = here
        }
        return Verdict.FREE
    }

    /** The original root tree of [commit], which is what its content is in the output. */
    private fun treeOf(commit: Commit): ObjectId =
        inputs.commits[commit]?.tree ?: error("$commit is not a commit of these inputs")

    /**
     * For every input that lies inside another, one [Splice] per input it lies inside, innermost
     * first — the longest destination that is a proper prefix of its own leads, and the repository
     * at the output root, the shortest of them, comes last.
     */
    private fun chains(): List<List<Splice>> {
        val subdirs = plan.graph.sources.associateWith { plan.subdirOf(it) }
        val chains = ArrayList<List<Splice>>()
        for ((inner, innerSubdir) in subdirs) {
            if (innerSubdir == null) continue
            val chain = subdirs.entries
                .filter { (source, subdir) ->
                    source !== inner && (subdir == null || innerSubdir.startsWith("$subdir/"))
                }
                .sortedByDescending { it.value?.length ?: -1 }
                .map { (outer, outerSubdir) -> Splice(outer, inner, outerSubdir, innerSubdir) }
            if (chain.isNotEmpty()) chains += chain
        }
        return chains
    }
}
