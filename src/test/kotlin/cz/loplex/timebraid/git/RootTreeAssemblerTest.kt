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
            subdirEntries = listOf(TreeEntry("a", FileMode.TREE, emptyTree())),
            at = { "test" },
        )

        assertEquals(listOf("a-", "a.txt", "a", "b"), namesOf(tree))
    }

    @Test
    fun `a file and a directory of the same name sort file first`() {
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
