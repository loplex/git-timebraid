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
    fun `a gitlink sorts by its plain name, not as a directory`() {
        // A submodule is a directory on disk and not in the tree: given the slash, `sub` would
        // follow `sub.txt` the way the directory `a` follows `a.txt` above.
        val tree = assembler.assemble(
            rootEntries = listOf(
                TreeEntry("sub", FileMode.GITLINK, ObjectId.fromString("1".repeat(40))),
                TreeEntry("sub.txt", FileMode.REGULAR_FILE, blob("s")),
            ),
            subdirEntries = emptyList(),
            at = { "test" },
        )

        assertEquals(listOf("sub", "sub.txt"), namesOf(tree))
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

        // The mode is part of what makes two sets identical: the same blob as a plain file and as
        // an executable one is two different trees.
        val script = blob("#!/bin/sh")
        val plain = assembler.assemble(listOf(TreeEntry("run.sh", FileMode.REGULAR_FILE, script)), emptyList(), at = { "c4" })
        val executable =
            assembler.assemble(listOf(TreeEntry("run.sh", FileMode.EXECUTABLE_FILE, script)), emptyList(), at = { "c5" })
        assertNotEquals(plain, executable)
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
                TreeEntry("\uD83D\uDE00", FileMode.REGULAR_FILE, blob("smile")),
                TreeEntry("\uFF01", FileMode.REGULAR_FILE, blob("bang")),
            ),
            subdirEntries = emptyList(),
            at = { "test" },
        )
        // UTF-8 puts the multi-byte names last: 0xC4 is above every ASCII letter. The last two are
        // the pair on which UTF-8 and a Kotlin string disagree: U+FF01 is EF BC 81 against the
        // emoji's F0 9F 98 80, while in UTF-16 the emoji's surrogate 0xD83D comes before 0xFF01.
        assertEquals(listOf("zebra", "ěšč", "\uFF01", "\uD83D\uDE00"), namesOf(tree))
        assertNotEquals(ObjectId.zeroId(), tree)
    }
}
