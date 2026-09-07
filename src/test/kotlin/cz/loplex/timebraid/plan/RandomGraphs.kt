package cz.loplex.timebraid.plan

import kotlin.random.Random

/** A generated corpus: the graph plus one mainline head per repository. */
class RandomGraph(val graph: CommitGraph, val heads: List<Commit>)

/**
 * Seeded generator of plausible input histories: several repositories, each a first-parent chain
 * with side branches forking off it and merging back.
 *
 * The `ancestryMonotoneTime` parameter of [generate] chooses between the two corpora the tests need.
 * With it set, every commit is strictly newer than all of its parents — the well-behaved case,
 * where ordering by time is already a valid topological order. With it clear, timestamps jitter
 * backwards across parent edges, the way a rebase or a skewed clock leaves them, and ancestry has
 * to override time.
 */
object RandomGraphs {

    fun generate(
        seed: Int,
        repositories: Int = 3,
        commitsPerRepository: Int = 70,
        ancestryMonotoneTime: Boolean = false,
    ): RandomGraph {
        val random = Random(seed)
        val builder = CommitGraphBuilder()
        val heads = ArrayList<Node>(repositories)
        val times = HashMap<Node, Long>()

        for (repository in 0 until repositories) {
            val name = ('A' + repository).toString()
            val source = builder.addSource(name)
            val ids = ArrayList<String>()
            var clock = random.nextLong(0, 100)
            var head: Node? = null

            for (position in 0 until commitsPerRepository) {
                val id = "$name$position"
                val parents = ArrayList<String>()
                if (position > 0) {
                    // Mostly extend the chain; occasionally fork off an older commit.
                    val firstParent =
                        if (random.nextInt(100) < 75) ids.size - 1
                        else random.nextInt(ids.size)
                    parents.add(ids[firstParent])
                    if (position > 2 && random.nextInt(100) < 15) {
                        val second = random.nextInt(ids.size)
                        if (ids[second] != parents[0]) parents.add(ids[second])
                    }
                }

                clock += random.nextLong(0, 11)
                val time = if (ancestryMonotoneTime) {
                    val newest = parents.maxOfOrNull { times.getValue(builder.find(source, it)!!) } ?: -1L
                    maxOf(clock, newest + 1) + random.nextLong(0, 5)
                } else {
                    clock + random.nextLong(-30, 31)
                }

                val node = builder.addCommit(source, id, time, parents)
                times[node] = time
                ids.add(id)
                head = node
            }
            heads += head ?: error("repository $name has no commits")
        }

        val built = builder.build()
        return RandomGraph(built.graph, heads.map { built.commitOf(it) })
    }
}
