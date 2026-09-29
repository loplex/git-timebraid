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
     * Prepended to every commit subject. `{repo}` and `{subdir}` are substituted, as they are in
     * every template here: the input's name, and where it lands — its name again for the input
     * placed at the output root.
     *
     * The default is the name and not the destination because a destination can be nested
     * arbitrarily deep and the subject line carries it on every commit, while a name is one segment
     * and is what identifies an input everywhere else.
     */
    val subjectPrefix: String = "{repo}: ",
    /**
     * Prepended to every tag name. `{repo}` and `{subdir}` are substituted, and an empty value
     * qualifies nothing.
     */
    val tagPrefix: String = "{repo}/",
    /**
     * Prepended to every branch name, as [tagPrefix] is to every tag, `{repo}` and `{subdir}`
     * substituted.
     *
     * It applies wherever a ref pattern has not spelled its destination out, rather than only where
     * two inputs used one name, which is what makes an output ref name a function of the input it
     * came from alone. The other rule made it a function of what the run selected as well: narrowing
     * one input could leave another as the only holder of a name and rename *its* branch, out of a
     * flag that says nothing about naming.
     *
     * An empty value is how a run asks for no qualification at all. Two inputs then meeting on one
     * name is refused rather than resolved, since either answer would be a guess — see
     * [RefNames.resolve].
     */
    val branchPrefix: String = "{repo}/",
    /**
     * Prepended to every recreated notes ref, below `refs/notes/`, `{repo}` and `{subdir}`
     * substituted.
     *
     * The same rule as the other two, for the same reason: `refs/notes/commits` is what `git notes`
     * writes by default, so an input with notes usually has that one. Two inputs then meeting on one
     * name is refused rather than resolved — see [RefNames.resolve].
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
     * The trailer [provenance] records. `{repo}`, `{subdir}`, `{commit}` and `{parents}` are
     * substituted.
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
    /** Whether to mirror the refs carried over and each input's mainline, bar notes — see [RefNames]. */
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
            .replace("{subdir}", subdir)
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
     * The refs the output gets, as [RefNames] names them, each turned into what it points at —
     * which writes an object for every annotated tag and every notes ref on the way. Nothing is
     * published here: see [write] for why.
     */
    private fun resolveRefs(): Refs {
        val named = RefNames(inputs, plan, options, mirrorRemotes).resolve()
        val targets = LinkedHashMap<String, ObjectId>(named.refs.size)
        for ((name, refSource) in named.refs) {
            targets[name] = when (refSource) {
                is RefSource.Braid -> idOf(refSource.commit)
                is RefSource.Of -> targetOf(name, refSource.ref)
                is RefSource.Notes -> rewrittenNotes(refSource.notesRef)
                is RefSource.Original -> refSource.id
            }
        }
        return Refs(targets, named.branches, named.tags, named.foreign, named.notes, named.remoteRefs)
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

    private fun idOf(commit: Commit): ObjectId =
        written[commit] ?: error("$commit was never written")

    companion object {

        private const val PGP_HEADER = "-----BEGIN PGP SIGNATURE-----"

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

    }
}
