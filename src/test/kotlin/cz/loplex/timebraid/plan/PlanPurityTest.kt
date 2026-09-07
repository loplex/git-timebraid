package cz.loplex.timebraid.plan

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The planner is pure: it knows nothing about git and touches nothing outside its own commits. That
 * is what makes the algorithm testable in milliseconds without a repository, and it is a property
 * that erodes the moment one convenient import slips in — so it is checked, not merely intended.
 *
 * Both the sources and the compiled classes are inspected. The source check gives a readable failure
 * pointing at the offending file; the bytecode check is the one that cannot be talked around, since
 * it sees fully qualified references that never appear as an import.
 */
class PlanPurityTest {

    @Test
    fun `no source of the planner reaches for git or for the outside world`() {
        val sources = Files.list(SOURCES).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.collect(Collectors.toList())
        }
        assertTrue(sources.size >= 6, "expected the planner's sources at $SOURCES, found $sources")

        for (source in sources) {
            val text = Files.readString(source)
            for (forbidden in FORBIDDEN_SOURCE) {
                assertTrue(
                    !text.contains(forbidden),
                    "${source.fileName} mentions '$forbidden'; the planner must stay free of it",
                )
            }
        }
    }

    @Test
    fun `no compiled class of the planner refers to git or to the outside world`() {
        val classes = Files.list(classesDirectory()).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.collect(Collectors.toList())
        }
        assertTrue(classes.size >= 6, "expected compiled planner classes, found $classes")

        for (classFile in classes) {
            // Every type a class refers to appears verbatim in its constant pool, so a plain scan of
            // the bytes catches an import, a fully qualified name and an inlined call alike.
            val constants = String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1)
            for (forbidden in FORBIDDEN_BYTECODE) {
                assertTrue(
                    !constants.contains(forbidden),
                    "${classFile.fileName} refers to '$forbidden'; the planner must stay free of it",
                )
            }
        }
    }

    private fun classesDirectory(): Path {
        val marker = CommitGraph::class.java
        val resource = marker.getResource("${marker.simpleName}.class")
            ?: error("cannot locate the compiled ${marker.simpleName}")
        val path = Path.of(resource.toURI())
        assertTrue(Files.isRegularFile(path), "expected compiled classes on disk, got $resource")
        return path.parent
    }

    private companion object {
        val SOURCES: Path = Path.of("src", "main", "kotlin", "cz", "loplex", "timebraid", "plan")

        /** Package prefixes, as they are written in Kotlin source. */
        val FORBIDDEN_SOURCE = listOf(
            "org.eclipse.jgit",
            "java.io",
            "java.nio",
            "java.net",
            "kotlin.io",
            "ProcessBuilder",
        )

        /** The same, as the JVM writes them in a constant pool. */
        val FORBIDDEN_BYTECODE = listOf(
            "org/eclipse/jgit",
            "java/io/",
            "java/nio/",
            "java/net/",
            "kotlin/io/",
            "java/lang/System",
            "java/lang/ProcessBuilder",
            "java/lang/Runtime",
        )
    }
}
