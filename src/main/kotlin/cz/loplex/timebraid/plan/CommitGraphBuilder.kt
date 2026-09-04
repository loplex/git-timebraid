package cz.loplex.timebraid.plan

/**
 * Assigns dense indices to `(repository, commit id)` pairs and assembles a [CommitGraph].
 *
 * Commits may be added in any order, and a commit may name parents that have not been added yet —
 * a repository log is usually walked newest first, so forward references are the normal case, not
 * the exception. A parent named by a commit that is never added is an error reported by [build]:
 * that is exactly the shape a shallow or partial clone has, and the tool needs the whole graph.
 *
 * Parent references are resolved **within the same repository**. Original parent edges never cross
 * repository boundaries — the inputs are independent histories, and the edges that do cross are
 * precisely the ones the braid adds later. Two repositories may therefore contain the same commit
 * id without interfering; they become two distinct commits.
 */
class CommitGraphBuilder {

    private val sourceNames = ArrayList<String>()
    private val indexBySource = ArrayList<HashMap<String, Int>>()

    private val ids = ArrayList<String>()
    private val sourceOf = ArrayList<Int>()
    private val orderingTime = ArrayList<Long>()
    private val parents = ArrayList<IntArray?>()

    /** Number of commit indices handed out so far, including placeholders for unresolved parents. */
    val size: Int get() = ids.size

    /** Registers an input repository and returns its source index. */
    fun addSource(name: String): Int {
        sourceNames.add(name)
        indexBySource.add(HashMap())
        return sourceNames.size - 1
    }

    /**
     * Adds a commit and returns its dense index. [parentIds] are commit ids within the same
     * repository, first parent first; duplicates are dropped, keeping the first occurrence.
     */
    fun addCommit(
        source: Int,
        id: String,
        orderingTime: Long,
        parentIds: List<String> = emptyList(),
    ): Int {
        require(source in sourceNames.indices) { "unknown source index $source" }
        val commit = intern(source, id)
        check(parents[commit] == null) {
            "commit ${sourceNames[source]}/$id added twice"
        }
        val resolved = IntArray(parentIds.size)
        var count = 0
        for (parentId in parentIds) {
            require(parentId != id) { "commit ${sourceNames[source]}/$id is its own parent" }
            val parent = intern(source, parentId)
            if ((0 until count).none { resolved[it] == parent }) resolved[count++] = parent
        }
        parents[commit] = if (count == resolved.size) resolved else resolved.copyOf(count)
        this.orderingTime[commit] = orderingTime
        return commit
    }

    /** Dense index of an already known commit, or [CommitGraph.NO_COMMIT] if it was never seen. */
    fun indexOf(source: Int, id: String): Int {
        require(source in sourceNames.indices) { "unknown source index $source" }
        return indexBySource[source][id] ?: CommitGraph.NO_COMMIT
    }

    /**
     * Builds the graph.
     *
     * @throws IllegalStateException if any commit was referenced as a parent but never added.
     */
    fun build(): CommitGraph {
        val missing = (0 until size).filter { parents[it] == null }
        check(missing.isEmpty()) {
            val listed = missing.take(10).joinToString(", ") { "${sourceNames[sourceOf[it]]}/${ids[it]}" }
            val more = if (missing.size > 10) ", ... (${missing.size} in total)" else ""
            "referenced as a parent but never added: $listed$more" +
                " — the input history is incomplete (a shallow or partial clone?)"
        }
        return CommitGraph(
            parents = Array(size) { parents[it]!! },
            sourceIndex = IntArray(size) { sourceOf[it] },
            orderingTime = LongArray(size) { orderingTime[it] },
            commitIds = Array(size) { ids[it] },
            sourceNames = ArrayList(sourceNames),
        )
    }

    private fun intern(source: Int, id: String): Int =
        indexBySource[source].getOrPut(id) {
            ids.add(id)
            sourceOf.add(source)
            orderingTime.add(0L)
            parents.add(null)
            ids.size - 1
        }
}
