package cz.loplex.timebraid.git

import org.eclipse.jgit.internal.storage.file.ObjectDirectory
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import java.nio.file.Path

/**
 * The output repository, opened for writing.
 *
 * Everything is written through a single [ObjectInserter], and where the storage backend allows it
 * that inserter is a *pack* inserter: a merge of three real repositories produces tens of thousands
 * of objects, and one pack file is the difference between a repository that opens instantly and a
 * directory holding 27 000 loose files.
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

    /** Builds root trees into this repository, deduplicating identical ones. */
    val trees: RootTreeAssembler = RootTreeAssembler(inserter)

    /**
     * Copies every tree and blob reachable from [tips] in [source] into this repository.
     *
     * The braid reuses the inputs' trees and blobs verbatim — only commits are rewritten — so this
     * is what makes the output stand on its own once the inputs are gone.
     *
     * @return the number of objects offered to the inserter; duplicates are recognised and stored
     *   once, so the count is an upper bound on what actually reaches the pack.
     */
    fun importContentObjects(source: SourceRepository, tips: Collection<ObjectId>): Int {
        var seen = 0
        source.forEachContentObject(tips) { type, loader ->
            if (loader.isLarge) {
                inserter.insert(type, loader.size, loader.openStream())
            } else {
                inserter.insert(type, loader.cachedBytes)
            }
            seen++
        }
        return seen
    }

    /**
     * Writes a blob and returns its id.
     *
     * The output's content is otherwise copied from the inputs object for object, so this exists for
     * the one file the braid has to invent: the root `.gitmodules` of [SubmoduleWiring].
     */
    fun writeBlob(content: ByteArray): ObjectId = inserter.insert(Constants.OBJ_BLOB, content)

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
