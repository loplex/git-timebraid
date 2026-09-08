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
        val heads = ArrayList<Commit>(repositories)

        for (repository in 0 until repositories) {
            val name = ('A' + repository).toString()
            val source = builder.addSource(name)
            val added = ArrayList<Commit>()
            var clock = random.nextLong(0, 100)
            var head: Commit? = null

            for (position in 0 until commitsPerRepository) {
                val id = "$name$position"
                val parents = ArrayList<Commit>()
                if (position > 0) {
                    // Mostly extend the chain; occasionally fork off an older commit.
                    val firstParent =
                        if (random.nextInt(100) < 75) added.size - 1
                        else random.nextInt(added.size)
                    parents.add(added[firstParent])
                    if (position > 2 && random.nextInt(100) < 15) {
                        val second = random.nextInt(added.size)
                        if (added[second] != parents[0]) parents.add(added[second])
                    }
                }

                clock += random.nextLong(0, 11)
                val time = if (ancestryMonotoneTime) {
                    val newest = parents.maxOfOrNull { it.time } ?: -1L
                    maxOf(clock, newest + 1) + random.nextLong(0, 5)
                } else {
                    clock + random.nextLong(-30, 31)
                }

                val commit = builder.addCommit(source, id, time, parents.map { it.id })
                added.add(commit)
                head = commit
            }
            heads += head ?: error("repository $name has no commits")
        }

        return RandomGraph(builder.build(), heads)
    }
}
