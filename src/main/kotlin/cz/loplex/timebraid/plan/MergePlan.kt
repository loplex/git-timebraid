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
    /** Parents after the braid edge was applied, first parent first; must not be modified. */
    val parents: IntArray,
    /** Whether this commit lies on the braid. */
    @Suppress("unused") // Part of the plan's description of a commit; nothing reads it yet.
    val onBraid: Boolean,
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
    /** The braid, in braid order. */
    val braid: IntArray,
    /** Write order: a topological order of the reparented history. */
    val order: IntArray,
    /** Subdirectory per input repository; exactly one may be `null`, meaning the repository root. */
    val subdirs: List<String?>,
    private val newParents: Array<IntArray>,
    private val content: Array<IntArray>,
    private val onBraid: BooleanArray,
) {

    /** Every commit to be written, in write order. */
    val commits: List<PlannedCommit> = order.map { commit ->
        PlannedCommit(
            commit = graph.commitAt(commit),
            subdir = subdirs[graph.sourceOf(commit)],
            parents = newParents[commit],
            onBraid = onBraid[commit],
        )
    }

    /** Parents of [commit] after reparenting. The returned array must not be modified. */
    fun parentsOf(commit: Int): IntArray = newParents[commit]

    /** Whether [commit] lies on the braid. */
    fun isOnBraid(commit: Int): Boolean = onBraid[commit]

    /** Subdirectory of the repository [commit] came from, `null` for the root repository. */
    fun subdirOf(commit: Int): String? = subdirs[graph.sourceOf(commit)]

    /**
     * The tree rule in symbolic form: for each input repository, the commit whose original tree is
     * that repository's content at [commit], or [CommitGraph.NO_COMMIT] if the repository has no
     * content there yet. Indexed by source index; the returned array must not be modified.
     *
     * The written tree of [commit] follows from this directly — one entry per repository that has
     * content, each pointing at that commit's original tree — and so does the promise the whole tool
     * is built on: whatever repository a commit came from, the other repositories are present at
     * whatever they had last committed at that point in the braid.
     */
    fun contentOf(commit: Int): IntArray = content[commit]

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
            appendLine("commits: ${graph.size} (braid: ${braid.size})")
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
            braid: IntArray,
            order: IntArray,
            newParents: Array<IntArray>,
            subdirs: List<String?>,
        ): MergePlan {
            validateSubdirs(graph, subdirs)

            val onBraid = BooleanArray(graph.size)
            for (commit in braid) onBraid[commit] = true

            return MergePlan(
                graph = graph,
                braid = braid,
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
