package cz.loplex.timebraid.plan

/**
 * One commit to be written, as the planner decided it.
 *
 * [parents] are the original commits, not shas: the planner has never seen a sha and cannot know the
 * identity of a commit that does not exist yet. Translating them into the identities of the commits
 * actually written is the writer's job, and it is always possible because the plan is in write order —
 * every parent has been written by the time its child comes up.
 */
class PlannedCommit internal constructor(
    /** The original commit being recreated. */
    val commit: Commit,
    /** Subdirectory this commit's repository occupies, or `null` for the repository placed at the root. */
    val subdir: String?,
    /** Parents after the braid edge was applied, first parent first. */
    val parents: List<Commit>,
)

/**
 * The complete, deterministic description of the output repository's history — and the seam between
 * the planner and everything that touches git. Everything above this line is arithmetic on indices;
 * everything below it is objects and refs.
 *
 * Reached through [CommitGraph.braid] and [Braid.plan], which run the algorithm in the order the two
 * decisions actually depend on each other: interleave the mainlines into the braid, apply the parent
 * rule so every temporal decision becomes a real parent edge, then take a write order of the braided
 * history and accumulate the content map along it.
 */
class MergePlan private constructor(
    val graph: CommitGraph,
    private val braidOrder: IntArray,
    private val order: IntArray,
    private val subdirs: List<String?>,
    private val newParents: Array<IntArray>,
    private val content: Array<IntArray>,
    private val onBraid: BooleanArray,
) {

    /** The braid, in braid order. */
    val braid: List<Commit> = graph.commitsAt(braidOrder)

    /** Every commit to be written, in write order. */
    val commits: List<PlannedCommit> = order.map { commit ->
        PlannedCommit(
            commit = graph.commitAt(commit),
            subdir = subdirs[graph.sourceOf(commit)],
            parents = graph.commitsAt(newParents[commit]),
        )
    }

    /** Parents of [commit] after reparenting, first parent first. */
    fun parentsOf(commit: Commit): List<Commit> = graph.commitsAt(newParents[commit.index])

    /** Whether [commit] lies on the braid. */
    fun isOnBraid(commit: Commit): Boolean = onBraid[commit.index]

    /** Subdirectory of the repository [commit] came from, `null` for the root repository. */
    fun subdirOf(commit: Commit): String? = subdirs[commit.source.index]

    /** Subdirectory [source] occupies in the output, `null` for the repository placed at the root. */
    fun subdirOf(source: Source): String? = subdirs[source.index]

    /**
     * The tree rule in symbolic form: for each input repository that has content at [commit], the
     * commit whose original tree is that content. A repository that has committed nothing by this
     * point in the braid is absent from the map rather than present as a blank.
     *
     * The written tree of [commit] follows from this directly — one entry per repository in the map,
     * each pointing at that commit's original tree — and so does the promise the whole tool is built
     * on: whatever repository a commit came from, the other repositories are present at whatever they
     * had last committed at that point in the braid.
     *
     * Iterates in the order the repositories were read, which is what keeps the assembled tree and
     * the merged `.gitmodules` a deterministic function of the inputs.
     */
    fun contentOf(commit: Commit): Map<Source, Commit> {
        val row = content[commit.index]
        val map = LinkedHashMap<Source, Commit>(graph.sourceCount)
        for (source in graph.sources) {
            val holder = row[source.index]
            if (holder != CommitGraph.NO_COMMIT) map[source] = graph.commitAt(holder)
        }
        return map
    }

    /** Counts, for `--dry-run`. */
    fun summary(): String {
        val histogram = HashMap<Int, Int>()
        for (commit in order) {
            histogram.merge(newParents[commit].size, 1) { a, b -> a + b }
        }
        val counts = histogram.keys.sorted().joinToString(", ") { "$it -> ${histogram[it]}" }
        return buildString {
            appendLine("repositories:")
            for (source in graph.sources) {
                appendLine("  ${source.name} -> ${subdirs[source.index]?.plus("/") ?: "<root>"}")
            }
            appendLine("commits: ${graph.size} (braid: ${braidOrder.size})")
            appendLine("parent counts: $counts")
        }
    }

    /**
     * The plan as text, one line per commit in write order. Deterministic, so two runs can simply be
     * diffed against each other when behaviour is expected to be unchanged.
     */
    fun render(): String = buildString {
        append(summary())
        appendLine()
        for ((position, commit) in order.withIndex()) {
            append(position.toString().padStart(6, '0'))
            append(if (onBraid[commit]) " * " else "   ")
            append(graph.describe(commit))
            append(" @").append(graph.timeOf(commit))
            append(" parents=[")
            append(newParents[commit].joinToString(", ") { graph.describe(it) })
            append("] content=[")
            append(
                (0 until graph.sourceCount)
                    .filter { content[commit][it] != CommitGraph.NO_COMMIT }
                    .joinToString(", ") { "${graph.sourceNames[it]}=${graph.idOf(content[commit][it])}" }
            )
            appendLine("]")
        }
    }

    internal companion object {

        /**
         * Assembles the plan from the decisions [Braid] and [ReparentedGraph] have already made.
         *
         * The braid needs no checking here: it can only have come from [CommitGraph.braid], which
         * names commits of this graph, each at most once.
         */
        fun create(
            graph: CommitGraph,
            braidOrder: IntArray,
            order: IntArray,
            newParents: Array<IntArray>,
            subdirs: List<String?>,
        ): MergePlan {
            validateSubdirs(graph, subdirs)

            val onBraid = BooleanArray(graph.size)
            for (commit in braidOrder) onBraid[commit] = true

            return MergePlan(
                graph = graph,
                braidOrder = braidOrder,
                order = order,
                subdirs = subdirs,
                newParents = newParents,
                content = accumulate(graph, order, newParents),
                onBraid = onBraid,
            )
        }

        /**
         * Walks the plan in write order carrying the content map forward: a commit inherits the map
         * of its first parent and replaces its own repository's entry with itself.
         *
         * This is where the accumulation the README describes actually happens. Because the first
         * parent of a braid commit is its predecessor in time, walking forward collects every
         * repository's latest state; because the first parent of an off-braid commit is its original
         * parent, a side branch keeps the other repositories frozen at the point it was cut.
         */
        private fun accumulate(
            graph: CommitGraph,
            order: IntArray,
            newParents: Array<IntArray>,
        ): Array<IntArray> {
            val content = arrayOfNulls<IntArray>(graph.size)
            for (commit in order) {
                val parents = newParents[commit]
                val inherited = if (parents.isEmpty()) {
                    IntArray(graph.sourceCount) { CommitGraph.NO_COMMIT }
                } else {
                    val firstParent = parents[0]
                    val parentContent = content[firstParent]
                        ?: error(
                            "commit ${graph.describe(commit)} is written before its first parent " +
                                "${graph.describe(firstParent)} — the write order is not topological"
                        )
                    parentContent.copyOf()
                }
                inherited[graph.sourceOf(commit)] = commit
                content[commit] = inherited
            }
            @Suppress("UNCHECKED_CAST")
            return content as Array<IntArray>
        }

        private fun validateSubdirs(graph: CommitGraph, subdirs: List<String?>) {
            require(subdirs.size == graph.sourceCount) {
                "got ${subdirs.size} subdirectories for ${graph.sourceCount} repositories"
            }
            require(subdirs.count { it == null } <= 1) {
                "at most one repository can be placed at the root"
            }
            val seen = HashSet<String>()
            for ((source, subdir) in subdirs.withIndex()) {
                if (subdir == null) continue
                require(subdir.isNotBlank() && !subdir.contains('/') && subdir != "." && subdir != "..") {
                    "'$subdir' is not a usable subdirectory name for ${graph.sourceNames[source]}"
                }
                require(seen.add(subdir)) {
                    "two repositories would be placed in the same subdirectory '$subdir'"
                }
            }
        }
    }
}
