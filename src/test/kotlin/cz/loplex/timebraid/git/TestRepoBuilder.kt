package cz.loplex.timebraid.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.lib.TreeFormatter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/**
 * Builds a real on-disk git repository object by object, with fixed idents and a controlled clock so
 * the resulting shas are deterministic — the fixture generator the integration tests are written
 * against.
 *
 * Two properties matter for those tests:
 *
 * - **Deterministic shas.** Every ident is the same `Tester <tester@example.com>`, in UTC unless a
 *   test gives a zone, and the clock only moves when a test says so, so a fixture produces
 *   byte-identical objects on every run and on every machine. That is what lets an integration test
 *   assert an exact output sha.
 * - **Awkward trees on demand.** A file path may contain `/`, so a fixture can put a blob inside a
 *   subdirectory; content is written verbatim, so a fixture can carry `CRLF`, NUL bytes or a
 *   non-ASCII name. A path may also be a *gitlink* rather than a blob, which is how a fixture
 *   carries a submodule. Tree entries are ordered the way git orders them
 *   ([RootTreeAssembler.GIT_TREE_ORDER]), which is the only ordering `git fsck` accepts.
 */
class TestRepoBuilder private constructor(private val git: Git) : AutoCloseable {

    val repository: Repository get() = git.repository

    private var clock = Instant.parse("2021-01-01T00:00:00Z")

    /** Author/committer of the next commit, before it is advanced. */
    private fun who(at: Instant, zone: ZoneOffset = ZoneOffset.UTC) =
        PersonIdent("Tester", "tester@example.com", at, zone)

    /**
     * Creates a commit and returns its id. The clock advances one hour per commit unless [at] is
     * given explicitly, which is how a test writes a history whose timestamps run backwards.
     * [authorAt] overrides only the author date, so a test can reproduce a rebased commit whose
     * author and committer dates disagree; [zone] is the offset both are written in.
     *
     * [files] keys are slash-separated paths: `mapOf("src/App.kt" to "…")` builds the `src` tree.
     * [gitlinks] puts a submodule entry at a path instead of a blob, pointing at a commit that need
     * not exist in this repository — which is the situation in every real superproject.
     */
    fun commit(
        message: String,
        parents: List<ObjectId> = emptyList(),
        files: Map<String, String> = mapOf("f" to message),
        gitlinks: Map<String, ObjectId> = emptyMap(),
        at: Instant? = null,
        authorAt: Instant? = null,
        zone: ZoneOffset = ZoneOffset.UTC,
    ): ObjectId = commitBytes(
        message,
        parents,
        files.mapValues { it.value.toByteArray(StandardCharsets.UTF_8) },
        gitlinks,
        at,
        authorAt,
        zone,
    )

    /** Like [commit], but the file contents are given as raw bytes — for CRLF, NUL or binary blobs. */
    fun commitBytes(
        message: String,
        parents: List<ObjectId> = emptyList(),
        files: Map<String, ByteArray>,
        gitlinks: Map<String, ObjectId> = emptyMap(),
        at: Instant? = null,
        authorAt: Instant? = null,
        zone: ZoneOffset = ZoneOffset.UTC,
    ): ObjectId {
        val leaves = LinkedHashMap<String, Leaf>()
        for ((path, bytes) in files) leaves[path] = Leaf.Blob(bytes)
        for ((path, commit) in gitlinks) leaves[path] = Leaf.Gitlink(commit)

        val stamp = at ?: clock.also { clock = clock.plusSeconds(3600) }
        repository.newObjectInserter().use { inserter ->
            val builder = CommitBuilder().apply {
                // JGit pairs getTreeId(): ObjectId with setTreeId(AnyObjectId), so Kotlin sees a
                // read-only property plus a setter method: the property-access syntax the inspection
                // suggests does not compile.
                @Suppress("UsePropertyAccessSyntax")
                setTreeId(writeTree(inserter, leaves))
                setParentIds(parents)
                author = who(authorAt ?: stamp, zone)
                committer = who(stamp, zone)
                setMessage(message)
            }
            val id = inserter.insert(builder)
            inserter.flush()
            return id
        }
    }

    /** One leaf of a fixture tree: content to store, or a commit to point a gitlink at. */
    private sealed class Leaf {
        class Blob(val bytes: ByteArray) : Leaf()
        class Gitlink(val commit: ObjectId) : Leaf()
    }

    /** Turns a path -> leaf map into a (possibly nested) tree and returns its id. */
    private fun writeTree(inserter: ObjectInserter, leaves: Map<String, Leaf>): ObjectId {
        val here = LinkedHashMap<String, Leaf>()
        val subdirs = LinkedHashMap<String, MutableMap<String, Leaf>>()
        for ((path, leaf) in leaves) {
            val slash = path.indexOf('/')
            if (slash < 0) {
                here[path] = leaf
            } else {
                subdirs.getOrPut(path.substring(0, slash)) { LinkedHashMap() }[path.substring(slash + 1)] = leaf
            }
        }

        val entries = ArrayList<TreeEntry>()
        for ((name, leaf) in here) {
            entries += when (leaf) {
                is Leaf.Blob ->
                    TreeEntry(name, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, leaf.bytes))
                is Leaf.Gitlink -> TreeEntry(name, FileMode.GITLINK, leaf.commit)
            }
        }
        for ((name, nested) in subdirs) {
            entries += TreeEntry(name, FileMode.TREE, writeTree(inserter, nested))
        }
        entries.sortWith(RootTreeAssembler.GIT_TREE_ORDER)

        val tree = TreeFormatter(entries.size)
        for (entry in entries) tree.append(entry.name, entry.mode, entry.id)
        return inserter.insert(tree)
    }

    fun branch(shortName: String, target: ObjectId) = point(Constants.R_HEADS + shortName, target)

    fun lightweightTag(shortName: String, target: ObjectId) = point(Constants.R_TAGS + shortName, target)

    /** Creates an annotated tag, its tagger in [zone], and points `refs/tags/<shortName>` at it. */
    fun annotatedTag(
        shortName: String,
        target: ObjectId,
        message: String = shortName,
        zone: ZoneOffset = ZoneOffset.UTC,
    ): ObjectId {
        repository.newObjectInserter().use { inserter ->
            val builder = TagBuilder().apply {
                setObjectId(target, Constants.OBJ_COMMIT)
                tag = shortName
                tagger = who(clock, zone)
                setMessage(message)
            }
            val id = inserter.insert(builder)
            inserter.flush()
            point(Constants.R_TAGS + shortName, id)
            return id
        }
    }

    private fun point(ref: String, target: ObjectId) {
        repository.updateRef(ref).apply {
            // getNewObjectId(): ObjectId against setNewObjectId(AnyObjectId) — same asymmetry as
            // above: assigning through the property does not compile.
            @Suppress("UsePropertyAccessSyntax")
            setNewObjectId(target)
            isForceUpdate = true
            update()
        }
    }

    override fun close() = git.close()

    companion object {

        /** Reopens a repository built earlier, so a test can add refs to it afterwards. */
        fun open(dir: Path): TestRepoBuilder = TestRepoBuilder(Git.open(dir.toFile()))

        fun create(dir: Path, bare: Boolean = true, initialBranch: String = "main"): TestRepoBuilder {
            val git = Git.init()
                .setDirectory(dir.toFile())
                .setBare(bare)
                .setInitialBranch(initialBranch)
                .call()
            return TestRepoBuilder(git)
        }
    }
}
