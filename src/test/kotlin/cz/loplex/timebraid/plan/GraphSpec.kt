package cz.loplex.timebraid.plan

/**
 * A [CommitGraph] written as one line, so that a fixture reads like the history it describes.
 *
 * ```
 * A: a1@10 <- a2@30 <- a3@50 | B: b1@20 <- b2@40
 * ```
 *
 * - `|` separates input repositories, `<name>:` names one.
 * - `<-` chains commits: the left one is the first parent of the right one.
 * - `;` starts another chain inside the same repository, for side branches.
 * - `id(p1,p2)` states the parents explicitly instead of taking them from the chain; the first one
 *   listed is the first parent. This is how merges are written.
 * - `@n` is the ordering timestamp.
 *
 * Commit names are unique across the whole fixture, so a test can refer to a commit by name alone.
 */
class GraphSpec internal constructor(
    val graph: CommitGraph,
    private val byName: Map<String, Commit>,
) {

    /** The commit named [name]. */
    fun commit(name: String): Commit =
        byName[name] ?: error("no commit named '$name' in this fixture")

    /** The named commits, in the order given — heads, tips, and anything else the API takes. */
    fun commits(vararg names: String): List<Commit> = names.map { commit(it) }

    /** Names of the given commits, in the order given — the readable form of an order or a braid. */
    fun names(commits: List<Commit>): List<String> = commits.map { it.id }

    /** One subdirectory per repository, named after the repository. */
    fun subdirs(): Map<Source, String?> = graph.sources.associateWith { it.name }

    /** The given subdirectories, one per repository in the order the fixture declares them. */
    fun subdirs(vararg values: String?): Map<Source, String?> =
        graph.sources.take(values.size).withIndex().associate { (i, source) -> source to values[i] }

    companion object {

        private val COMMIT = Regex("""^([A-Za-z0-9_.\-]+)(?:\(([^)]*)\))?@(-?\d+)$""")

        fun parse(spec: String): GraphSpec {
            val builder = CommitGraphBuilder()
            val byName = LinkedHashMap<String, Commit>()

            for (strand in spec.split('|')) {
                val parts = strand.split(':', limit = 2)
                require(parts.size == 2) { "strand '$strand' has no '<name>:' prefix" }
                val source = builder.addSource(parts[0].trim())

                for (chain in parts[1].split(';')) {
                    var previous: String? = null
                    for (token in chain.split("<-")) {
                        val text = token.trim()
                        if (text.isEmpty()) continue
                        val match = COMMIT.matchEntire(text) ?: error("cannot parse commit '$text'")
                        val (name, parentList, time) = match.destructured
                        val parents = when {
                            parentList.isEmpty() -> listOfNotNull(previous)
                            else -> parentList.split(',').map { it.trim() }
                        }
                        val commit = builder.addCommit(source, name, time.toLong(), parents)
                        require(byName.put(name, commit) == null) {
                            "commit name '$name' is used twice"
                        }
                        previous = name
                    }
                }
            }
            return GraphSpec(builder.build(), byName)
        }
    }
}
