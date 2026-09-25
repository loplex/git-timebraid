package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
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
    val branches: Int,
    val tags: Int,
    /** Remote-tracking refs written for the inputs, zero unless the inputs were kept as remotes. */
    val remoteRefs: Int,
    val head: String,
)

/**
 * Turns a [MergePlan] into a real repository.
 *
 * What the braid takes from the inputs is in [target] before this runs — [TargetRepository.fetchFrom]
 * put it there — so what is left is what the braid invents, and the order of the two passes is forced
 * by git itself. The commits first, in the plan's write order, which guarantees that a parent already
 * has a new identity by the time its child needs it: the planner works in indices precisely because
 * it cannot know a sha that does not exist yet. Refs last, after the objects have been flushed,
 * because a ref pointing at an object no reader can see is a broken repository.
 */
class BraidWriter(
    private val target: TargetRepository,
    /**
     * The open repository behind each strand, paired by whoever opened them, by position against
     * [BraidInputs.sources], which keeps the order the repositories were given to
     * [CommitGraphReader.read] in. Every lookup here is by [Source], so nothing in this class has
     * to know what order anything arrived in, or be trusted to get it right.
     */
    private val repoOf: Map<Source, SourceRepository>,
    private val inputs: BraidInputs,
    private val plan: MergePlan,
    private val options: WriteOptions = WriteOptions(),
    /** Whether to mirror the refs carried over and each input's mainline — see [mirrorInputs]. */
    private val mirrorRemotes: Boolean = false,
) {

    private val graph = inputs.graph

    /** New identity of every original commit, filled in write order. */
    private val written = HashMap<Commit, ObjectId>(graph.size)

    /** Top-level entries of the root repository's trees, which repeat across the whole braid. */
    private val rootEntries = HashMap<ObjectId, List<TreeEntry>>()

    /**
     * Per input, what the `.gitmodules` of each of its trees contributes to the output's, keyed by
     * that tree. [MergePlan.contentOf] names the commit each input *last* made at a point in the
     * braid, so the same tree is asked about at as many braid positions as the input stood still
     * for — without this the answer would be recomputed at every one of them.
     */
    private val wiring = graph.sources.associateWith { HashMap<ObjectId, RewiredGitmodules>() }

    /** Root `.gitmodules` blobs written so far, keyed by their text. */
    private val gitmodulesBlobs = HashMap<String, ObjectId>()

    init {
        val missing = graph.sources.filterNot { repoOf.containsKey(it) }
        require(missing.isEmpty()) { "no repository given for ${missing.joinToString()}" }
    }

    /**
     * The commit an input originally made, as the reader read it.
     *
     * Missing means the commit is not one of these inputs' — which can only happen if the plan and
     * the inputs were built from different graphs, and is worth saying rather than reading whatever
     * happens to be at hand.
     */
    private fun originalOf(commit: Commit): SourceCommit =
        inputs.commits[commit] ?: error("$commit is not a commit of these inputs")

    fun write(): WriteSummary {
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
            branches = refs.branches,
            tags = refs.tags,
            remoteRefs = refs.remoteRefs,
            head = inputs.mainlineBranch,
        )
    }

    private fun writeCommits() {
        for (planned in plan.commits) {
            val commit = planned.commit
            val original = originalOf(commit)
            val parents = planned.parents.map { parent ->
                written[parent]
                    ?: error(
                        "$commit is written before its parent $parent -- " +
                            "the plan's order is not a write order"
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
     * here is to turn those commits into the trees they carried.
     */
    private fun treeOf(commit: Commit): ObjectId {
        val content = plan.contentOf(commit)
        val subdirEntries = ArrayList<TreeEntry>(content.size)
        val parts = ArrayList<RewiredGitmodules>(content.size)
        var root: List<TreeEntry> = emptyList()
        val at = { commit.toString() }

        for ((source, holder) in content) {
            val tree = originalOf(holder).tree
            val subdir = plan.subdirOf(source)
            if (subdir == null) {
                root = rootEntries.getOrPut(tree) { repoOf.getValue(source).topLevelEntries(tree) }
            } else {
                subdirEntries += TreeEntry(subdir, FileMode.TREE, tree)
            }
            parts += wiringOf(source, tree, at)
        }

        return target.trees.assemble(root, subdirEntries, gitmodulesOf(parts, at), at)
    }

    /** What [source]'s `.gitmodules` at [tree] contributes, or [SubmoduleWiring.NOTHING]. */
    private fun wiringOf(source: Source, tree: ObjectId, at: () -> String): RewiredGitmodules =
        wiring.getValue(source).getOrPut(tree) {
            val repo = repoOf.getValue(source)
            val text = repo.gitmodules(tree) ?: return@getOrPut SubmoduleWiring.NOTHING
            SubmoduleWiring.rewire(text, plan.subdirOf(source), repo.name, at)
        }

    /**
     * The root `.gitmodules` blob for one commit, or `null` when no input describes a submodule
     * there. Identical files are written once: the wiring only changes when an input adds, moves or
     * drops a submodule, so one blob typically serves a long stretch of the braid.
     */
    private fun gitmodulesOf(parts: List<RewiredGitmodules>, at: () -> String): ObjectId? {
        val text = SubmoduleWiring.merge(parts, at) ?: return null
        return gitmodulesBlobs.getOrPut(text) { target.writeBlob(text) }
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
        val repo = planned.commit.source.name
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

    private class Refs(
        val targets: Map<String, ObjectId>,
        val branches: Int,
        val tags: Int,
        val remoteRefs: Int,
    )

    /**
     * Works out the complete set of refs the output should have, writing an object for every
     * annotated tag on the way. Nothing is published here — see [write] for why.
     *
     * The mainline collapses: every input contributed its mainline to one braid, so the output gets
     * one branch of that name, at the braid's tip. Every other branch keeps its own name where that
     * name belongs to a single repository, and is qualified with the repository name where two
     * inputs happen to have used it. Tags carry `--tag-prefix`, `{repo}/` by default, because
     * release names collide across repositories as a matter of course rather than by accident.
     */
    private fun resolveRefs(): Refs {
        val refs = LinkedHashMap<String, ObjectId>()

        val braidTip = plan.braid.lastOrNull()
            ?: error("the braid is empty -- there is nothing to point a branch at")
        refs[Constants.R_HEADS + inputs.mainlineBranch] = idOf(braidTip)
        var branches = 1

        val shared = HashMap<String, Int>()
        for (input in inputs.sources) {
            for (branch in input.branches) {
                if (branch.name == inputs.mainlineBranch) continue
                shared.merge(branch.name, 1) { a, b -> a + b }
            }
        }

        var tags = 0
        for (input in inputs.sources) {
            for (branch in input.branches) {
                if (branch.name == inputs.mainlineBranch) continue
                val name =
                    if (shared[branch.name] == 1) branch.name
                    else "${input.source.name}/${branch.name}"
                refs[Constants.R_HEADS + name] = idOf(branch.commit)
                branches++
            }
            for (tag in input.tags) {
                val name = options.tagPrefix.replace("{repo}", input.source.name) + tag.name
                refs[Constants.R_TAGS + name] = tagTarget(name, tag)
                tags++
            }
        }

        val remoteRefs = if (mirrorRemotes) mirrorInputs(refs) else 0

        checkRefNames(refs.keys)
        return Refs(refs, branches, tags, remoteRefs)
    }

    /**
     * Adds a ref under `refs/remotes/<name>/` for every branch `-b` took and every tag of every
     * input, and for each input's mainline whether `-b` took it or not, each pointing at that
     * input's *original* commit.
     *
     * Nothing is copied here, and nothing needs to be: the fetch that filled the output brought
     * across everything the refs that were read reach, commits included, with their shas intact —
     * that is what a fetch moves. All that was missing is a ref of the output's own that outlives
     * [TargetRepository.dropFetchRefs], and that is what this writes.
     *
     * Tags are covered as well as branches because a great many commits hang off them and nothing
     * else: on a three-repository history of 14 387 commits, a corpus outside this
     * tree, 929 of them were reachable in their input from a tag alone, and
     * mirroring only the branches left every one of those originals with no ref
     * pointing at it — present in the output, but unreachable, and pruned by the first `git gc`
     * once git's grace period for unreachable objects, two weeks by default, has passed. They go
     * under `tags/` so that the branch `v1.0` and the tag `v1.0` of one
     * input do not land on the same name. That keeps the usual pair apart, and not every pair git
     * accepts: a branch `-b` took that is literally named `tags/v1.0` still meets the tag `v1.0`
     * here, and that is refused, since either write winning would leave the other ref's originals
     * with no mirror.
     *
     * A ref here points at the commit a tag peels to rather than at the input's own tag object.
     * Nothing is lost by that: what an annotated tag holds beyond its target — its tagger, its
     * message — is recreated in full by [tagTarget] under the output's own prefixed tag name.
     *
     * The mirror covers the refs that were read, so `-b` narrows it the same way it narrows the
     * output, and for the same reason: the fetch was narrowed to those refs too, and a ref pointing
     * at an object that is not there is a broken repository. The mainline is among them whatever
     * `-b` says, since the braid is built along it: its commits are fetched and rewritten either
     * way, and without a mirror of its own a run that never selected it would leave its originals in
     * the output with nothing naming them.
     *
     * @return how many remote-tracking refs were added.
     */
    private fun mirrorInputs(refs: MutableMap<String, ObjectId>): Int {
        var added = 0
        for ((input, head) in inputs.sources.zip(inputs.heads)) {
            val prefix = Constants.R_REMOTES + input.source.name + "/"
            for (branch in input.branches) {
                refs[prefix + branch.name] = originalOf(branch.commit).id
                added++
            }
            for (tag in input.tags) {
                val name = prefix + "tags/" + tag.name
                // A branch literally named `tags/v1.0` mirrors to the name the tag `v1.0` does.
                require(name !in refs) {
                    "the branch 'tags/${tag.name}' and the tag '${tag.name}' of '${input.source.name}' " +
                        "would both be mirrored as '$name'; rename one of them in the input, leave " +
                        "the branch out with -b, or drop --keep-remotes"
                }
                refs[name] = originalOf(tag.commit).id
                added++
            }
            // Counted only where it is new: a selection that took the mainline wrote it above.
            val mainline = prefix + inputs.mainlineBranch
            if (refs.putIfAbsent(mainline, originalOf(head).id) == null) added++
        }
        return added
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

    private fun idOf(commit: Commit): ObjectId =
        written[commit] ?: error("$commit was never written")

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
         * to store a file and a directory under the same name. Detecting that here, before any of
         * the braid's refs is written, turns a lock error half way through them into one message
         * naming both refs. The output is not untouched: the fetch has run by then, so it holds the
         * history of every ref the run read, still parked under `refs/timebraid-fetch/`, which this
         * refusal leaves in place.
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
