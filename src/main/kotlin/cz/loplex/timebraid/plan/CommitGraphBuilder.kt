package cz.loplex.timebraid.plan

/**
 * Assembles a [CommitGraph] from commits given in any order.
 *
 * A commit may name parents that have not been added yet — a repository log is usually walked
 * newest first, so forward references are the normal case, not the exception. Each one becomes a
 * [Commit] straight away and is filled in when its own turn comes; one that never gets a turn is an
 * error reported by [build], since the tool needs the whole graph. A shallow or partial clone is
 * not what produces one: JGit reads a shallow clone's boundary commits as roots, and
 * `SourceRepository.open` refuses both kinds of clone before anything is read.
 *
 * Parent references are resolved **within the same repository**. Original parent edges never cross
 * repository boundaries — the inputs are independent histories, and the edges that do cross are
 * precisely the ones the braid adds later. Two repositories may therefore contain the same commit
 * id without interfering; they become two distinct commits.
 *
 * What the builder hands out is what the graph is made of: [addSource] returns the [Source] and
 * [addCommit] the [Commit] that will be in the finished graph, so a caller that has to remember a
 * commit — a ref target, a mainline tip, the original behind it — remembers the commit itself and
 * has nothing left to look up once [build] has run.
 */
internal class CommitGraphBuilder {

    /** Every repository, in the order they were registered, each with its own commits by id. */
    private val sources = LinkedHashMap<Source, HashMap<String, Commit>>()

    /** Every commit, in the order they were first named, which becomes the graph's order. */
    private val commits = ArrayList<Commit>()

    /** Registers an input repository. */
    fun addSource(name: String): Source =
        Source(name).also { sources[it] = HashMap() }

    /**
     * Adds a commit. [parentIds] are commit ids within the same repository, first parent first;
     * duplicates are dropped, keeping the first occurrence.
     */
    fun addCommit(
        source: Source,
        id: String,
        orderingTime: Long,
        parentIds: List<String> = emptyList(),
    ): Commit {
        val byId = commitsOf(source)
        val commit = intern(byId, source, id)
        check(!commit.isAdded) { "commit $source/$id added twice" }

        val parents = ArrayList<Commit>(parentIds.size)
        for (parentId in parentIds) {
            require(parentId != id) { "commit $source/$id is its own parent" }
            val parent = intern(byId, source, parentId)
            if (parent !in parents) parents.add(parent)
        }
        commit.define(orderingTime, parents)
        return commit
    }

    /** The already named commit with this id, or null if nothing has named it. */
    fun find(source: Source, id: String): Commit? = commitsOf(source)[id]

    /**
     * Builds the graph.
     *
     * @throws IllegalStateException if any commit was referenced as a parent but never added.
     */
    fun build(): CommitGraph {
        val missing = commits.filter { !it.isAdded }
        check(missing.isEmpty()) {
            val listed = missing.take(10).joinToString(", ")
            val more = if (missing.size > 10) ", ... (${missing.size} in total)" else ""
            "referenced as a parent but never added: $listed$more" +
                " -- the input history is incomplete"
        }

        return CommitGraph(sources.keys.toList(), commits.toList())
    }

    private fun intern(byId: HashMap<String, Commit>, source: Source, id: String): Commit =
        byId.getOrPut(id) { Commit(source, id).also { commits.add(it) } }

    /**
     * The commits of [source], which is also the check that it is one of this builder's.
     *
     * A [Source] is looked up by identity, so one from another builder is refused rather than
     * quietly interning into a map that is not this graph's.
     */
    private fun commitsOf(source: Source): HashMap<String, Commit> {
        val byId = sources[source]
        require(byId != null) { "repository $source belongs to another builder" }
        return byId
    }
}
