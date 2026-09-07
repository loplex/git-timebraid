package cz.loplex.timebraid.plan

/**
 * One input repository of the graph being assembled — what a [Source] is once it exists.
 *
 * Handed out by [CommitGraphBuilder.addSource] and accepted back by the calls that name a
 * repository, so the two repositories a caller is holding cannot be mixed up with each other or
 * with anything else.
 */
internal class Strand internal constructor(val name: String, internal val index: Int) {
    internal val byId = HashMap<String, Node>()
    override fun toString(): String = name
}

/**
 * One commit of the graph being assembled — what a [Commit] is once it exists.
 *
 * A node comes into being the moment anything names that commit, which is usually as somebody's
 * parent: a repository log is walked newest first, so a commit is mentioned before it is reached.
 * It carries its own data only once [CommitGraphBuilder.addCommit] has been given it, and [parents]
 * being null is exactly that gap — the one [CommitGraphBuilder.build] reports as an incomplete
 * history.
 *
 * There is one instance per `(repository, commit id)` pair, so identity answers "the same commit",
 * and the same id in two repositories is two nodes.
 */
internal class Node internal constructor(val strand: Strand, val id: String) {

    /** Parents, first parent first. Null until the commit itself has been added. */
    internal var parents: List<Node>? = null

    internal var time: Long = 0

    override fun toString(): String = "${strand.name}/$id"
}

/** A finished [CommitGraph] and the commits the nodes that built it became. */
internal class BuiltGraph internal constructor(
    val graph: CommitGraph,
    private val commits: Map<Node, Commit>,
) {
    /** The commit [node] became. */
    fun commitOf(node: Node): Commit =
        commits[node] ?: error("$node was not added to this graph")
}

/**
 * Assembles a [CommitGraph] from commits given in any order.
 *
 * A commit may name parents that have not been added yet — a repository log is usually walked
 * newest first, so forward references are the normal case, not the exception. Each one becomes a
 * [Node] straight away and is filled in when its own turn comes; one that never gets a turn is an
 * error reported by [build], which is exactly the shape a shallow or partial clone has, and the
 * tool needs the whole graph.
 *
 * Parent references are resolved **within the same repository**. Original parent edges never cross
 * repository boundaries — the inputs are independent histories, and the edges that do cross are
 * precisely the ones the braid adds later. Two repositories may therefore contain the same commit
 * id without interfering; they become two distinct commits.
 *
 * The dense numbering the graph is made of is assigned here, in [build], and only there: while the
 * graph is being described it is nodes all the way, because an index into a graph that does not
 * exist yet would be an index into nothing.
 */
internal class CommitGraphBuilder {

    private val strands = ArrayList<Strand>()

    /** Every node, in the order they were first named, which becomes the graph's dense order. */
    private val nodes = ArrayList<Node>()

    /** Number of commits named so far, including ones so far only mentioned as a parent. */
    val size: Int get() = nodes.size

    /** Registers an input repository. */
    fun addSource(name: String): Strand =
        Strand(name, strands.size).also { strands.add(it) }

    /**
     * Adds a commit. [parentIds] are commit ids within the same repository, first parent first;
     * duplicates are dropped, keeping the first occurrence.
     */
    fun addCommit(
        source: Strand,
        id: String,
        orderingTime: Long,
        parentIds: List<String> = emptyList(),
    ): Node {
        require(source.isFrom(this)) { "repository ${source.name} belongs to another builder" }
        val node = intern(source, id)
        check(node.parents == null) { "commit ${source.name}/$id added twice" }

        val parents = ArrayList<Node>(parentIds.size)
        for (parentId in parentIds) {
            require(parentId != id) { "commit ${source.name}/$id is its own parent" }
            val parent = intern(source, parentId)
            if (parent !in parents) parents.add(parent)
        }
        node.parents = parents
        node.time = orderingTime
        return node
    }

    /** The node of an already named commit, or null if nothing has named it. */
    fun find(source: Strand, id: String): Node? {
        require(source.isFrom(this)) { "repository ${source.name} belongs to another builder" }
        return source.byId[id]
    }

    /**
     * Builds the graph, numbering the nodes in the order they were first named.
     *
     * @throws IllegalStateException if any commit was referenced as a parent but never added.
     */
    fun build(): BuiltGraph {
        val missing = nodes.filter { it.parents == null }
        check(missing.isEmpty()) {
            val listed = missing.take(10).joinToString(", ")
            val more = if (missing.size > 10) ", ... (${missing.size} in total)" else ""
            "referenced as a parent but never added: $listed$more" +
                " — the input history is incomplete (a shallow or partial clone?)"
        }

        val at = HashMap<Node, Int>(nodes.size * 2)
        nodes.forEachIndexed { index, node -> at[node] = index }

        val graph = CommitGraph.of(
            parents = Array(nodes.size) { index ->
                val row = nodes[index].parents!!
                IntArray(row.size) { at.getValue(row[it]) }
            },
            sourceIndex = IntArray(nodes.size) { nodes[it].strand.index },
            orderingTime = LongArray(nodes.size) { nodes[it].time },
            commitIds = Array(nodes.size) { nodes[it].id },
            sourceNames = strands.map { it.name },
        )

        return BuiltGraph(graph, nodes.withIndex().associate { (index, node) -> node to graph.commits[index] })
    }

    private fun intern(source: Strand, id: String): Node =
        source.byId.getOrPut(id) { Node(source, id).also { nodes.add(it) } }

    private fun Strand.isFrom(builder: CommitGraphBuilder): Boolean =
        index in builder.strands.indices && builder.strands[index] === this
}
