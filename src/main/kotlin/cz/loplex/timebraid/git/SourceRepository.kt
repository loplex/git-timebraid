package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import java.io.File
import java.nio.file.Path

/**
 * One input repository — one *strand* of the braid — opened for reading only.
 *
 * The class exists to keep every JGit type on this side of the boundary: it hands back plain data
 * ([GitRef], [PeeledRef], [SourceCommit], [TreeEntry]) and the planner never sees an [ObjectId], a
 * [RevWalk] or a [Repository]. Nothing here writes, and nothing here moves objects: the output pulls
 * them across itself, by fetching from [location] (see [TargetRepository.fetchFrom]).
 */
class SourceRepository private constructor(
    /**
     * Short name of the repository, and its identity in the output: the default subdirectory, and
     * wherever the output names or labels the input.
     */
    val name: String,
    /** Where the repository was opened from, for diagnostics. */
    val location: Path,
    private val repository: Repository,
) : AutoCloseable {

    /**
     * Kept open for the whole life of the handle: every tree and blob read goes through it. The
     * [RevWalk]s below are the exception, each opening a reader of its own and closing it with the
     * walk.
     */
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
     * annotated tag rather than silently flattening the tagger and the tag message away. A symbolic
     * ref here is read as a tag at what it points at, as 0.1.0 read it: a name someone gave a
     * commit.
     */
    fun tags(): List<PeeledRef> = refsUnder(Constants.R_TAGS)

    /**
     * Refs under [namespace], sorted, each named relative to it and peeled to the commit it
     * ultimately points at. A ref that does not resolve to a commit is dropped — it has no place on
     * a history graph.
     *
     * [tags] is this over `refs/tags/`, and it is the same operation over a namespace the program
     * knows nothing about: a Gerrit `refs/changes/`, a forge's `refs/pull/`, the branches an
     * ordinary clone keeps in `refs/remotes/origin/`. What those have in common is the only thing
     * that matters here — they name commits.
     *
     * A symbolic ref is skipped outside `refs/tags/`. It is a pointer at another ref rather than a
     * thing of its own, so carrying it over would write the same commit twice, once under a name
     * like `refs/remotes/origin/HEAD` that means nothing away from the repository it came from.
     */
    fun refsUnder(namespace: String): List<PeeledRef> {
        RevWalk(repository).use { walk ->
            walk.isRetainBody = true
            return repository.refDatabase.getRefsByPrefix(namespace)
                .sortedBy { it.name }
                .filterNot { it.isSymbolic && namespace != Constants.R_TAGS }
                .mapNotNull { ref ->
                    val pointee = walk.parseAny(ref.objectId)
                    val peeled = walk.peel(pointee)
                    if (peeled !is RevCommit) return@mapNotNull null
                    val annotation = (pointee as? RevTag)?.let {
                        TagAnnotation(it.taggerIdent, it.fullMessage)
                    }
                    PeeledRef(ref.name.removePrefix(namespace), peeled.id, annotation)
                }
        }
    }

    /**
     * Every notes ref (under `refs/notes/`), each read as the map it is: the sha of the object a
     * note is attached to, to the blob holding that note's text.
     *
     * A notes ref is a commit whose tree is keyed by the sha of what each note annotates, fanned out
     * into directories at whatever depth the writer chose (`ab/cdef…`, `ab/cd/ef…`, or none at all).
     * The fan-out is a storage detail rather than part of the key, so the path is rejoined here and
     * the caller sees one flat map. An entry whose rejoined path is not a sha is not a note — a
     * `.gitattributes` a user put there, or a directory that is not fan-out — and is dropped.
     *
     * The commit's own author, committer and message come along because the output writes a notes
     * commit of its own and has no business inventing either. Its *parents* do not: the trees behind
     * them are keyed by shas that mean nothing in the output, so the history of a notes ref is not
     * carried over.
     */
    fun notes(): List<NoteRef> {
        val refs = repository.refDatabase.getRefsByPrefix(Constants.R_NOTES).sortedBy { it.name }
        if (refs.isEmpty()) return emptyList()
        RevWalk(repository).use { walk ->
            walk.isRetainBody = true
            return refs.mapNotNull { ref ->
                val commit = walk.peel(walk.parseAny(ref.objectId)) as? RevCommit
                    ?: return@mapNotNull null
                val entries = LinkedHashMap<String, ObjectId>()
                TreeWalk(repository, reader()).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    while (tree.next()) {
                        val key = tree.pathString.replace("/", "")
                        if (isObjectSha(key)) entries[key] = tree.getObjectId(0)
                    }
                }
                NoteRef(
                    name = ref.name.removePrefix(Constants.R_NOTES),
                    entries = entries,
                    author = commit.authorIdent,
                    committer = commit.committerIdent,
                    message = commit.fullMessage,
                )
            }
        }
    }

    /**
     * Object a branch points at, or `null` if the repository has no such branch.
     *
     * The name is looked up as a ref rather than parsed as a revision, which would take `main~1` or
     * `main^` for an ancestor of `main`. A name JGit would not accept for a ref is no branch
     * either, and is not looked up at all: a `..` in it would walk out of `refs/heads/` to whatever
     * ref file it lands on.
     */
    fun resolveBranch(shortName: String): ObjectId? {
        val name = Constants.R_HEADS + shortName
        return if (Repository.isValidRefName(name)) repository.exactRef(name)?.objectId else null
    }

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
     * The top-level `.gitmodules` of [tree] read as text, or `null` when the tree has none.
     *
     * Only a regular file counts. A `.gitmodules` stored as a symlink is one git itself refuses to
     * follow, and a tree of that name describes no submodule, so either is passed through as
     * ordinary content rather than being read as configuration. At the output root it is dropped
     * wherever the braid writes a `.gitmodules` of its own there, and where a dissolve took the last
     * section the braid would have written.
     */
    fun gitmodules(tree: ObjectId): String? {
        val parser = CanonicalTreeParser(null, reader(), tree)
        while (!parser.eof()) {
            if (parser.entryPathString == Constants.DOT_GIT_MODULES &&
                (parser.entryFileMode == FileMode.REGULAR_FILE ||
                    parser.entryFileMode == FileMode.EXECUTABLE_FILE)
            ) {
                return String(blob(parser.entryObjectId), Charsets.UTF_8)
            }
            parser.next()
        }
        return null
    }

    /** The bytes of one blob. Git stores content as bytes, and nothing here decides it is text. */
    fun blob(id: ObjectId): ByteArray = reader().open(id, Constants.OBJ_BLOB).cachedBytes

    /**
     * Entries of [tree] itself, in the order git stored them — not recursed into.
     *
     * Called for a commit's own root tree, and for a subtree of it where a nested destination
     * reaches into a directory this repository already has.
     */
    fun entriesOf(tree: ObjectId): List<TreeEntry> {
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
         *
         * The environment is not consulted, for the same reason and one more. `readEnvironment()`
         * would let `GIT_DIR` stand in for every input that is not itself a git directory, a
         * working tree as much as a path that is no repository at all, and for the latter the
         * variable would satisfy the `require` below on its own: the input would open as whatever
         * `GIT_DIR` named — the exact substitution the paragraph above refuses to make by walking
         * up. `GIT_COMMON_DIR`, `GIT_OBJECT_DIRECTORY` and `GIT_ALTERNATE_OBJECT_DIRECTORIES` would
         * reach a bare input too, since nothing here overrides them, and read its refs or its
         * objects from another repository.
         *
         * The one more is that this program opens *several* repositories at once. A single
         * process-wide variable naming one repository or one object store cannot be right for a set
         * of inputs, however it is spelled — which is why the same variables are stripped from every
         * git subprocess ([GitCommand.dropRedirectingVariables]) rather than being honoured
         * anywhere.
         */
        fun open(location: Path, name: String = defaultName(location)): SourceRepository {
            val gitDir = requireNotNull(gitDirOf(location)) { "no git repository at $location" }
            val repository = FileRepositoryBuilder().setMustExist(true).setGitDir(gitDir).build()
            try {
                refuseIncomplete(repository, name, location)
            } catch (e: IllegalArgumentException) {
                repository.close()
                throw e
            }
            return SourceRepository(name, location, repository)
        }

        /**
         * Refuses a repository holding only part of its history, before anything is read from it.
         *
         * A shallow clone stops at commits whose parents it does not have, and JGit reads those as
         * roots: the graph looks whole, only smaller, and the history left out surfaces once the
         * output fetches it, as a missing object of whichever input comes next. A partial clone has
         * every commit but leaves objects out to be fetched on demand, which nothing here does.
         * Either would be braided as a different history from the one the repository stands for.
         *
         * JGit has no notion of a partial clone, so that half reads the config git writes for one:
         * `extensions.partialClone`, and a remote marked as a promisor.
         */
        private fun refuseIncomplete(repository: Repository, name: String, location: Path) {
            val shallow = repository.objectDatabase.shallowCommits
            require(shallow.isEmpty()) {
                val commits = if (shallow.size == 1) "1 commit" else "${shallow.size} commits"
                "'$name' at $location is a shallow clone: its history stops at $commits whose " +
                    "parents it does not have. Complete it with: git -C $location fetch --unshallow"
            }
            val config = repository.config
            val partial = config.getString("extensions", null, "partialClone") != null ||
                config.getSubsections("remote").any { remote ->
                    config.getBoolean("remote", remote, "promisor", false)
                }
            require(!partial) {
                "'$name' at $location is a partial clone, which leaves out objects to fetch on " +
                    "demand. Clone it again without --filter"
            }
        }

        /**
         * Whether [location] is itself a git repository — bare, or a working tree whose `.git`
         * resolves to one.
         *
         * A scan asks this of every directory it walks, so a `GIT_DIR` in the environment answering
         * yes for every one of them would be its own kind of wrong; [open] does not consult the
         * environment either, for reasons written out there.
         */
        fun isRepository(location: Path): Boolean = gitDirOf(location) != null

        /**
         * The git directory [location] is or holds, as [open] opens it, or `null` when it is
         * neither.
         *
         * The location itself when it is a git directory, else its `.git`: a directory in an
         * ordinary working tree, and a file naming one elsewhere in a linked worktree or a
         * submodule. findGitDir resolves both, which is why it is still used here. Its first step
         * examines the location itself, so a `.git` that is present and usable settles it there;
         * the ceiling stops it from climbing when that `.git` turns out to be neither. Nothing is
         * guessed beside the location, and the path is not normalized: a `..` past a symlink is the
         * filesystem's.
         */
        fun gitDirOf(location: Path): File? {
            val dir = location.toAbsolutePath().toFile()
            if (RepositoryCache.FileKey.isGitRepository(dir, FS.DETECTED)) return dir
            if (!File(dir, Constants.DOT_GIT).exists()) return null
            val builder = FileRepositoryBuilder()
            dir.parentFile?.let { builder.addCeilingDirectory(it) }
            return builder.findGitDir(dir).gitDir
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

        /** Whether [text] is spelled the way git writes an object id in a notes tree path. */
        private fun isObjectSha(text: String): Boolean =
            text.length == Constants.OBJECT_ID_STRING_LENGTH &&
                text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** One notes ref: what it is called, what it holds, and who last wrote it. */
class NoteRef(
    /** The ref's name below `refs/notes/` — `commits` for the one `git notes` writes by default. */
    val name: String,
    /** Sha of the annotated object, to the blob holding its note. */
    val entries: Map<String, ObjectId>,
    val author: PersonIdent,
    val committer: PersonIdent,
    val message: String,
)

/** A named reference resolved to the object it points at. */
class GitRef(val name: String, val target: ObjectId)

/** A ref resolved to the commit it peels to, with its annotation where it has one. */
class PeeledRef(val name: String, val target: ObjectId, val annotation: TagAnnotation?)

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
