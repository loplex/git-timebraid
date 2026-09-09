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

class TreeAssemblerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var repo: TestRepoBuilder
    private lateinit var inserter: ObjectInserter
    private lateinit var assembler: TreeAssembler

    @BeforeEach
    fun open() {
        repo = TestRepoBuilder.create(tmp.resolve("r.git"))
        inserter = repo.repository.newObjectInserter()
        assembler = TreeAssembler(inserter)
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

    /** Stands in for an input repository's reader — see [Placement.entriesOf]. */
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

    /** A tree of the given entries, as an input repository would hold it. */
    private fun treeOf(vararg entries: TreeEntry): ObjectId {
        val formatter = TreeFormatter(entries.size)
        for (entry in entries.sortedWith(TreeAssembler.GIT_TREE_ORDER)) {
            formatter.append(entry.name, entry.mode, entry.id)
        }
        return inserter.insert(formatter)
    }

    private fun placement(subdir: String, tree: ObjectId) = Placement(subdir, tree, ::entriesOf)

    /** An assembler that lets an input land on a gitlink instead of colliding with it. */
    private fun dissolving() = TreeAssembler(inserter, dissolveSubmodules = true)

    /** The sha a superproject pinned its submodule at. Nothing here has to be able to resolve it. */
    private val pinned: ObjectId = ObjectId.fromString("06df2481b3f0ad0e5d6d0f04ac4b5f0e0eaa1234")

    /** The repository placed at the output root, holding the given entries at its top level. */
    private fun root(vararg entries: TreeEntry) = Placement(null, treeOf(*entries), ::entriesOf)

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
        // The pairs that catch a naive sort: '-' (0x2D) and '.' (0x2E) both precede '/' (0x2F), so
        // `a-` and `a.txt` each come before the directory `a`, where a plain name comparison would
        // put the directory first.
        val tree = assembler.assemble(
            listOf(
                root(
                    TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                    TreeEntry("a-", FileMode.REGULAR_FILE, blob("b")),
                    TreeEntry("b", FileMode.REGULAR_FILE, blob("c")),
                ),
                placement("a", emptyTree()),
            ),
            at = { "test" },
        )

        assertEquals(listOf("a-", "a.txt", "a", "b"), namesOf(tree))
    }

    @Test
    fun `a gitlink sorts by its plain name, not as a directory`() {
        // A submodule is a directory on disk and not in the tree: given the slash, `sub` would
        // follow `sub.txt` the way the directory `a` follows `a.txt` above.
        val tree = assembler.assemble(
            listOf(
                root(
                    TreeEntry("sub", FileMode.GITLINK, ObjectId.fromString("1".repeat(40))),
                    TreeEntry("sub.txt", FileMode.REGULAR_FILE, blob("s")),
                ),
            ),
            at = { "test" },
        )

        assertEquals(listOf("sub", "sub.txt"), namesOf(tree))
    }

    @Test
    fun `a shorter name sorts before one that extends it`() {
        // Not the slash rule: `x` runs out before `x-dir/` does, so it wins on length whatever the
        // directory's key ends in. The pairs that turn on the slash are `a-` and `a.txt` against the
        // directory `a`, above. A file and a directory of one name never reach the sort together:
        // the assembler keys entries by name, so such a pair is refused, as a collision where an
        // input lands on the entry and as not a directory where one is placed inside it, or, for a
        // root `.gitmodules`, replaced by the one the braid writes.
        val tree = assembler.assemble(
            listOf(
                root(TreeEntry("x", FileMode.REGULAR_FILE, blob("x"))),
                placement("x-dir", emptyTree()),
            ),
            at = { "test" },
        )
        assertEquals(listOf("x", "x-dir"), namesOf(tree))
    }

    @Test
    fun `identical entry sets are written once and yield the same tree`() {
        val entries = listOf(placement("ui", emptyTree()))
        val first = assembler.assemble(entries, at = { "c1" })
        val second = assembler.assemble(entries, at = { "c2" })

        assertEquals(first, second)
        assertEquals(1, assembler.treesWritten)

        assembler.assemble(listOf(placement("api", emptyTree())), at = { "c3" })
        assertEquals(2, assembler.treesWritten)

        // The mode is part of what makes two sets identical: the same blob as a plain file and as
        // an executable one is two different trees.
        val script = blob("#!/bin/sh")
        val plain = assembler.assemble(listOf(root(TreeEntry("run.sh", FileMode.REGULAR_FILE, script))), at = { "c4" })
        val executable =
            assembler.assemble(listOf(root(TreeEntry("run.sh", FileMode.EXECUTABLE_FILE, script))), at = { "c5" })
        assertNotEquals(plain, executable)
    }

    @Test
    fun `a subdirectory colliding with an entry of the root repository is a named error`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    root(TreeEntry("webui", FileMode.REGULAR_FILE, blob("a script"))),
                    placement("webui", emptyTree()),
                ),
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
            listOf(
                root(
                    TreeEntry(".gitmodules", FileMode.REGULAR_FILE, original),
                    TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                ),
                placement("A", emptyTree()),
            ),
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
            listOf(
                root(TreeEntry(".gitmodules", FileMode.REGULAR_FILE, original)),
                placement("A", emptyTree()),
            ),
            at = { "test" },
        )

        assertEquals(original, gitmodulesId(tree))
    }

    @Test
    fun `a nested destination becomes one tree per segment`() {
        val tree = assembler.assemble(
            listOf(placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a"))))),
            at = { "test" },
        )

        assertEquals(listOf("libs"), namesOf(tree))
        assertEquals(listOf("libs", "libs/backend", "libs/backend/a.txt"), pathsOf(tree))
    }

    @Test
    fun `two inputs under one prefix share the tree for it`() {
        val tree = assembler.assemble(
            listOf(
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
        val tree = assembler.assemble(
            listOf(
                root(
                    TreeEntry(
                        "libs",
                        FileMode.TREE,
                        treeOf(TreeEntry("shared.txt", FileMode.REGULAR_FILE, blob("theirs"))),
                    ),
                ),
                placement("libs/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
            ),
            at = { "test" },
        )

        // The root repository's own file stays where it was, beside the input placed next to it.
        assertEquals(listOf("libs", "libs/shared.txt", "libs/webui", "libs/webui/b.txt"), pathsOf(tree))
    }

    @Test
    fun `a nested destination reaching into a file of the root repository is a named error`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    root(TreeEntry("libs", FileMode.REGULAR_FILE, blob("a stray file"))),
                    placement("libs/webui", emptyTree()),
                ),
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
                listOf(
                    root(
                        TreeEntry(
                            "libs",
                            FileMode.TREE,
                            treeOf(TreeEntry("webui", FileMode.TREE, emptyTree())),
                        ),
                    ),
                    placement("libs/webui", emptyTree()),
                ),
                at = { "webui/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'libs/webui'"), error.message)
    }

    @Test
    fun `a destination inside another is spliced the way one inside the root repository is`() {
        // What the root repository gets for free — its content read into entries so an input can
        // be placed beside it — is not special to the root: any containing input is spliced the
        // same way. Whether the pair is allowed at all is the planner's decision, not this class's.
        val tree = assembler.assemble(
            listOf(
                placement(
                    "libs",
                    treeOf(
                        TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                        TreeEntry("docs", FileMode.TREE, treeOf(TreeEntry("d.txt", FileMode.REGULAR_FILE, blob("d")))),
                    ),
                ),
                placement("libs/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
            ),
            at = { "test" },
        )

        assertEquals(
            listOf("libs", "libs/a.txt", "libs/docs", "libs/docs/d.txt", "libs/webui", "libs/webui/b.txt"),
            pathsOf(tree),
        )
    }

    @Test
    fun `a destination inside another still collides with what that repository holds there`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    placement("libs", treeOf(TreeEntry("webui", FileMode.REGULAR_FILE, blob("a stray file")))),
                    placement("libs/webui", emptyTree()),
                ),
                at = { "webui/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'libs/webui'"), error.message)
        assertTrue(error.message!!.contains("webui/abc123"), error.message)
    }

    @Test
    fun `a destination with another inside it still collides with what the root repository holds there`() {
        // The landing that gets spliced open is checked like any other: the root repository's own
        // libs/ is an entry of the landing's name, and an input placed at libs may not replace it.
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    root(TreeEntry("libs", FileMode.TREE, treeOf(TreeEntry("own.txt", FileMode.REGULAR_FILE, blob("o"))))),
                    placement("libs", emptyTree()),
                    placement("libs/webui", emptyTree()),
                ),
                at = { "libs/abc123" },
            )
        }
        assertTrue(error.message!!.contains("subdirectory 'libs' collides"), error.message)
        assertTrue(error.message!!.contains("libs/abc123"), error.message)
    }

    @Test
    fun `a gitlink at an input's destination collides until the run asks to dissolve it`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    root(TreeEntry("vendor", FileMode.TREE, treeOf(TreeEntry("lib", FileMode.GITLINK, pinned)))),
                    placement("vendor/lib", emptyTree()),
                ),
                at = { "backend/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'vendor/lib'"), error.message)
        // The remedy is named, because a gitlink here is the one collision that has a second one.
        assertTrue(error.message!!.contains("--dissolve-submodules"), error.message)
    }

    @Test
    fun `a gitlink at a destination with another inside it collides until the run asks to dissolve it`() {
        // The landing that gets spliced open is held to the same rule as one that does not.
        val inputs = listOf(
            root(TreeEntry("libs", FileMode.GITLINK, pinned)),
            placement("libs", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")))),
            placement("libs/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
        )

        val error = assertThrows<IllegalArgumentException> { assembler.assemble(inputs, at = { "libs/abc123" }) }
        assertTrue(error.message!!.contains("'libs'"), error.message)
        assertTrue(error.message!!.contains("--dissolve-submodules"), error.message)

        val tree = dissolving().assemble(inputs, at = { "libs/abc123" })
        assertEquals(listOf("libs", "libs/a.txt", "libs/webui", "libs/webui/b.txt"), pathsOf(tree))
    }

    @Test
    fun `dissolving puts the input's own tree where the gitlink was`() {
        val tree = dissolving().assemble(
            listOf(
                root(
                    TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")),
                    TreeEntry("vendor", FileMode.TREE, treeOf(TreeEntry("lib", FileMode.GITLINK, pinned))),
                ),
                placement("vendor/lib", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
            ),
            at = { "backend/abc123" },
        )

        assertEquals(listOf("a.txt", "vendor", "vendor/lib", "vendor/lib/b.txt"), pathsOf(tree))
        val lib = entriesOf(entriesOf(tree).first { it.name == "vendor" }.id).single()
        assertEquals(FileMode.TREE, lib.mode, "the gitlink is gone, not merely pointed elsewhere")
    }

    @Test
    fun `dissolving reaches only the destination, not a gitlink on the way to it`() {
        // 'vendor' is a submodule and 'vendor/lib' is a path inside it, which is a path this braid
        // never writes: there is no input at 'vendor' whose content could take the submodule's place.
        val error = assertThrows<IllegalArgumentException> {
            dissolving().assemble(
                listOf(
                    root(TreeEntry("vendor", FileMode.GITLINK, pinned)),
                    placement("vendor/lib", emptyTree()),
                ),
                at = { "backend/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'vendor' is not a directory"), error.message)
    }

    @Test
    fun `dissolving does not excuse an ordinary entry in the way`() {
        val error = assertThrows<IllegalArgumentException> {
            dissolving().assemble(
                listOf(
                    root(TreeEntry("vendor", FileMode.TREE, treeOf(TreeEntry("lib", FileMode.REGULAR_FILE, blob("x"))))),
                    placement("vendor/lib", emptyTree()),
                ),
                at = { "backend/abc123" },
            )
        }
        assertTrue(error.message!!.contains("'vendor/lib'"), error.message)
        assertTrue(!error.message!!.contains("--dissolve-submodules"), error.message)
    }

    @Test
    fun `two repositories at the output root are refused rather than one of them dropped`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(root(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a"))), root()),
                at = { "test" },
            )
        }
        assertTrue(error.message!!.contains("output root"), error.message)
    }

    @Test
    fun `two repositories at one destination are refused rather than one of them dropped`() {
        val error = assertThrows<IllegalArgumentException> {
            assembler.assemble(
                listOf(
                    placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a")))),
                    placement("libs/backend", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b")))),
                ),
                at = { "test" },
            )
        }
        assertTrue(error.message!!.contains("libs/backend"), error.message)
    }

    @Test
    fun `an unchanged prefix is written once however many commits stand on it`() {
        val backend = placement("libs/backend", treeOf(TreeEntry("a.txt", FileMode.REGULAR_FILE, blob("a"))))
        val webui = placement("apps/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b"))))

        assembler.assemble(listOf(backend, webui), at = { "c1" })
        val written = assembler.treesWritten
        // A second commit where only 'apps' moves reuses the 'libs' tree of the first.
        assembler.assemble(
            listOf(backend, placement("apps/webui", treeOf(TreeEntry("b.txt", FileMode.REGULAR_FILE, blob("b2"))))),
            at = { "c2" },
        )

        assertEquals(3, written, "expected the root tree plus one per prefix")
        assertEquals(written + 2, assembler.treesWritten, "only 'apps' and the root tree changed")
    }

    @Test
    fun `a non-ASCII name round-trips as its UTF-8 bytes`() {
        val tree = assembler.assemble(
            listOf(
                root(
                    TreeEntry("zebra", FileMode.REGULAR_FILE, blob("z")),
                    TreeEntry("ěšč", FileMode.REGULAR_FILE, blob("e")),
                    TreeEntry("\uD83D\uDE00", FileMode.REGULAR_FILE, blob("smile")),
                    TreeEntry("\uFF01", FileMode.REGULAR_FILE, blob("bang")),
                ),
            ),
            at = { "test" },
        )
        // UTF-8 puts the multi-byte names last: 0xC4 is above every ASCII letter. The last two are
        // the pair on which UTF-8 and a Kotlin string disagree: U+FF01 is EF BC 81 against the
        // emoji's F0 9F 98 80, while in UTF-16 the emoji's surrogate 0xD83D comes before 0xFF01.
        assertEquals(listOf("zebra", "ěšč", "\uFF01", "\uD83D\uDE00"), namesOf(tree))
        assertNotEquals(ObjectId.zeroId(), tree)
    }
}
