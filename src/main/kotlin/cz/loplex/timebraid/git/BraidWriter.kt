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
    /**
     * Prepended to every commit subject. `{repo}` and `{subdir}` are substituted.
     *
     * The default is the name and not the destination because a destination can be nested
     * arbitrarily deep and the subject line carries it on every commit, while a name is one segment
     * and is what identifies an input everywhere else.
     */
    val subjectPrefix: String = "{repo}: ",
    /**
     * Prepended to every tag name. `{repo}` is substituted, and an empty value qualifies nothing.
     */
    val tagPrefix: String = "{repo}/",
    /**
     * Prepended to every branch name, as [tagPrefix] is to every tag. `{repo}` is substituted.
     *
     * It applies wherever a ref pattern has not spelled its destination out, rather than only where
     * two inputs used one name, which is what makes an output ref name a function of the input it
     * came from alone. The other rule made it a function of what the run selected as well: narrowing
     * one input could leave another as the only holder of a name and rename *its* branch, out of a
     * flag that says nothing about naming.
     *
     * An empty value is how a run asks for no qualification at all. Two inputs then meeting on one
     * name is refused rather than resolved, since either answer would be a guess — see
     * [BraidWriter.resolveRefs].
     */
    val branchPrefix: String = "{repo}/",
    /**
     * Prepended to every recreated notes ref, below `refs/notes/`. `{repo}` is substituted.
     *
     * The same rule as the other two, for the same reason: `refs/notes/commits` is what `git notes`
     * writes by default, so an input with notes usually has that one. Two inputs then meeting on one
     * name is refused rather than resolved — see [BraidWriter.resolveRefs].
     */
    val notesPrefix: String = "{repo}/",
    /**
     * Whether an annotated tag is recreated as a lightweight one, dropping its tagger and message.
     *
     * Off, because dropping them loses text no other object holds and a merge should not do that
     * quietly. On, it is the deliberate case: a run that wants the ref names without carrying the
     * name, address and date of whoever cut each of forty releases.
     */
    val lightweightTags: Boolean = false,
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
    /** Every commit written: the braid's, and the one each notes ref points at. */
    val commits: Int,
    /**
     * Every tree written: the distinct trees the braid built, a nested destination's own levels
     * among them, and each notes commit's own.
     */
    val trees: Int,
    val branches: Int,
    val tags: Int,
    /**
     * Carried-over refs a pattern's destination wrote outside `refs/heads/` and `refs/tags/`. The
     * notes and the `--keep-remotes` mirrors are not among them: each has a count of its own.
     */
    val foreign: Int,
    /** Notes refs written, zero unless the run asked for the notes. */
    val notes: Int,
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
    /** Whether to mirror the refs carried over and each input's mainline, bar notes — see [mirrorInputs]. */
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
            // A notes ref is one commit over one flat tree, written by [rewrittenNotes].
            commits = plan.commits.size + refs.notes,
            trees = trees.treesWritten + refs.notes,
            branches = refs.branches,
            tags = refs.tags,
            foreign = refs.foreign,
            notes = refs.notes,
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
        val foreign: Int,
        val notes: Int,
        val remoteRefs: Int,
    )

    /**
     * Works out the complete set of refs the output should have, writing an object for every
     * annotated tag on the way. Nothing is published here — see [write] for why.
     *
     * The mainline collapses: every input contributed its mainline to one braid, so the output gets
     * one branch at the braid's tip, named by the unscoped `--mainline-branch` or else after the
     * first input's mainline. Every other ref is written under the prefix its kind carries —
     * `--branch-prefix` for a branch, `--tag-prefix` for a tag — and that prefix applies to all of
     * them alike.
     *
     * **Unconditionally, bar one thing: a pattern spelling its destination out takes the name as
     * written, prefix and all.** Short of that, an output ref name is a function of its input. The
     * qualifier used to go on a branch only where two inputs had used the name, so whether a branch
     * kept its own name depended on what else the run selected: narrowing one input with `--ref`
     * could leave another as that name's only holder and rename *its* branch. A run that emptied the
     * prefix asks for the plain names and gets them; two inputs meeting there is [claim].
     *
     * Which of the two prefixes a ref sees follows from where it is written rather than from where
     * it came: a pattern may carry a destination, and a branch written into `refs/tags/` is a tag of
     * the output whatever it was at home. The counts in [WriteSummary] follow the same rule, since
     * what a reader of the output can see is its kind there.
     */
    private fun resolveRefs(): Refs {
        val refs = LinkedHashMap<String, ObjectId>()
        // Who claimed each name, so a collision can name both sides rather than the loser alone.
        val claimed = HashMap<String, String>()

        val braidTip = plan.braid.lastOrNull()
            ?: error("the braid is empty -- there is nothing to point a branch at")
        val mainline = Constants.R_HEADS + inputs.mainlineBranch
        refs[mainline] = idOf(braidTip)
        // The braid's own name is in the running too: an input whose mainline is `master` may carry
        // an ordinary branch called `main`, and with nothing to qualify it that is the output's
        // mainline being overwritten by a side branch.
        claimed[mainline] = BRAID
        var branches = 1

        var tags = 0
        var foreign = 0
        for (input in inputs.sources) {
            val repo = input.source.name
            for (ref in input.refs) {
                // Its own mainline, not the output's: two inputs may braid along differently named
                // branches, and each is the one already spoken for by the braid rather than a ref to
                // write. Matched on the input's full name, which is the only name that still says
                // so once a destination may have renamed it.
                if (ref.isMainlineOf(input)) continue
                val name = named(ref, repo)
                claim(claimed, name, repo, prefixFlag(ref))
                refs[name] = targetOf(name, ref)
                when {
                    name.startsWith(Constants.R_HEADS) -> branches++
                    name.startsWith(Constants.R_TAGS) -> tags++
                    else -> foreign++
                }
            }
        }

        var notes = 0
        for (input in inputs.sources) {
            val repo = input.source.name
            for (notesRef in input.notes) {
                val name = Constants.R_NOTES + options.notesPrefix.replace("{repo}", repo) + notesRef.name
                claim(claimed, name, repo, "--notes-prefix")
                refs[name] = rewrittenNotes(notesRef)
                notes++
            }
        }

        val remoteRefs = if (mirrorRemotes) mirrorInputs(refs, claimed) else 0

        checkRefNames(refs.keys)
        return Refs(refs, branches, tags, foreign, notes, remoteRefs)
    }

    /**
     * Writes [notesRef] as a notes commit of the output, keyed by the shas the braid gave those
     * commits.
     *
     * A flat tree, with no fan-out: git reads a notes tree at whatever depth it finds one, and the
     * fan-out exists to keep a directory listing small in a repository with a great many notes. The
     * numbers here do not call for it — a note is written by hand, one commit at a time — and a flat
     * tree is the one shape that needs no rule about when to split.
     *
     * One commit with no parents. The original's author, committer and message come across because
     * the output has no business inventing either, but its ancestry cannot: every tree behind it is
     * keyed by shas the output never wrote under those names, so carrying the history would carry
     * a chain of notes attached to nothing.
     */
    private fun rewrittenNotes(notesRef: BraidNotes): ObjectId {
        val entries = notesRef.entries.map { (commit, blob) ->
            TreeEntry(idOf(commit).name, FileMode.REGULAR_FILE, blob)
        }
        return target.writeCommit(
            tree = target.writeTree(entries),
            parents = emptyList(),
            author = notesRef.author,
            committer = notesRef.committer,
            message = notesRef.message,
        )
    }

    /**
     * The full output name of [ref]: its namespace, the prefix that namespace carries, and its name.
     *
     * The middle term is empty where the run spelled the name out itself. That is the whole of how a
     * destination and a prefix get along — the prefix is the naming rule, and a destination
     * overrides exactly the part of it that it wrote.
     */
    private fun named(ref: BraidOutRef, repo: String): String {
        val prefix = if (!ref.prefixed) "" else prefixOf(ref.namespace).replace("{repo}", repo)
        return ref.namespace + prefix + ref.name
    }

    /**
     * The prefix template belonging to a namespace.
     *
     * Only the two the program names a rule for can reach this: a pattern reading any other
     * namespace has to spell its destination out, which turns the prefix off before this is asked.
     */
    private fun prefixOf(namespace: String): String = when (namespace) {
        Constants.R_HEADS -> options.branchPrefix
        Constants.R_TAGS -> options.tagPrefix
        else -> error("no prefix rule for '$namespace' -- it should have been named outright")
    }

    /** The option setting the prefix [ref] sees, for a message that has to suggest a remedy. */
    private fun prefixFlag(ref: BraidOutRef): String = when {
        !ref.prefixed -> "the destination"
        ref.namespace == Constants.R_HEADS -> "--branch-prefix"
        else -> "--tag-prefix"
    }

    /**
     * What the output ref points at: a tag object where it is a tag with something to say, and the
     * commit itself everywhere else.
     *
     * An annotation only survives into `refs/tags/`. A branch points at a commit and nothing else,
     * so a tag written as one loses its tagger and message — a deliberate flattening, asked for by
     * naming that destination, rather than a quiet one.
     *
     * [WriteOptions.lightweightTags] asks for that same loss outright, whatever the destination:
     * the ref points straight at the commit and no tag object is written at all.
     */
    private fun targetOf(name: String, ref: BraidOutRef): ObjectId {
        val commit = idOf(ref.commit)
        if (!name.startsWith(Constants.R_TAGS)) return commit
        if (options.lightweightTags) return commit
        val annotation = ref.annotation ?: return commit
        return target.writeAnnotatedTag(
            name = name.removePrefix(Constants.R_TAGS),
            target = commit,
            tagger = annotation.tagger,
            message = stripSignature(annotation.message),
        )
    }

    /**
     * Records that [repo] wants [name], refusing a name already taken.
     *
     * Reached four ways. A prefix that has stopped telling two inputs apart, such as an emptied one
     * or one holding no `{repo}`: the default qualifies every ref with the name of its input, and
     * so keeps them apart. The braid's own branch, which takes no prefix, so an input can meet it
     * under any prefix: under the default, input `release`'s branch `x` meets a mainline called
     * `release/x`. A destination spelled out in full, which takes the prefix off whatever it holds.
     * And one input sending two of its refs to one name — a branch `v1.0` written as a tag beside
     * its tag `v1.0`, or a destination with no `*` given a pattern that matches more than one ref.
     * The braid's own mainline claims its name first, so the side already holding one may be it.
     * Refused rather than resolved: the two refs can point at different commits, so silently keeping
     * either would publish one ref's history under a name the other's reader would look up.
     */
    private fun claim(claimed: MutableMap<String, String>, name: String, repo: String, prefixOption: String) {
        // The run's own corner of the ref space: the fetch parks the inputs' refs there, and
        // everything under it is deleted once the braid is written, so a ref of the braid's there
        // would be counted as written and then be gone.
        require(!name.startsWith(TargetRepository.FETCH_NAMESPACE)) {
            "'$repo' would write '$name', under ${TargetRepository.FETCH_NAMESPACE}, where this run " +
                "parks the refs it fetches and which it empties once the braid is written; give it " +
                "another destination"
        }
        val first = claimed.put(name, repo) ?: return
        throw IllegalArgumentException(
            // A prefix that keeps inputs apart can still spell the braid's own name, as `{repo}/`
            // does for input `release` and a mainline `release/x`, so advising one is not enough.
            if (first == BRAID) {
                "'$first' and '$repo' would both write '$name'; " +
                    "give $prefixOption a template, or the input a name, that moves its refs off the " +
                    "braid's, or narrow the run"
            } else if (first == repo) {
                "two refs of '$repo' would both write '$name'; " +
                    "give one of them another destination, or narrow the run"
            } else {
                "'$first' and '$repo' would both write '$name'; " +
                    "give $prefixOption a template that keeps {repo} apart from the name, as {repo}/ " +
                    "does, or narrow the run"
            }
        )
    }

    /**
     * Adds a ref under `refs/remotes/<name>/` for every ref the run carried over, and for each input's
     * mainline whether the selection took it or not — a branch at its own name, everything else under
     * the tail of its namespace — each pointing at that input's *original* commit. Notes are not among
     * them: they are carried on their own field and written under `refs/notes/`, and a notes ref names
     * a notes commit, which is not in the graph and so has no original to point at.
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
     * What an annotated tag holds beyond its target — its tagger, its message — is recreated in
     * full by [targetOf] where the tag is written under `refs/tags/`, unless `--lightweight-tags`
     * asks for none. A tag a destination writes as a branch loses it as well, and this mirror,
     * pointing at the commit, keeps none of it.
     *
     * The mirror covers every ref the run carried over, the selection and the labels alike, so `-b`
     * narrows it as it narrows the output — bar the mainline, which the paragraph below exempts. A
     * label was not read and so was not fetched, but it is attached only where its target is already
     * in the graph — the object came in behind some selected ref's ancestry — so no mirrored ref
     * points at nothing.
     *
     * Each input's mainline is mirrored whether or not the selection named it. It is read either
     * way, so its originals are in the output either way, and a narrowed run would otherwise leave
     * exactly the chain the tags paragraph above is about: fetched, rewritten under the output's
     * own branch, and named by nothing at all.
     *
     * A name the braid already writes is refused rather than written over, the mainline's mirror
     * as much as any other. A destination spelled out under `refs/remotes/<name>/` can meet a
     * mirror there, and either write winning loses something: the mirror drops the rewritten commit
     * the destination asked for, the destination leaves the original unnamed. So, as for two inputs
     * meeting in [claim], the run is refused, and the refusal names both. So is a destination
     * there that meets no mirror: the remote's refspec covers the whole of `refs/remotes/<name>/`,
     * and a pruning fetch deletes whatever in it names no branch of the input.
     *
     * @param claimed who wrote each of the braid's own names, as [resolveRefs] recorded them.
     * @return how many remote-tracking refs were added.
     */
    private fun mirrorInputs(refs: MutableMap<String, ObjectId>, claimed: Map<String, String>): Int {
        fun refuseClaimed(name: String, input: SourceInputs, prefix: String) {
            val first = claimed[name] ?: return
            throw IllegalArgumentException(
                "'$first' and the --keep-remotes mirror of '${input.source.name}' would both " +
                    "write '$name'; spell that destination outside $prefix, or drop --keep-remotes"
            )
        }

        var added = 0
        for (input in inputs.sources) {
            val prefix = Constants.R_REMOTES + input.source.name + "/"
            // Which of this input's refs holds each mirror name, so two meeting can be named.
            val mirroredFrom = HashMap<String, String>()
            for (ref in input.refs) {
                // Keyed by what the ref is called at *home*, since that is what a mirror records:
                // a branch beside the remote's own branches, everything else under the tail of its
                // namespace, which is what keeps a branch and a tag of one name apart.
                val where = when {
                    ref.fullName.startsWith(Constants.R_HEADS) ->
                        ref.fullName.removePrefix(Constants.R_HEADS)
                    else -> ref.fullName.removePrefix(Constants.R_REFS)
                }
                val name = prefix + where
                refuseClaimed(name, input, prefix)
                // A branch literally named `tags/v1.0` mirrors to the name the tag `v1.0` does.
                mirroredFrom.put(name, ref.fullName)?.let { first ->
                    throw IllegalArgumentException(
                        "'$first' and '${ref.fullName}' of '${input.source.name}' would both be " +
                            "mirrored as '$name'; rename one of them in the input, narrow the run, " +
                            "or drop --keep-remotes"
                    )
                }
                refs[name] = originalOf(ref.commit).id
                added++
            }
            // Its own sha, not one the graph has to be asked for: a selection that never took the
            // mainline has no [BraidOutRef] to read an original off.
            val mainline = prefix + input.mainlineBranch
            refuseClaimed(mainline, input, prefix)
            if (refs.putIfAbsent(mainline, input.mainlineTip) == null) added++
        }
        // A destination meeting no mirror can still sit among them, and the first pruning fetch
        // deletes it for naming no branch of the input: the same loss as a meeting, only later.
        for (input in inputs.sources) {
            val prefix = Constants.R_REMOTES + input.source.name + "/"
            val name = claimed.keys.filter { it.startsWith(prefix) }.minOrNull() ?: continue
            throw IllegalArgumentException(
                "'${claimed.getValue(name)}' would write '$name' among the --keep-remotes mirrors " +
                    "of '${input.source.name}', where a pruning fetch deletes it; spell that " +
                    "destination outside $prefix, or drop --keep-remotes"
            )
        }
        return added
    }

    private fun idOf(commit: Commit): ObjectId =
        written[commit] ?: error("$commit was never written")

    companion object {

        private const val PGP_HEADER = "-----BEGIN PGP SIGNATURE-----"

        /** The holder [claim] records for the braid's own branch, and names when it is met. */
        private const val BRAID = "the braid"

        /**
         * Drops a trailing PGP signature block from a tag message.
         *
         * A signature covers the commit the tag pointed at, which no longer exists under that
         * name here, so carrying it over would attach a proof of something to something else.
         */
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
         * history of every ref the run read, still parked under `refs/timebraid-fetch/`, and this
         * refusal leaves it where it got to, as the manual page's EXIT STATUS says.
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
