package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.CommitGraph
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.PlannedCommit
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId

/** Everything about the output that is a matter of taste rather than of correctness. */
class WriteOptions(
    /** Prepended to every commit subject. `{repo}` and `{subdir}` are substituted. */
    val subjectPrefix: String = "{subdir}: ",
    /** Prepended to every tag name. `{repo}` is substituted. */
    val tagPrefix: String = "{repo}/",
    /** Whether to record the original identity of each commit in a trailer. */
    val provenance: Boolean = true,
)

/** What a run produced, for the closing report. */
class WriteSummary(
    val commits: Int,
    val trees: Int,
    val contentObjects: Int,
    val branches: Int,
    val tags: Int,
    val head: String,
)

/**
 * Turns a [MergePlan] into a real repository.
 *
 * The order of the three passes is forced by git itself. Content objects first, because the commits
 * about to be written point at them. Then the commits, in the plan's write order, which guarantees
 * that a parent already has a new identity by the time its child needs it — the planner works in
 * indices precisely because it cannot know a sha that does not exist yet. Refs last, after the
 * objects have been flushed, because a ref pointing at an object no reader can see is a broken
 * repository.
 */
class BraidWriter(
    private val target: TargetRepository,
    private val sources: List<SourceRepository>,
    private val inputs: BraidInputs,
    private val plan: MergePlan,
    private val options: WriteOptions = WriteOptions(),
) {

    private val graph = inputs.graph

    /** New identity of every original commit, filled in write order. */
    private val written = arrayOfNulls<ObjectId>(graph.size)

    /** Top-level entries of the root repository's trees, which repeat across the whole braid. */
    private val rootEntries = HashMap<ObjectId, List<TreeEntry>>()

    init {
        require(sources.size == graph.sourceCount) {
            "got ${sources.size} repositories for ${graph.sourceCount} strands"
        }
        require(sources.map { it.name } == graph.sourceNames) {
            "the repositories and the graph disagree about the strands"
        }
    }

    fun write(): WriteSummary {
        val contentObjects = importContent()
        writeCommits()
        val refs = resolveRefs()

        // Annotated tags are objects too, and a ref pointing at an object no reader can see is
        // rejected, so nothing may be published before everything is flushed.
        target.flushObjects()
        for ((name, id) in refs.targets) target.point(name, id)
        target.setHead(inputs.mainlineBranch)

        return WriteSummary(
            commits = plan.commits.size,
            trees = target.trees.treesWritten,
            contentObjects = contentObjects,
            branches = refs.branches,
            tags = refs.tags,
            head = inputs.mainlineBranch,
        )
    }

    private fun importContent(): Int {
        var objects = 0
        for ((index, source) in sources.withIndex()) {
            objects += target.importContentObjects(source, inputs.sources[index].tips)
        }
        return objects
    }

    private fun writeCommits() {
        for (planned in plan.commits) {
            val commit = planned.commit.index
            val original = inputs.commits[commit]
            val parents = planned.parents.map { parent ->
                written[parent]
                    ?: error(
                        "${graph.describe(commit)} is written before its parent " +
                            "${graph.describe(parent)} — the plan's order is not a write order"
                    )
            }
            written[commit] = target.writeCommit(
                tree = treeOf(commit),
                parents = parents,
                author = original.author,
                committer = original.committer,
                message = messageOf(planned, original),
            )
        }
    }

    /**
     * The root tree of [commit]: every repository that already has content, each at whatever the
     * plan says it last committed. [MergePlan.contentOf] has done the accumulating; all that is left
     * here is to turn commit indices into the trees those commits carried.
     */
    private fun treeOf(commit: Int): ObjectId {
        val content = plan.contentOf(commit)
        val subdirEntries = ArrayList<TreeEntry>(content.size)
        var root: List<TreeEntry> = emptyList()

        for (source in content.indices) {
            val holder = content[source]
            if (holder == CommitGraph.NO_COMMIT) continue
            val tree = inputs.commits[holder].tree
            val subdir = plan.subdirs[source]
            if (subdir == null) {
                root = rootEntries.getOrPut(tree) { sources[source].topLevelEntries(tree) }
            } else {
                subdirEntries += TreeEntry(subdir, FileMode.TREE, tree)
            }
        }

        return target.trees.assemble(root, subdirEntries) { graph.describe(commit) }
    }

    /**
     * The subject prefix, the original message, and the provenance trailer.
     *
     * The trailer is what makes the README's promise checkable instead of merely claimed: it records
     * the original sha and the original parent shas, so a script can walk the output and verify that
     * every edge of every input still exists. Trailing newlines are normalised so the trailer always
     * ends up as its own paragraph, which is where git's trailer parsing expects it.
     */
    private fun messageOf(planned: PlannedCommit, original: SourceCommit): String {
        val repo = graph.sourceNameOf(planned.commit.index)
        val subdir = planned.subdir ?: repo
        val prefixed = options.subjectPrefix
            .replace("{repo}", repo)
            .replace("{subdir}", subdir) + original.message

        if (!options.provenance) return prefixed

        val trailer = "[timebraid: repo=\"$repo\" commit=${original.id.name}" +
            " parents=${original.parents.joinToString(",") { it.name }}]\n"
        val body = prefixed.trimEnd('\n')
        return if (body.isEmpty()) trailer else "$body\n\n$trailer"
    }

    private class Refs(val targets: Map<String, ObjectId>, val branches: Int, val tags: Int)

    /**
     * Works out the complete set of refs the output should have, writing an object for every
     * annotated tag on the way. Nothing is published here — see [write] for why.
     *
     * The mainline collapses: every input contributed its mainline to one braid, so the output gets
     * one branch of that name, at the braid's tip. Every other branch keeps its own name where that
     * name belongs to a single repository, and is qualified with the repository name where two
     * inputs happen to have used it. Tags are always qualified, because release names collide across
     * repositories as a matter of course rather than by accident.
     */
    private fun resolveRefs(): Refs {
        val refs = LinkedHashMap<String, ObjectId>()

        val braidTip = plan.braid.lastOrNull()
            ?: error("the braid is empty — there is nothing to point a branch at")
        refs[Constants.R_HEADS + inputs.mainlineBranch] = idOf(braidTip)
        var branches = 1

        val shared = HashMap<String, Int>()
        for (source in inputs.sources) {
            for (branch in source.branches) {
                if (branch.name == inputs.mainlineBranch) continue
                shared.merge(branch.name, 1) { a, b -> a + b }
            }
        }

        var tags = 0
        for (source in inputs.sources) {
            for (branch in source.branches) {
                if (branch.name == inputs.mainlineBranch) continue
                val name = if (shared[branch.name] == 1) branch.name else "${source.name}/${branch.name}"
                refs[Constants.R_HEADS + name] = idOf(branch.commit)
                branches++
            }
            for (tag in source.tags) {
                val name = options.tagPrefix.replace("{repo}", source.name) + tag.name
                refs[Constants.R_TAGS + name] = tagTarget(name, tag)
                tags++
            }
        }

        checkRefNames(refs.keys)
        return Refs(refs, branches, tags)
    }

    /**
     * An annotated tag stays annotated: its tagger and message are the only thing about a tag that a
     * person actually wrote, and dropping them would lose text no other object holds. The signature,
     * if there was one, is not carried over — it covers the commit the tag pointed at, which no
     * longer exists under that name.
     */
    private fun tagTarget(name: String, tag: BraidTag): ObjectId {
        val commit = idOf(tag.commit)
        val annotation = tag.annotation ?: return commit
        return target.writeAnnotatedTag(
            name = name,
            target = commit,
            tagger = annotation.tagger,
            message = stripSignature(annotation.message),
        )
    }

    private fun idOf(commit: Int): ObjectId =
        written[commit] ?: error("${graph.describe(commit)} was never written")

    companion object {

        private const val PGP_HEADER = "-----BEGIN PGP SIGNATURE-----"

        /** Drops a trailing PGP signature block from a tag message. */
        fun stripSignature(message: String): String {
            val start = message.indexOf(PGP_HEADER)
            if (start < 0) return message
            if (start != 0 && message[start - 1] != '\n') return message
            return message.substring(0, start)
        }

        /**
         * Refs are paths, so `refs/heads/a` and `refs/heads/a/b` cannot both exist — git would have
         * to store a file and a directory under the same name. Detecting that here, before anything
         * is written, turns a half-populated repository and an opaque lock error into one message
         * naming both refs.
         */
        fun checkRefNames(names: Collection<String>) {
            val all = names.toSet()
            for (name in names) {
                var slash = name.indexOf('/')
                while (slash >= 0) {
                    val prefix = name.substring(0, slash)
                    require(prefix !in all) {
                        "'$prefix' and '$name' cannot both be refs in one repository; " +
                            "rename one of them or narrow the run with -b"
                    }
                    slash = name.indexOf('/', slash + 1)
                }
            }
        }
    }
}
