package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import cz.loplex.timebraid.plan.PlannedCommit
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

/** Everything about the output that is a matter of taste rather than of correctness. */
class WriteOptions(
    /**
     * Prepended to every commit subject. `{repo}` and `{subdir}` are substituted.
     *
     * The default is the name and not the destination because a destination can be nested
     * arbitrarily deep and the subject line carries it on every commit, while a name is one segment
     * and is what identifies an input everywhere else.
     */
    val subjectPrefix: String = "{repo}: ",
    /** Prepended to every tag name. `{repo}` is substituted. */
    val tagPrefix: String = "{repo}/",
    /**
     * Prepended to a branch name two inputs both use. `{repo}` is substituted.
     *
     * A branch only one input has keeps its own name and never sees this, which is why it is a
     * qualifier rather than a prefix: it exists to tell two otherwise identical names apart. One
     * that does not keep `{repo}` apart from the name can fail to, and so can an unshared name that
     * equals a qualified one; two inputs meeting on a name are refused then, as they are for
     * [tagPrefix] — see [BraidWriter.resolveRefs].
     */
    val branchPrefix: String = "{repo}/",
    /** Whether to record the original identity of each commit in a trailer. */
    val provenance: Boolean = true,
    /**
     * The trailer [provenance] records. `{repo}`, `{commit}` and `{parents}` are substituted.
     *
     * The default is what makes the promise that every original edge survives checkable rather than
     * merely claimed, so a template that drops `{commit}` or `{parents}` gives that up — which is
     * the caller's to decide.
     */
    val provenanceTrailer: String = "[timebraid: repo=\"{repo}\" commit={commit} parents={parents}]",
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
    /** Whether an input may land on a gitlink of the repository around it — see [TreeAssembler]. */
    private val dissolveSubmodules: Boolean = false,
    /** How a refusal tells the user to give an input another subdirectory. */
    private val relocation: Relocation = Relocation.UNSPELLED,
    /**
     * Called with the running commit count as [writeCommits] goes, so a caller can narrate the one
     * phase long enough to look stalled. A count rather than a line per commit: what happens here
     * is the same thing tens of thousands of times, and the plan that decides it is already
     * available in full through `--plan-out`.
     */
    private val onCommitWritten: (Int) -> Unit = {},
    /**
     * Runs the stage that makes the braid visible — the pack flush and the refs pointed at it.
     * Handed over so a caller can say it is happening; it has nothing to count, so nothing here
     * could.
     */
    private val publishing: (() -> Unit) -> Unit = { it() },
) {

    private val graph = inputs.graph

    /** New identity of every original commit, filled in write order. */
    private val written = HashMap<Commit, ObjectId>(graph.size)

    private val trees = target.treeAssembler(dissolveSubmodules, relocation)

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
     * Per input, how to read one of its trees into entries — what a splice needs when another input
     * is placed inside this one, and nothing else asks for.
     *
     * Memoized per tree because the same trees are asked about over and over: a containing
     * repository's tree at a spliced path is read again at every braid position that repository
     * stood still for, and the trees above it repeat the same way.
     */
    private val entryReaders: Map<Source, (ObjectId) -> List<TreeEntry>> =
        graph.sources.associateWith { source ->
            val repo = repoOf.getValue(source)
            val cache = HashMap<ObjectId, List<TreeEntry>>()
            val read: (ObjectId) -> List<TreeEntry> =
                { tree -> cache.getOrPut(tree) { repo.entriesOf(tree) } }
            read
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

        // Wrapped, because this is where a large braid goes quiet: the flush writes the whole pack
        // in one call and the refs follow it one at a time, which is seconds with nothing to count.
        publishing {
            // Annotated tags are objects too, and a ref pointing at an object no reader can see is
            // rejected, so nothing may be published before everything is flushed.
            target.flushObjects()
            for ((name, id) in refs.targets) target.point(name, id)
            target.setHead(inputs.mainlineBranch)
        }

        return WriteSummary(
            commits = plan.commits.size,
            trees = trees.treesWritten,
            branches = refs.branches,
            tags = refs.tags,
            remoteRefs = refs.remoteRefs,
            head = inputs.mainlineBranch,
        )
    }

    private fun writeCommits() {
        var done = 0
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
            onCommitWritten(++done)
        }
    }

    /**
     * The root tree of [commit]: every repository that already has content, each at whatever the
     * plan says it last committed. [MergePlan.contentOf] has done the accumulating; all that is left
     * here is to turn those commits into the trees they carried.
     */
    private fun treeOf(commit: Commit): ObjectId {
        val content = plan.contentOf(commit)
        val placements = ArrayList<Placement>(content.size)
        val parts = ArrayList<RewiredGitmodules>(content.size)
        // Where the inputs' own content sits at this commit, which is what a dissolve makes untrue
        // of a `.gitmodules` section claiming the same path. Stays empty when nothing can dissolve.
        val occupied = HashSet<String>()
        val at = { commit.toString() }

        for ((source, holder) in content) {
            val tree = originalOf(holder).tree
            val subdir = plan.subdirOf(source)
            placements += Placement(subdir, tree, entryReaders.getValue(source))
            parts += wiringOf(source, tree, at)
            if (dissolveSubmodules && subdir != null) occupied += subdir
        }

        // Whether the wiring lost a section to a dissolve, which the assembler needs to know for the
        // case where it lost its last one: an absent `.gitmodules` then means the output has none on
        // purpose, and the root repository's own copy must not stand in for it.
        val dissolved = parts.any { part ->
            part.sections.any { it.path != null && it.path in occupied }
        }
        return trees.assemble(placements, gitmodulesOf(parts, occupied, at), dissolved, at)
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
     *
     * [occupied] is where the inputs' own content sits at this commit, so a section claiming one of
     * those paths for a submodule can be dropped. It is empty unless the run asked to dissolve, and
     * it is a question about *this* commit rather than about the run: an input that has no content
     * yet occupies nothing, and the gitlink standing in for it is still the truth there.
     */
    private fun gitmodulesOf(
        parts: List<RewiredGitmodules>,
        occupied: Set<String>,
        at: () -> String,
    ): ObjectId? {
        val text = SubmoduleWiring.merge(parts, occupied, relocation, at) ?: return null
        return gitmodulesBlobs.getOrPut(text) { target.writeBlob(text) }
    }

    /**
     * The subject prefix, the original message, and the provenance trailer.
     *
     * The trailer is what makes the promise that every original edge survives checkable instead of
     * merely claimed: it records the original sha and the original parent shas, so a script can
     * walk the output and verify that every edge of every input still exists. Trailing newlines are
     * normalised so the trailer always ends up as its own paragraph, which is where git's trailer
     * parsing expects it.
     */
    private fun messageOf(planned: PlannedCommit, original: SourceCommit): String {
        val repo = planned.commit.source.name
        val subdir = planned.subdir ?: repo
        val prefixed = options.subjectPrefix
            .replace("{repo}", repo)
            .replace("{subdir}", subdir) + original.message

        if (!options.provenance) return prefixed

        val trailer = options.provenanceTrailer
            .replace("{repo}", repo)
            .replace("{commit}", original.id.name)
            .replace("{parents}", original.parents.joinToString(",") { it.name }) + "\n"
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
     * release names collide across repositories as a matter of course rather than by accident. A
     * prefix that does not keep `{repo}` apart from the name, either of the two, lets two inputs'
     * refs meet on one name, as does a branch one input alone has under the name another input's
     * shared branch is qualified to, and a shared branch qualified onto the braid's own. That is
     * refused rather than resolved: the two can point at different commits, so keeping either would
     * publish one history under a name the other's reader would look up.
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
        val branchOwner = HashMap<String, String>()
        // The braid's own branch is in the running too: under `{repo}/`, input backend's shared
        // branch `x` is qualified onto a mainline called `backend/x`, and would write over it.
        branchOwner[inputs.mainlineBranch] = BRAID
        // Names written as they stand, for a branch only one input has: no template reaches them.
        val plain = HashSet<String>()
        val tagOwner = HashMap<String, String>()
        for (input in inputs.sources) {
            for (branch in input.branches) {
                if (branch.name == inputs.mainlineBranch) continue
                val name =
                    if (shared[branch.name] == 1) branch.name
                    else options.branchPrefix.replace("{repo}", input.source.name) + branch.name
                val alone = shared[branch.name] == 1
                branchOwner.put(name, input.source.name)?.let { first ->
                    throw IllegalArgumentException(
                        "'$first' and '${input.source.name}' would both write '${Constants.R_HEADS}$name'; " +
                            if (first == BRAID) {
                                "give --branch-prefix a template that moves the input's branches " +
                                    "off the braid's, or leave that branch out with -b"
                            } else if (alone || name in plain) {
                                // One side keeps its plain name whatever the template says, and
                                // `{repo}/` may be the very prefix that met it.
                                "a branch only one input has keeps its name, so leave one of them " +
                                    "out with -b"
                            } else {
                                "give --branch-prefix a template that keeps {repo} apart from the " +
                                    "name, as {repo}/ does, or narrow the run"
                            }
                    )
                }
                if (alone) plain += name
                refs[Constants.R_HEADS + name] = idOf(branch.commit)
                branches++
            }
            for (tag in input.tags) {
                val name = options.tagPrefix.replace("{repo}", input.source.name) + tag.name
                tagOwner.put(name, input.source.name)?.let { first ->
                    throw IllegalArgumentException(
                        "'$first' and '${input.source.name}' would both write '${Constants.R_TAGS}$name'; " +
                            "give --tag-prefix a template that keeps {repo} apart from the name, " +
                            "as {repo}/ does"
                    )
                }
                refs[Constants.R_TAGS + name] = tagTarget(name, tag)
                tags++
            }
        }

        val remoteRefs = if (mirrorRemotes) mirrorInputs(refs) else 0

        checkRefNames(refs.keys)
        return Refs(refs, branches, tags, remoteRefs)
    }

    /**
     * Adds a ref under `refs/remotes/<name>/` for every branch and tag the run carried over, and for
     * each input's mainline whether the selection took it or not, each pointing at that input's
     * *original* commit.
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
     * accepts: a branch the selection took that is literally named `tags/v1.0` still meets the tag
     * `v1.0` here, and that is refused, since either write winning would leave the other ref's
     * originals with no mirror.
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

        /** The holder `resolveRefs` records for the braid's own branch, and names when it is met. */
        private const val BRAID = "the braid"

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
