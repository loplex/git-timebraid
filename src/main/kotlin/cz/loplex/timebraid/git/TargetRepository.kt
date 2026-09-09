package cz.loplex.timebraid.git

import org.eclipse.jgit.internal.storage.file.ObjectDirectory
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Path

/**
 * The output repository, opened for writing.
 *
 * The inputs' own objects arrive by [fetchFrom]; everything the braid invents on top of them —
 * the rewritten commits, the new root trees, the tag objects — is written through a single
 * [ObjectInserter], and where the storage backend allows it that inserter is a *pack* inserter,
 * because one pack file is the difference between a repository that opens instantly and a directory
 * holding tens of thousands of loose files.
 *
 * Objects only become visible to readers — including this repository's own ref updates — after
 * [flushObjects]. Refs are therefore written in a second pass, once every commit exists.
 */
class TargetRepository private constructor(
    /** Where the repository was created, for diagnostics. */
    val location: Path,
    private val repository: Repository,
) : AutoCloseable {

    private val inserter: ObjectInserter =
        (repository.objectDatabase as? ObjectDirectory)?.newPackInserter()
            ?: repository.newObjectInserter()

    /** Builds the braid's trees into this repository, deduplicating identical ones. */
    fun treeAssembler(dissolveSubmodules: Boolean = false): TreeAssembler =
        TreeAssembler(inserter, dissolveSubmodules)

    /**
     * Fetches everything reachable from [refs] in [source] into this repository, parking the refs
     * themselves under [FETCH_NAMESPACE].
     *
     * This is how the output gets the inputs' objects — all of them, in one transfer per input.
     * The trees and blobs come across because the braid's new root trees point straight at them; the
     * commits come across because a fetch cannot leave them out, and because moving their bytes is
     * the only way an original sha survives, which is what `--keep-remotes` points its
     * `refs/remotes/<repo>/<branch>` at.
     *
     * A fetch rather than an object-by-object copy for two reasons that were measured on a
     * three-repository history of 14 387 commits and 139 MB of inputs: it is roughly 2.5x faster
     * than walking the inputs and feeding every tree and blob to an [ObjectInserter], and the
     * sending side deltifies what it sends, where the inserter can only store each object whole —
     * 97 MB against 221 MB for the same content.
     *
     * [refs] narrows the transfer to exactly the refs the graph was read from, so `-b` keeps out
     * history this run never meant to include; a ref pointing at an object that is not there is a
     * broken repository, and so is a repository holding history nobody asked for.
     *
     * @return how many refs were fetched.
     */
    fun fetchFrom(source: SourceRepository, refs: List<String>): Int {
        if (refs.isEmpty()) return 0
        val specs = refs.map { RefSpec("+$it:" + fetchedName(source.name, it)) }
        try {
            // java.io.File spells a path as the single-slash `file:/…` URI that URIish parses back
            // into a plain local path on both platforms, escaping included — a Windows drive letter
            // survives it where `Path.toUri()`'s `file:///C:/…` is read as a host named C.
            val uri = URIish(source.location.toAbsolutePath().normalize().toFile().toURI().toString())
            Transport.open(repository, uri).use { transport ->
                // Every ref that matters is named in `specs`. Auto-following would add whatever tags
                // the input has beyond them, which is the widening the refspec exists to prevent.
                transport.tagOpt = TagOpt.NO_TAGS
                transport.fetch(NullProgressMonitor.INSTANCE, specs)
            }
        } catch (e: IOException) {
            // JGit's TransportException and NotSupportedException are both IOExceptions, so this is
            // the whole of what a fetch can fail with — bar an unparseable location, which Kotlin
            // would otherwise let past unnoticed and the CLI would report as a stack trace.
            throw fetchFailed(source, e)
        } catch (e: URISyntaxException) {
            throw fetchFailed(source, e)
        }
        return refs.size
    }

    private fun fetchFailed(source: SourceRepository, cause: Exception) = IllegalStateException(
        "could not fetch '${source.name}' from ${source.location} into $location: ${cause.message}",
        cause,
    )

    /**
     * Deletes the refs [fetchFrom] parked and returns how many.
     *
     * They are a handle for the transfer and nothing more: git has no way to ask for objects without
     * naming refs, and the refs the output keeps are the braid's own, written by `BraidWriter`.
     * What deleting them leaves behind is the inputs' original commits, unreferenced in the pack —
     * 3 MB of the 97 on the corpus above, which `git gc --prune=now` reclaims and which `git fsck`
     * reports as `dangling commit` until it does.
     */
    fun dropFetchRefs(): Int {
        val fetched = repository.refDatabase.getRefsByPrefix(FETCH_NAMESPACE)
        for (ref in fetched) {
            val update = repository.updateRef(ref.name).apply { isForceUpdate = true }
            val result = update.delete()
            check(result in ACCEPTED) { "could not delete ${ref.name} in $location: $result" }
        }
        return fetched.size
    }

    /**
     * Writes [text] as a blob and returns its id.
     *
     * The output's content is otherwise copied from the inputs object for object, so this exists for
     * the one file the braid has to invent: the root `.gitmodules` of [SubmoduleWiring]. Git stores a
     * blob as bytes and this one is text, so the encoding is settled here rather than at the call
     * site — UTF-8, which is what git itself assumes of a `.gitmodules`.
     */
    fun writeBlob(text: String): ObjectId =
        inserter.insert(Constants.OBJ_BLOB, text.toByteArray(Charsets.UTF_8))

    /** Writes a commit object and returns its id. */
    fun writeCommit(
        tree: ObjectId,
        parents: List<ObjectId>,
        author: PersonIdent,
        committer: PersonIdent,
        message: String,
    ): ObjectId {
        val builder = CommitBuilder().apply {
            // JGit pairs getTreeId(): ObjectId with setTreeId(AnyObjectId), so Kotlin sees a
            // read-only property plus a setter method: the property-access syntax the inspection
            // suggests does not compile.
            @Suppress("UsePropertyAccessSyntax")
            setTreeId(tree)
            setParentIds(parents)
            this.author = author
            this.committer = committer
            setMessage(message)
        }
        return inserter.insert(builder)
    }

    /**
     * Writes an annotated tag object pointing at [target] and returns its id. [name] is the tag's
     * final (prefixed) name, so the name inside the object and the name of the ref agree.
     */
    fun writeAnnotatedTag(
        name: String,
        target: ObjectId,
        tagger: PersonIdent?,
        message: String,
    ): ObjectId {
        val builder = TagBuilder().apply {
            setObjectId(target, Constants.OBJ_COMMIT)
            tag = name
            setTagger(tagger)
            setMessage(message)
        }
        return inserter.insert(builder)
    }

    /** Makes every object written so far visible to readers. Must precede any ref update. */
    fun flushObjects() = inserter.flush()

    /** Points [refName] (a full name such as `refs/heads/main`) at [target]. */
    fun point(refName: String, target: ObjectId) {
        require(Repository.isValidRefName(refName)) { "'$refName' is not a valid ref name" }
        val update = repository.updateRef(refName).apply {
            // getNewObjectId(): ObjectId against setNewObjectId(AnyObjectId) — same asymmetry as
            // above: assigning through the property does not compile.
            @Suppress("UsePropertyAccessSyntax")
            setNewObjectId(target)
            isForceUpdate = true
        }
        val result = update.update()
        check(result in ACCEPTED) { "could not write $refName in $location: $result" }
    }

    /** Points `HEAD` at `refs/heads/[branch]`, whether or not that branch exists yet. */
    fun setHead(branch: String) {
        val target = Constants.R_HEADS + branch
        require(Repository.isValidRefName(target)) { "'$branch' is not a valid branch name" }
        val result = repository.updateRef(Constants.HEAD, true).link(target)
        check(result in ACCEPTED) { "could not point HEAD at $target in $location: $result" }
    }

    override fun close() {
        inserter.close()
        repository.close()
    }

    companion object {

        /**
         * Where [fetchFrom] parks the refs it fetches, laid out as `<repo>/<the input's own ref>`.
         *
         * Its own corner of the ref space rather than `refs/remotes/`, because these refs are not
         * the output's: they exist for the length of the transfer and [dropFetchRefs] removes every
         * one of them. Keeping the input's own `heads/…` and `tags/…` split is what stops a branch
         * and a tag of the same name from colliding on the way in.
         */
        const val FETCH_NAMESPACE = "refs/timebraid-fetch/"

        /** Where the input's [ref] (a full name) lands while the fetch is running. */
        private fun fetchedName(repo: String, ref: String): String =
            FETCH_NAMESPACE + repo + "/" + ref.removePrefix(Constants.R_REFS)

        private val ACCEPTED = setOf(
            RefUpdate.Result.NEW,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.NO_CHANGE,
            RefUpdate.Result.FAST_FORWARD,
        )

        /**
         * Creates (or reopens, with [force]) a repository at [location], bare by default.
         *
         * Without [force] the location must not exist, or must be an empty directory: overwriting
         * refs in a repository somebody else is using is not something to do by accident. Nothing is
         * ever deleted here — [force] only permits writing *into* what is already there.
         *
         * When [bare] is `false`, [location] becomes the working tree and its `.git` the git
         * directory; the working tree is left empty here — the caller checks the mainline out through
         * the git CLI once every object has been flushed.
         */
        fun create(
            location: Path,
            initialBranch: String,
            force: Boolean = false,
            bare: Boolean = true,
        ): TargetRepository {
            val gitDir = if (bare) location.toFile() else location.resolve(Constants.DOT_GIT).toFile()
            val existingRepository = RepositoryCache.FileKey.isGitRepository(gitDir, FS.DETECTED)
            if (location.toFile().exists()) {
                val occupied = existingRepository || (location.toFile().list()?.isNotEmpty() ?: false)
                require(!occupied || force) {
                    "$location already exists and is not empty; pass --force to write into it"
                }
            }

            val builder = FileRepositoryBuilder().setGitDir(gitDir)
            if (!bare) builder.setWorkTree(location.toFile())
            val repository = builder.build()
            if (!existingRepository) {
                repository.create(bare)
                configureForRewriting(repository)
            }
            val target = TargetRepository(location, repository)
            target.setHead(initialBranch)
            return target
        }

        /**
         * Pins line-ending handling off in the output's own config.
         *
         * Blobs are copied byte for byte from the inputs, so any translation on the way to a working
         * tree would silently disagree with what the original repositories contain. This matters
         * directly for a `--no-bare` output and travels with a bare one to whoever later adds a
         * working tree.
         */
        private fun configureForRewriting(repository: Repository) {
            val config = repository.config
            config.setBoolean("core", null, "autocrlf", false)
            config.setString("core", null, "eol", "lf")
            config.save()
        }
    }
}
