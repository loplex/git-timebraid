package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.util.FS
import java.io.File
import java.nio.file.Path

/**
 * One input repository — one *strand* of the braid — opened for reading only.
 *
 * The class exists to keep every JGit type on this side of the boundary: it hands back plain data
 * ([GitRef], [TagRef], [SourceCommit], [TreeEntry]) and the planner never sees an [ObjectId], a
 * [RevWalk] or a [Repository]. Nothing here writes, and network operations (clone, fetch) are a
 * separate concern, handled through the git CLI in [GitCommand].
 */
class SourceRepository private constructor(
    /** Short name of the repository, used as the default subdirectory and the tag prefix. */
    val name: String,
    /** Where the repository was opened from, for diagnostics. */
    val location: Path,
    private val repository: Repository,
) : AutoCloseable {

    /** Kept open for the whole life of the handle: [topLevelEntries] is called once per commit. */
    private var reader: ObjectReader? = null

    /** Local branches (under `refs/heads/`), sorted by their short name. */
    fun branches(): List<GitRef> =
        repository.refDatabase.getRefsByPrefix(Constants.R_HEADS)
            .sortedBy { it.name }
            .mapNotNull { ref ->
                val id = ref.leaf.objectId ?: return@mapNotNull null
                GitRef(ref.name.removePrefix(Constants.R_HEADS), id)
            }

    /**
     * Tags (under `refs/tags/`), sorted by their short name, each peeled to the commit it ultimately
     * points at. A tag that does not resolve to a commit (a tag of a tree or a blob) is dropped —
     * it has no place on a history graph.
     *
     * An annotated tag also carries its [TagAnnotation], so the writer can recreate it as an
     * annotated tag rather than silently flattening the tagger and the tag message away.
     */
    fun tags(): List<TagRef> {
        RevWalk(repository).use { walk ->
            walk.isRetainBody = true
            return repository.refDatabase.getRefsByPrefix(Constants.R_TAGS)
                .sortedBy { it.name }
                .mapNotNull { ref ->
                    val pointee = walk.parseAny(ref.objectId)
                    val peeled = walk.peel(pointee)
                    if (peeled !is RevCommit) return@mapNotNull null
                    val annotation = (pointee as? RevTag)?.let {
                        TagAnnotation(it.taggerIdent, it.fullMessage)
                    }
                    TagRef(ref.name.removePrefix(Constants.R_TAGS), peeled.id, annotation)
                }
        }
    }

    /** Object a branch points at, or `null` if the repository has no such branch. */
    fun resolveBranch(shortName: String): ObjectId? =
        repository.resolve(Constants.R_HEADS + shortName)

    /**
     * Every commit reachable from [tips], their ancestors included, as plain data. The [RevWalk] and
     * its object reader are closed before this returns, so the result outlives the repository handle.
     *
     * A tip that is not a commit (or an annotated tag of one) is skipped rather than rejected: the
     * caller passes in whatever the refs point at, and a stray tag of a tree should not abort the run.
     */
    fun readReachable(tips: Collection<ObjectId>): List<SourceCommit> {
        val commits = ArrayList<SourceCommit>()
        RevWalk(repository).use { walk ->
            walk.isRetainBody = true
            for (tip in tips) {
                val obj = walk.peel(walk.parseAny(tip))
                if (obj is RevCommit) walk.markStart(walk.parseCommit(obj.id))
            }
            for (commit in walk) {
                commits += SourceCommit(
                    id = commit.id,
                    parents = commit.parents.map { it.id },
                    tree = commit.tree.id,
                    author = commit.authorIdent,
                    committer = commit.committerIdent,
                    message = commit.fullMessage,
                )
            }
        }
        return commits
    }

    /**
     * Feeds every tree and blob reachable from [tips] to [sink] — the *content* of the history, as
     * opposed to the commits, which the writer recreates rather than copies.
     *
     * These objects are what the output repository needs in order to stand on its own: the braid's
     * new root trees point straight at the original subtrees, so those subtrees and everything below
     * them have to be present. The loader is only valid for the duration of the call.
     */
    fun forEachContentObject(tips: Collection<ObjectId>, sink: (type: Int, loader: ObjectLoader) -> Unit) {
        ObjectWalk(repository).use { walk ->
            walk.isRetainBody = false
            for (tip in tips) {
                val obj = walk.peel(walk.parseAny(tip))
                if (obj is RevCommit) walk.markStart(walk.parseCommit(obj.id))
            }
            // The commit walk has to be drained first; only then does the walk hand out the trees
            // and blobs those commits reference.
            while (walk.next() != null) continue
            val objectReader = walk.objectReader
            var obj = walk.nextObject()
            while (obj != null) {
                sink(obj.type, objectReader.open(obj, obj.type))
                obj = walk.nextObject()
            }
        }
    }

    /**
     * The top-level `.gitmodules` of [tree] read as text, or `null` when the tree has none.
     *
     * Only a regular file counts. A `.gitmodules` stored as a symlink is one git itself refuses to
     * follow, and a tree of that name describes no submodule, so either is passed through as
     * ordinary content rather than being read as configuration.
     */
    fun gitmodules(tree: ObjectId): String? {
        val parser = CanonicalTreeParser(null, reader(), tree)
        while (!parser.eof()) {
            if (parser.entryPathString == Constants.DOT_GIT_MODULES &&
                (parser.entryFileMode == FileMode.REGULAR_FILE ||
                    parser.entryFileMode == FileMode.EXECUTABLE_FILE)
            ) {
                val bytes = reader().open(parser.entryObjectId, Constants.OBJ_BLOB).cachedBytes
                return String(bytes, Charsets.UTF_8)
            }
            parser.next()
        }
        return null
    }

    /** Top-level entries of [tree], in the order git stored them. */
    fun topLevelEntries(tree: ObjectId): List<TreeEntry> {
        val entries = ArrayList<TreeEntry>()
        val parser = CanonicalTreeParser(null, reader(), tree)
        while (!parser.eof()) {
            entries += TreeEntry(parser.entryPathString, parser.entryFileMode, parser.entryObjectId)
            parser.next()
        }
        return entries
    }

    override fun close() {
        reader?.close()
        repository.close()
    }

    private fun reader(): ObjectReader = reader ?: repository.newObjectReader().also { reader = it }

    companion object {

        /**
         * Opens the repository at [location] (bare or with a working tree).
         *
         * The repository has to be *at* [location]: either the path is itself a git directory, or
         * it holds a `.git`. A repository in some parent directory is deliberately not opened.
         * Inputs are named one by one on the command line, so walking up the tree could only ever
         * substitute an enclosing repository for the one that was asked for — silently, and under
         * the name of the path that was written.
         */
        fun open(location: Path, name: String = defaultName(location)): SourceRepository {
            val dir = location.toFile()
            val builder = FileRepositoryBuilder().setMustExist(true).readEnvironment()
            if (RepositoryCache.FileKey.isGitRepository(dir, FS.DETECTED)) {
                builder.setGitDir(dir)
            } else if (File(dir, Constants.DOT_GIT).exists()) {
                // `.git` is a directory in an ordinary working tree and a file pointing elsewhere in
                // a linked worktree or a submodule; findGitDir resolves both, which is why it is
                // still used here. Its first step examines `dir` itself, so a `.git` that is present
                // and usable settles it there; the ceiling stops it from climbing when that `.git`
                // turns out to be neither.
                dir.parentFile?.let { builder.addCeilingDirectory(it) }
                builder.findGitDir(dir)
            }
            require(builder.gitDir != null) { "no git repository at $location" }
            return SourceRepository(name, location, builder.build())
        }

        /**
         * The repository name implied by a path: the last segment of its absolute, normalized form,
         * without a trailing `.git`.
         *
         * Normalizing first is what lets `.` and `../sibling` be written as inputs and still name
         * the directory they land on rather than themselves. Dropping a final `.git` *segment* —
         * as opposed to the `.git` suffix of a bare `repo.git` — is what makes `repo/.git` name
         * `repo` instead of nothing at all. Empty only for a path with no segment to be named by,
         * the filesystem root; callers reject that rather than carrying a nameless repository.
         */
        fun defaultName(location: Path): String {
            val absolute = location.toAbsolutePath().normalize()
            val directory =
                if (absolute.fileName?.toString() == Constants.DOT_GIT) absolute.parent ?: absolute
                else absolute
            return directory.fileName?.toString()?.removeSuffix(".git") ?: ""
        }
    }
}

/** A named reference resolved to the object it points at. */
class GitRef(val name: String, val target: ObjectId)

/** A tag resolved to the commit it peels to, with its annotation when it has one. */
class TagRef(val name: String, val target: ObjectId, val annotation: TagAnnotation?)

/** The parts of an annotated tag object that survive retargeting. */
class TagAnnotation(val tagger: PersonIdent?, val message: String)

/**
 * One commit of an input repository, copied out of JGit's object model so the rest of the program
 * can stay clear of it. Holds everything the writer will need later — full message, both idents,
 * the root tree — even though the planner only reads [parents] and one timestamp.
 */
class SourceCommit(
    val id: ObjectId,
    /** Original parents, first parent first. */
    val parents: List<ObjectId>,
    /** Root tree of the original commit. */
    val tree: ObjectId,
    val author: PersonIdent,
    val committer: PersonIdent,
    val message: String,
) {
    /** The timestamp used to interleave the strands, in epoch seconds, per `--order-by`. */
    fun time(orderBy: OrderBy): Long {
        val ident = if (orderBy == OrderBy.AUTHOR) author else committer
        return ident.whenAsInstant.epochSecond
    }
}
