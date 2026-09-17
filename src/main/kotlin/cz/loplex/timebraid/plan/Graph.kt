package cz.loplex.timebraid.plan

/**
 * A DAG one of this package's walks can be run over: the nodes to walk, and the edge from each node
 * to each of its parents.
 *
 * This is what it takes to *describe* a graph, which is all a caller with an ad-hoc set of nodes can
 * offer. Walking one without hashing takes a numbering as well — see [IndexedGraph] and [numbered].
 */
internal interface Graph<T : Any> {

    /** Every node to be walked. A walk's ties break on the order they are given in. */
    val nodes: List<T>

    /** Parents of [node], first parent first. Every parent has to be among [nodes]. */
    fun parentsOf(node: T): List<T>
}

/**
 * A graph that numbers its own nodes, so a walk indexes an array where it would otherwise hash a key.
 *
 * [indexOf] is dense in `0 until indexSpace`, stable for the life of the graph, and [nodes] come in
 * increasing index order — which is what lets an [Adjacency] be filled in a single pass.
 *
 * A graph that is *part* of a larger one keeps the larger one's numbering and reports its
 * [indexSpace]. A walk then sizes its arrays to the whole and visits only [nodes]: a few sparse
 * arrays instead of renumbering the part, and one invariant instead of two numberings to keep apart.
 */
internal interface IndexedGraph<T : Any> : Graph<T> {

    /** Exclusive upper bound on [indexOf]. Equal to `nodes.size` unless this graph is a part. */
    val indexSpace: Int

    /**
     * Where [node] sits. Whether a node of another graph is refused here or further on is the
     * implementation's to say.
     */
    fun indexOf(node: T): Int
}

/** A graph described by its nodes and their parents, for a caller with no numbering to offer. */
internal fun <T : Any> graphOf(given: List<T>, edges: (T) -> List<T>): Graph<T> =
    object : Graph<T> {
        override val nodes: List<T> get() = given
        override fun parentsOf(node: T): List<T> = edges(node)
    }

/**
 * The same graph with a numbering derived by hashing its nodes.
 *
 * The fallback for a node type that carries no number of its own — a test's `Int`s, or any caller
 * from outside this package. Equality, not identity: the node type is the caller's and may well be a
 * value, and a boxed `Int` past the JVM's small-integer cache is a fresh object on every mention.
 */
internal fun <T : Any> Graph<T>.numbered(): IndexedGraph<T> = HashNumbered(this)

private class HashNumbered<T : Any>(private val graph: Graph<T>) : IndexedGraph<T> {

    private val indices = HashMap<T, Int>(graph.nodes.size * 2)

    init {
        graph.nodes.forEachIndexed { index, node -> indices[node] = index }
    }

    override val nodes: List<T> get() = graph.nodes
    override val indexSpace: Int get() = graph.nodes.size
    override fun parentsOf(node: T): List<T> = graph.parentsOf(node)

    override fun indexOf(node: T): Int =
        indices[node] ?: error("$node is not one of the nodes this graph was given")
}
