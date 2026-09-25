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
        assembler = RootTreeAssembler(inserter)
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
        // The pairs that catch a naive sort: '-' (0x2D) and '.' (0x2E) both precede '/' (0x2F), so
        // `a-` and `a.txt` each come before the directory `a`, where a plain name comparison would
        // put the directory first.
        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                TreeEntry("a-", FileMode.REGULAR_FILE, blob("b")),
                TreeEntry("b", FileMode.REGULAR_FILE, blob("c")),
            ),
            subdirEntries = listOf(TreeEntry("a", FileMode.TREE, emptyTree())),
            at = { "test" },
        )

        assertEquals(listOf("a-", "a.txt", "a", "b"), namesOf(tree))
    }

    @Test
    fun `a shorter name sorts before one that extends it`() {
        // Not the slash rule: `x` runs out before `x-dir/` does, so it wins on length whatever the
        // directory's key ends in. The pairs that turn on the slash are `a-` and `a.txt` against the
        // directory `a`, above. A file and a directory of one name never reach the sort together:
        // the assembler keys entries by name, so such a pair is refused as a collision, or, for a
        // root `.gitmodules`, replaced by the one the braid writes.
        val tree = assembler.assemble(
            rootEntries = listOf(TreeEntry("x", FileMode.REGULAR_FILE, blob("x"))),
            subdirEntries = listOf(TreeEntry("x-dir", FileMode.TREE, emptyTree())),
            at = { "test" },
        )
        assertEquals(listOf("x", "x-dir"), namesOf(tree))
    }

    @Test
    fun `identical entry sets are written once and yield the same tree`() {
        val entries = listOf(TreeEntry("ui", FileMode.TREE, emptyTree()))
        val first = assembler.assemble(emptyList(), entries, at = { "c1" })
        val second = assembler.assemble(emptyList(), entries, at = { "c2" })

        assertEquals(first, second)
        assertEquals(1, assembler.treesWritten)

        assembler.assemble(emptyList(), listOf(TreeEntry("api", FileMode.TREE, emptyTree())), at = { "c3" })
        assertEquals(2, assembler.treesWritten)
    }

    @Test
    fun `a subdirectory colliding with an entry of the root repository is a named error`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                rootEntries = listOf(TreeEntry("webui", FileMode.REGULAR_FILE, blob("a script"))),
                subdirEntries = listOf(TreeEntry("webui", FileMode.TREE, emptyTree())),
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
            subdirEntries = listOf(TreeEntry("A", FileMode.TREE, emptyTree())),
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
            subdirEntries = listOf(TreeEntry("A", FileMode.TREE, emptyTree())),
            at = { "test" },
        )

        assertEquals(original, gitmodulesId(tree))
    }

    @Test
    fun `a non-ASCII name round-trips as its UTF-8 bytes`() {
        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry("zebra", FileMode.REGULAR_FILE, blob("z")),
                TreeEntry("ěšč", FileMode.REGULAR_FILE, blob("e")),
            ),
            subdirEntries = emptyList(),
            at = { "test" },
        )
        // UTF-8 puts the multi-byte name last: 0xC4 is above every ASCII letter.
        assertEquals(listOf("zebra", "ěšč"), namesOf(tree))
        assertNotEquals(ObjectId.zeroId(), tree)
    }
}
