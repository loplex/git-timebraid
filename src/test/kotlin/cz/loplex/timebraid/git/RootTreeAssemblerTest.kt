package cz.loplex.timebraid.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.nio.file.Path

class RootTreeAssemblerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var repo: TestRepoBuilder
    private lateinit var inserter: ObjectInserter
    private lateinit var assembler: RootTreeAssembler

    @BeforeEach
    fun open() {
        repo = TestRepoBuilder.create(tmp.resolve("r.git"))
        inserter = repo.repository.newObjectInserter()
        assembler = RootTreeAssembler(inserter, ::entriesOf)
    }

    @AfterEach
    fun close() {
        inserter.close()
        repo.close()
    }

    private fun blob(content: String): ObjectId = inserter.insert(Constants.OBJ_BLOB, content.toByteArray())

    private fun emptyTree(): ObjectId = inserter.insert(TreeFormatter())

    private fun gitmodulesId(tree: ObjectId): ObjectId {
        inserter.flush()
        repo.repository.newObjectReader().use { reader ->
            val parser = CanonicalTreeParser(null, reader, tree)
            while (!parser.eof()) {
                if (parser.entryPathString == Constants.DOT_GIT_MODULES) return parser.entryObjectId
                parser.next()
            }
            error("no '${Constants.DOT_GIT_MODULES}' in the tree")
        }
    }

    /** Stands in for the root repository's reader — see [RootTreeAssembler]. */
    private fun entriesOf(tree: ObjectId): List<TreeEntry> {
        inserter.flush()
        repo.repository.newObjectReader().use { reader ->
            val parser = CanonicalTreeParser(null, reader, tree)
            val entries = ArrayList<TreeEntry>()
            while (!parser.eof()) {
                entries += TreeEntry(parser.entryPathString, parser.entryFileMode, parser.entryObjectId)
                parser.next()
            }
            return entries
        }
    }

    /** A tree of the given entries, as the root repository would hold it. */
    private fun treeOf(vararg entries: TreeEntry): ObjectId {
        val formatter = TreeFormatter(entries.size)
        for (entry in entries.sortedWith(RootTreeAssembler.GIT_TREE_ORDER)) {
            formatter.append(entry.name, entry.mode, entry.id)
        }
        return inserter.insert(formatter)
    }

    private fun placement(subdir: String, tree: ObjectId) = Placement(subdir, tree)

    /** Every path below [tree], depth first, so a nested placement can be asserted whole. */
    private fun pathsOf(tree: ObjectId, prefix: String = ""): List<String> =
        entriesOf(tree).flatMap { entry ->
            val path = prefix + entry.name
            if (entry.mode == FileMode.TREE) listOf(path) + pathsOf(entry.id, "$path/")
            else listOf(path)
        }

    private fun namesOf(tree: ObjectId): List<String> {
        inserter.flush()
        repo.repository.newObjectReader().use { reader ->
            val parser = CanonicalTreeParser(null, reader, tree)
            val names = ArrayList<String>()
            while (!parser.eof()) {
                names += parser.entryPathString
                parser.next()
            }
            return names
        }
    }

    @Test
    fun `entries come out in git's order, where a directory sorts as if it ended in a slash`() {
        // The pair that catches a naive sort: '.' (0x2E) precedes '/' (0x2F), so the file wins,
        // but a plain name comparison would put the directory first.
        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                TreeEntry("a-", FileMode.REGULAR_FILE, blob("b")),
                TreeEntry("b", FileMode.REGULAR_FILE, blob("c")),
            ),
            placements = listOf(placement("a", emptyTree())),
            at = { "test" },
        )

        assertEquals(listOf("a-", "a.txt", "a", "b"), namesOf(tree))
    }

    @Test
    fun `a file and a directory of the same name sort file first`() {
        val tree = assembler.assemble(
            rootEntries = listOf(TreeEntry("x", FileMode.REGULAR_FILE, blob("x"))),
            placements = listOf(placement("x-dir", emptyTree())),
            at = { "test" },
        )
        assertEquals(listOf("x", "x-dir"), namesOf(tree))
    }

    @Test
    fun `identical entry sets are written once and yield the same tree`() {
        val entries = listOf(placement("ui", emptyTree()))
        val first = assembler.assemble(emptyList(), entries, at = { "c1" })
        val second = assembler.assemble(emptyList(), entries, at = { "c2" })

        assertEquals(first, second)
        assertEquals(1, assembler.treesWritten)

        assembler.assemble(emptyList(), listOf(placement("api", emptyTree())), at = { "c3" })
        assertEquals(2, assembler.treesWritten)
    }

    @Test
    fun `a subdirectory colliding with an entry of the root repository is a named error`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                rootEntries = listOf(TreeEntry("webui", FileMode.REGULAR_FILE, blob("a script"))),
                placements = listOf(placement("webui", emptyTree())),
                at = { "backend/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'webui'"), error.message)
        assertTrue(error.message!!.contains("backend/abc123"), error.message)
    }

    @Test
    fun `the synthesized gitmodules replaces the root repository's own, rather than colliding`() {
        val original = blob("[submodule \"lib\"]\n")
        val wired = blob("[submodule \"A/lib\"]\n")

        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry(".gitmodules", FileMode.REGULAR_FILE, original),
                TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
            ),
            placements = listOf(placement("A", emptyTree())),
            gitmodules = wired,
            at = { "test" },
        )

        assertEquals(listOf(".gitmodules", "A", "a.txt"), namesOf(tree))
        assertEquals(wired, gitmodulesId(tree))
    }

    @Test
    fun `no gitmodules leaves the root repository's own entry exactly where it was`() {
        val original = blob("# only a comment\n")

        val tree = assembler.assemble(
            rootEntries = listOf(TreeEntry(".gitmodules", FileMode.REGULAR_FILE, original)),
            placements = listOf(placement("A", emptyTree())),
            at = { "test" },
        )

        assertEquals(original, gitmodulesId(tree))
    }

    @Test
    fun `a nested destination becomes one tree per segment`() {
        val tree = assembler.assemble(
            rootEntries = emptyList(),
            placements = listOf(placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a"))))),
            at = { "test" },
        )

        assertEquals(listOf("libs"), namesOf(tree))
        assertEquals(listOf("libs", "libs/backend", "libs/backend/a.txt"), pathsOf(tree))
    }

    @Test
    fun `two inputs under one prefix share the tree for it`() {
        val tree = assembler.assemble(
            rootEntries = emptyList(),
            placements = listOf(
                placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")))),
                placement("libs/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
            ),
            at = { "test" },
        )

        assertEquals(
            listOf("libs", "libs/backend", "libs/backend/a.txt", "libs/webui", "libs/webui/b.txt"),
            pathsOf(tree),
        )
        // The root tree, the shared 'libs', and nothing else: both inputs' own trees are entries.
        assertEquals(2, assembler.treesWritten)
    }

    @Test
    fun `a nested destination is spliced into a directory the root repository already has`() {
        val existing = treeOf(TreeEntry("shared.txt", FileMode.REGULAR_FILE, blob("theirs")))

        val tree = assembler.assemble(
            rootEntries = listOf(TreeEntry("libs", FileMode.TREE, existing)),
            placements = listOf(placement("libs/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b"))))),
            at = { "test" },
        )

        // The root repository's own file stays where it was, beside the input placed next to it.
        assertEquals(listOf("libs", "libs/shared.txt", "libs/webui", "libs/webui/b.txt"), pathsOf(tree))
    }

    @Test
    fun `a nested destination reaching into a file of the root repository is a named error`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                rootEntries = listOf(TreeEntry("libs", FileMode.REGULAR_FILE, blob("a stray file"))),
                placements = listOf(placement("libs/webui", emptyTree())),
                at = { "webui/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'libs'"), error.message)
        assertTrue(error.message!!.contains("webui/abc123"), error.message)
    }

    @Test
    fun `a destination landing on a directory of the root repository is a named error`() {
        // Unlike the segments above it, the last one cannot be spliced: the entry written there is
        // the input's own tree object, so there is no room beside it.
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                rootEntries = listOf(
                    TreeEntry("libs", FileMode.TREE, treeOf(TreeEntry("webui", FileMode.TREE, emptyTree()))),
                ),
                placements = listOf(placement("libs/webui", emptyTree())),
                at = { "webui/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'libs/webui'"), error.message)
    }

    @Test
    fun `one destination containing another is refused rather than silently dropped`() {
        // The planner rejects this pair before the assembler sees it; this is the backstop.
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                rootEntries = emptyList(),
                placements = listOf(placement("libs", emptyTree()), placement("libs/webui", emptyTree())),
                at = { "webui/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'libs'"), error.message)
    }

    @Test
    fun `an unchanged prefix is written once however many commits stand on it`() {
        val backend = placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a"))))
        val webui = placement("apps/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b"))))

        assembler.assemble(emptyList(), listOf(backend, webui), at = { "c1" })
        val written = assembler.treesWritten
        // A second commit where only 'apps' moves reuses the 'libs' tree of the first.
        assembler.assemble(
            emptyList(),
            listOf(backend, placement("apps/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b2"))))),
            at = { "c2" },
        )

        assertEquals(3, written, "expected the root tree plus one per prefix")
        assertEquals(written + 2, assembler.treesWritten, "only 'apps' and the root tree changed")
    }

    @Test
    fun `a non-ASCII name round-trips as its UTF-8 bytes`() {
        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry("zebra", FileMode.REGULAR_FILE, blob("z")),
                TreeEntry("ěšč", FileMode.REGULAR_FILE, blob("e")),
            ),
            placements = emptyList(),
            at = { "test" },
        )
        // UTF-8 puts the multi-byte name last: 0xC4 is above every ASCII letter.
        assertEquals(listOf("zebra", "ěšč"), namesOf(tree))
        assertNotEquals(ObjectId.zeroId(), tree)
    }
}
