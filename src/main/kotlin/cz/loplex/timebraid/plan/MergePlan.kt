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
 * the planner and everything that touches git. Everything above this line is commits and the
 * decisions taken over them; everything below it is objects and refs.
 *
 * Reached through [CommitGraph.braid] and [Braid.plan], which run the algorithm in the order the two
 * decisions actually depend on each other: interleave the mainlines into the braid, apply the parent
 * rule so every temporal decision becomes a real parent edge, then take a write order of the braided
 * history and accumulate the content map along it.
 */
class MergePlan private constructor(
    val graph: CommitGraph,
    /** The braid, in braid order. */
    val braid: List<Commit>,
    private val order: List<Commit>,
    private val subdirs: Map<Source, String?>,
    private val newParents: ParentEdges,
    private val columns: Map<Source, Int>,
    /** One row per commit of the graph, addressed by the position the commit carries. */
    private val content: Array<Array<Commit?>?>,
    private val onBraid: BooleanArray,
) {

    /** Every commit to be written, in write order. */
    val commits: List<PlannedCommit> = order.map { commit ->
        PlannedCommit(
            commit = commit,
            subdir = subdirs.getValue(commit.source),
            parents = newParents[commit],
        )
    }

    /** Parents of [commit] after reparenting, first parent first. */
    fun parentsOf(commit: Commit): List<Commit> = newParents[commit]

    /** Whether [commit] lies on the braid. */
    fun isOnBraid(commit: Commit): Boolean = onBraid[positionOf(commit)]

    /** Subdirectory of the repository [commit] came from, `null` for the root repository. */
    fun subdirOf(commit: Commit): String? = subdirs.getValue(commit.source)

    /** Subdirectory [source] occupies in the output, `null` for the repository placed at the root. */
    fun subdirOf(source: Source): String? = subdirs.getValue(source)

    private fun rowOf(commit: Commit): Array<Commit?> = content[positionOf(commit)]!!

    /**
     * Where [commit] sits in the graph this plan was made from, which is also the check that it is
     * one of its commits — the commit at that position has to be this one.
     */
    private fun positionOf(commit: Commit): Int {
        val at = commit.position
        if (at !in content.indices || graph.commits[at] !== commit) {
            error("$commit is not a commit of this plan")
        }
        return at
    }

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
        val row = rowOf(commit)
        val map = LinkedHashMap<Source, Commit>(graph.sources.size)
        for (source in graph.sources) {
            row[columns.getValue(source)]?.let { map[source] = it }
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
                appendLine("  ${source.name} -> ${subdirs.getValue(source)?.plus("/") ?: "<root>"}")
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
            val row = rowOf(commit)
            append(position.toString().padStart(6, '0'))
            append(if (isOnBraid(commit)) " * " else "   ")
            append(commit)
            append(" @").append(commit.time)
            append(" parents=[")
            append(newParents[commit].joinToString(", "))
            append("] content=[")
            append(
                graph.sources
                    .mapNotNull { source -> row[columns.getValue(source)]?.let { "${source.name}=${it.id}" } }
                    .joinToString(", ")
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
            braid: List<Commit>,
            order: List<Commit>,
            newParents: ParentEdges,
            subdirs: Map<Source, String?>,
        ): MergePlan {
            validateSubdirs(graph, subdirs)

            // The content table is addressed by repository, so it needs a column per repository —
            // the one numbering this class derives, the way every pass of the package derives its
            // own. A repository of another graph is simply not in it.
            val columns = HashMap<Source, Int>(graph.sources.size * 2)
            graph.sources.forEachIndexed { column, source -> columns[source] = column }

            return MergePlan(
                graph = graph,
                braid = braid,
                order = order,
                subdirs = subdirs,
                newParents = newParents,
                columns = columns,
                content = accumulate(graph, order, newParents, columns),
                onBraid = BooleanArray(graph.size).also { for (commit in braid) it[commit.position] = true },
            )
        }

        /**
         * Walks the plan in write order carrying the content map forward: a commit inherits the map
         * of its first parent and replaces its own repository's entry with itself.
         *
         * This is where the accumulation doc/how-it-works.md describes actually happens. Because
         * the first parent of a braid commit is its predecessor in time, walking forward collects
         * every repository's latest state; because the first parent of an off-braid commit is its
         * original parent, a side branch keeps the other repositories frozen at the point it was cut.
         */
        private fun accumulate(
            graph: CommitGraph,
            order: List<Commit>,
            newParents: ParentEdges,
            columns: Map<Source, Int>,
        ): Array<Array<Commit?>?> {
            // One row per commit, one column per repository: this is the largest thing the plan
            // holds, and a map per commit over a corpus-sized history would cost far more than the
            // rows are worth. It stays behind contentOf, which hands out a map of what is there.
            val content = arrayOfNulls<Array<Commit?>>(graph.size)
            for (commit in order) {
                val parents = newParents[commit]
                val inherited = if (parents.isEmpty()) {
                    arrayOfNulls<Commit>(graph.sources.size)
                } else {
                    val firstParent = parents[0]
                    val parentContent = content[firstParent.position]
                        ?: error(
                            "commit $commit is written before its first parent $firstParent — " +
                                "the write order is not topological"
                        )
                    parentContent.copyOf()
                }
                inherited[columns.getValue(commit.source)] = commit
                content[commit.position] = inherited
            }
            return content
        }

        private fun validateSubdirs(graph: CommitGraph, subdirs: Map<Source, String?>) {
            for (source in graph.sources) {
                require(source in subdirs) { "no subdirectory given for repository ${source.name}" }
            }
            require(subdirs.size == graph.sources.size) {
                "got ${subdirs.size} subdirectories for ${graph.sources.size} repositories"
            }
            require(subdirs.values.count { it == null } <= 1) {
                "at most one repository can be placed at the root"
            }
            val seen = HashSet<String>()
            for ((source, subdir) in subdirs) {
                if (subdir == null) continue
                require(subdir.isNotBlank() && !subdir.contains('/') && subdir != "." && subdir != "..") {
                    "'$subdir' is not a usable subdirectory name for $source"
                }
                require(seen.add(subdir)) {
                    "two repositories would be placed in the same subdirectory '$subdir'"
                }
            }
        }
    }
}
