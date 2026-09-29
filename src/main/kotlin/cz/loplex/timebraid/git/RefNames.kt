package cz.loplex.timebraid.git

import cz.loplex.timebraid.plan.Commit
import cz.loplex.timebraid.plan.MergePlan
import cz.loplex.timebraid.plan.Source
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

/** What an output ref is to point at, told apart by what has to be written to get it. */
sealed class RefSource {
    /** The output's mainline, at the braid's tip. */
    class Braid(val commit: Commit) : RefSource()

    /** A ref carried over, at the rewritten commit, or a tag object over it. */
    class Of(val ref: BraidOutRef) : RefSource()

    /** A notes ref, at the notes commit written for it. */
    class Notes(val notesRef: BraidNotes) : RefSource()

    /** A `--keep-remotes` mirror, at the input's own original commit. */
    class Original(val id: ObjectId) : RefSource()
}

/** Every name the output's refs get, and what each points at; see [RefNames.resolve]. */
class Named(
    val refs: Map<String, RefSource>,
    val branches: Int,
    val tags: Int,
    val foreign: Int,
    val notes: Int,
    val remoteRefs: Int,
)

/**
 * What a ref carried over is called in the output, in the two parts a prefix goes between.
 *
 * The output name is held in two parts because the run may have written either, neither or both of
 * them. [namespace] and [name] are always concatenated to make it; [prefixed] says whether the
 * naming rule for [namespace] — `--branch-prefix`, `--tag-prefix` — goes between the two.
 *
 * That is the whole of how a destination and a prefix get along. A run that gives no destination
 * leaves both to the prefix rule; one that names a namespace takes that half; one that spells out a
 * pattern takes all of it and the prefix stays out.
 */
class OutputName(
    /** Namespace the output writes it into, `refs/heads/` and the like, with its trailing slash. */
    val namespace: String,
    /** Name below [namespace]. */
    val name: String,
    /** Whether the prefix belonging to [namespace] goes between the two. */
    val prefixed: Boolean = true,
) {
    companion object {

        /**
         * What [ref], of the input named [repo] and placed at [subdir], is called in the output under
         * the pattern that took it.
         *
         * The three destination forms land here as the two halves of a name plus one boolean. A
         * namespace destination swaps the namespace and leaves the rest to that namespace's
         * prefix; a spelled-out destination fills both halves and turns the prefix off; no
         * destination at all keeps the ref exactly where it was.
         */
        fun of(ref: BraidOutRef, repo: String, subdir: String = repo): OutputName {
            val pattern = ref.takenBy
            val spelled = pattern?.resolve(ref.fullName, repo, subdir)
            val namespace = pattern?.destination?.takeIf { pattern.toNamespace }
            return when {
                // Split at the last separator so the two halves still join back, and so a name
                // under refs/heads or refs/tags is still counted as the branch or the tag it became.
                spelled != null -> OutputName(
                    spelled.substringBeforeLast('/') + "/",
                    spelled.substringAfterLast('/'),
                    prefixed = false,
                )
                namespace != null -> OutputName(namespace, ref.name)
                else -> OutputName(ref.namespace, ref.name)
            }
        }
    }
}

/**
 * Names the output's refs, and refuses a set of names that cannot all be written, without writing
 * anything.
 *
 * Kept apart from [BraidWriter] because none of it waits on the write: a name follows from the
 * inputs as read, the options and the braid's tip, never from an id the write gives out. So the
 * runner asks it before the output is created, where a dry run asks it too, and a name git would
 * not accept, or a clash between two, is refused before a single object is fetched into the output
 * rather than once every commit is written.
 */
class RefNames(
    private val inputs: BraidInputs,
    private val plan: MergePlan,
    private val options: WriteOptions = WriteOptions(),
    /** Whether the inputs' refs are mirrored under `refs/remotes/` too — see [mirrorInputs]. */
    private val mirrorRemotes: Boolean = false,
) {

    private fun originalOf(commit: Commit): SourceCommit =
        inputs.commits[commit] ?: error("$commit is not a commit of these inputs")

    /**
     * The complete set of refs the output should have, each with what it is to point at, and every
     * refusal a clash of names meets — with nothing written, since none of it depends on an id the
     * write gives out: the inputs as read, the options and the plan's braid decide it all.
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
    fun resolve(): Named {
        val refs = LinkedHashMap<String, RefSource>()
        // Who claimed each name, so a collision can name both sides rather than the loser alone.
        val claimed = HashMap<String, String>()

        val braidTip = plan.braid.lastOrNull()
            ?: error("the braid is empty -- there is nothing to point a branch at")
        val mainline = Constants.R_HEADS + inputs.mainlineBranch
        refs[mainline] = RefSource.Braid(braidTip)
        // The braid's own name is in the running too: an input whose mainline is `master` may carry
        // an ordinary branch called `main`, and with nothing to qualify it that is the output's
        // mainline being overwritten by a side branch.
        claimed[mainline] = BRAID
        var branches = 1

        var tags = 0
        var foreign = 0
        for (input in inputs.sources) {
            val repo = input.source.name
            val subdir = subdirOf(input.source)
            for (ref in input.refs) {
                // Its own mainline, not the output's: two inputs may braid along differently named
                // branches, and each is the one already spoken for by the braid rather than a ref to
                // write. Matched on the input's full name, which is the only name that still says
                // so once a destination may have renamed it.
                if (ref.isMainlineOf(input)) continue
                val name = named(ref, repo, subdir)
                claim(claimed, name, repo, prefixFlag(OutputName.of(ref, repo, subdir)))
                refs[name] = RefSource.Of(ref)
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
                val name = Constants.R_NOTES + expand(options.notesPrefix, input.source) + notesRef.name
                claim(claimed, name, repo, "--notes-prefix")
                refs[name] = RefSource.Notes(notesRef)
                notes++
            }
        }

        val remoteRefs = if (mirrorRemotes) mirrorInputs(refs, claimed) else 0

        // The rule the write holds each name to, asked here so that a template git would not accept
        // is refused with the clashes, before the output exists; the write keeps its own check.
        for (name in refs.keys) {
            require(TargetRepository.isRefName(name)) { "'$name' is not a valid ref name" }
        }
        checkRefNames(refs.keys)
        return Named(refs, branches, tags, foreign, notes, remoteRefs)
    }

    /**
     * The full output name of [ref]: its namespace, the prefix that namespace carries, and its name.
     *
     * The middle term is empty where the run spelled the name out itself. That is the whole of how a
     * destination and a prefix get along — the prefix is the naming rule, and a destination
     * overrides exactly the part of it that it wrote.
     */
    private fun named(ref: BraidOutRef, repo: String, subdir: String): String {
        val out = OutputName.of(ref, repo, subdir)
        val prefix = if (!out.prefixed) "" else expand(prefixOf(out.namespace), repo, subdir)
        return out.namespace + prefix + out.name
    }

    /**
     * Where [source] lands, for a `{subdir}` in a template: its destination, or its name for the
     * input placed at the output root, as a commit subject has it.
     */
    private fun subdirOf(source: Source): String = plan.subdirOf(source) ?: source.name

    /** [template] with `{repo}` and `{subdir}` substituted for [source]. */
    private fun expand(template: String, source: Source): String =
        expand(template, source.name, subdirOf(source))

    private fun expand(template: String, repo: String, subdir: String): String =
        template.replace("{repo}", repo).replace("{subdir}", subdir)

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

    /** The option setting the prefix [out] sees, for a message that has to suggest a remedy. */
    private fun prefixFlag(out: OutputName): String = when {
        !out.prefixed -> "the destination"
        out.namespace == Constants.R_HEADS -> "--branch-prefix"
        else -> "--tag-prefix"
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
     * [TargetRepository.dropFetchRefs], and that is what this adds.
     *
     * Tags are covered as well as branches because a great many commits hang off them and nothing
     * else: on a three-repository history of 14 387 commits, a corpus outside this
     * tree, 929 of them were reachable in their input from a tag alone, and
     * mirroring only the branches left every one of those originals with no ref
     * pointing at it — present in the output, but unreachable, and pruned by the first `git gc`
     * once git's grace period for unreachable objects, two weeks by default, has passed. They go
     * under `tags/` so that the branch `v1.0` and the tag `v1.0` of one
     * input do not land on the same name. That keeps the usual pair apart, and not every pair git
     * accepts: a branch literally named `tags/v1.0` still meets the tag `v1.0` here, and that is
     * refused, since either write winning would leave the other ref's originals with no mirror —
     * the mainline's included, which is mirrored whether the selection took it or not.
     *
     * A ref here points at the commit a tag peels to rather than at the input's own tag object.
     * What an annotated tag holds beyond its target — its tagger, its message — is recreated in
     * full by `BraidWriter` where the tag is written under `refs/tags/`, unless
     * `--lightweight-tags` asks for none. A tag a destination writes as a branch loses it as well,
     * and this mirror, pointing at the commit, keeps none of it.
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
     * @param claimed who wrote each of the braid's own names, as [resolve] recorded them.
     * @return how many remote-tracking refs were added.
     */
    private fun mirrorInputs(refs: MutableMap<String, RefSource>, claimed: Map<String, String>): Int {
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
                refs[name] = RefSource.Original(originalOf(ref.commit).id)
                added++
            }
            // Its own sha, not one the graph has to be asked for: a selection that never took the
            // mainline has no [BraidOutRef] to read an original off. A selection that did has
            // mirrored it above; any other ref holding its name is a meeting like the one there.
            val mainline = prefix + input.mainlineBranch
            refuseClaimed(mainline, input, prefix)
            val home = Constants.R_HEADS + input.mainlineBranch
            when (val first = mirroredFrom[mainline]) {
                null -> {
                    refs[mainline] = RefSource.Original(input.mainlineTip)
                    added++
                }
                home -> Unit
                else -> throw IllegalArgumentException(
                    "'$first' and '$home' of '${input.source.name}' would both be mirrored as " +
                        "'$mainline'; rename one of them in the input, narrow the run, or drop " +
                        "--keep-remotes"
                )
            }
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

    companion object {

        /** The holder [claim] records for the braid's own branch, and names when it is met. */
        private const val BRAID = "the braid"

        /**
         * Refs are paths, so `refs/heads/a` and `refs/heads/a/b` cannot both exist — git would have
         * to store a file and a directory under the same name. Detecting it with the other names,
         * before anything is fetched into the output, turns a lock error half way through the refs
         * into one message naming both, which a dry run gives as well.
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
