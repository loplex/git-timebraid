package cz.loplex.timebraid.plan

/**
 * The commit graph as [CommitGraph] stores it: parallel arrays indexed by a dense commit number.
 *
 * A [Commit] is a handle onto one row of these, which is what keeps a history of 14 000 commits to a
 * handful of arrays rather than an object per edge. This is storage and nothing else: the passes do
 * not read it, because each is handed commits and derives its own numbering from them — see
 * [Numbering]. Nothing outside this package can name the type either.
 *
 * Immutable in use: the arrays are copied on the way in and nothing writes into them afterwards. That
 * matters because two public objects can share the same instance — a [Braid] and the [CommitGraph] it
 * came from do.
 */
internal class DenseGraph private constructor(
    val edges: Array<IntArray>,
    val source: IntArray,
    val time: LongArray,
    val ids: Array<String>,
    val sourceNames: List<String>,
) {
    val size: Int get() = edges.size

    val sourceCount: Int get() = sourceNames.size

    /** `<repository>/<id>`, for diagnostics. The same text [Commit.toString] gives. */
    fun describe(commit: Int): String = "${sourceNames[source[commit]]}/${ids[commit]}"

    companion object {
        /**
         * Copies the arrays in and checks the shape: matching lengths, every source index and every
         * parent index in range, no commit its own parent, no parent listed twice.
         *
         * Acyclicity is deliberately *not* checked. A graph read out of a repository is a DAG by
         * definition, and the one place where that can genuinely break is reparenting, which checks
         * explicitly — see [requireAcyclic].
         */
        fun of(
            parents: Array<IntArray>,
            sourceIndex: IntArray,
            orderingTime: LongArray,
            commitIds: Array<String>,
            sourceNames: List<String>,
        ): DenseGraph {
            val size = parents.size
            require(sourceIndex.size == size) {
                "sourceIndex has ${sourceIndex.size} entries, expected $size"
            }
            require(orderingTime.size == size) {
                "orderingTime has ${orderingTime.size} entries, expected $size"
            }
            require(commitIds.size == size) {
                "commitIds has ${commitIds.size} entries, expected $size"
            }

            val graph = DenseGraph(
                Array(size) { parents[it].copyOf() },
                sourceIndex.copyOf(),
                orderingTime.copyOf(),
                commitIds.copyOf(),
                sourceNames,
            )

            for (commit in 0 until size) {
                require(graph.source[commit] in sourceNames.indices) {
                    "commit #$commit has source index ${graph.source[commit]}, " +
                        "expected 0..${sourceNames.size - 1}"
                }
                val parentsOfCommit = graph.edges[commit]
                for (i in parentsOfCommit.indices) {
                    val parent = parentsOfCommit[i]
                    require(parent in 0 until size) {
                        "commit #$commit has parent index $parent, expected 0..${size - 1}"
                    }
                    require(parent != commit) { "commit ${graph.describe(commit)} is its own parent" }
                    for (j in 0 until i) {
                        require(parentsOfCommit[j] != parent) {
                            "commit ${graph.describe(commit)} lists parent " +
                                "${graph.describe(parent)} twice"
                        }
                    }
                }
            }
            return graph
        }
    }
}
