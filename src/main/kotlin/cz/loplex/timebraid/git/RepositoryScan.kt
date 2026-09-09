package cz.loplex.timebraid.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** One repository a scan found, with the place in the output its place on disk implies. */
class ScannedRepository(
    /** Where the repository is, absolute and normalized. */
    val path: Path,
    /**
     * Where its content lands: its own path relative to the base directory, or `null` for the base
     * directory itself, which is the output root.
     */
    val subdir: String?,
    /** Its identity, derived exactly as it would be from a `<repo>` argument. */
    val name: String,
)

/**
 * Reads an output layout off a directory tree instead of having it written out argument by
 * argument: every repository under one base directory becomes an input, placed in the output where
 * it sits on disk.
 *
 * The base directory is the output root, so a repository at `<base>/libs/backend` lands at
 * `libs/backend` and the base itself, when it is a repository, lands at the root — which is
 * `--root-repo` by another route, and needs no separate concept.
 *
 * Two rules decide what is looked at:
 *
 * - **A repository is not descended into.** Whatever is nested inside one is its own business —
 *   submodules, vendored checkouts, linked worktrees — and pulling those in as inputs of their own
 *   is a decision, not a default. The base directory is the exception: it is always descended into,
 *   because a scan of a repository that stopped at the repository would find nothing else.
 * - **Directories whose name begins with `.` are skipped**, and so are symlinks, which cannot be
 *   followed without the walk having to guard against a cycle. Either can still be given as an
 *   ordinary `<repo>` argument, which is what makes the pair complementary.
 */
object RepositoryScan {

    /**
     * Every repository under [base], in a deterministic order: each directory's children by name,
     * and a repository before whatever the walk reaches after it.
     */
    fun scan(base: Path): List<ScannedRepository> {
        val root = base.toAbsolutePath().normalize()
        require(root.isDirectory()) { "--scan '$base' is not a directory" }

        val found = ArrayList<ScannedRepository>()
        if (SourceRepository.isRepository(root)) {
            found += ScannedRepository(root, null, SourceRepository.defaultName(root))
        }
        walk(root, root, found)
        require(found.isNotEmpty()) { "--scan '$base' holds no git repository" }
        return found
    }

    private fun walk(root: Path, dir: Path, found: MutableList<ScannedRepository>) {
        for (child in childrenOf(dir)) {
            if (SourceRepository.isRepository(child)) {
                found += ScannedRepository(child, subdirOf(root, child), SourceRepository.defaultName(child))
            } else {
                walk(root, child, found)
            }
        }
    }

    /** Real subdirectories of [dir], by name, with no symlink and no dot-name among them. */
    private fun childrenOf(dir: Path): List<Path> =
        try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter {
                    !it.name.startsWith(".") && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
                }.sortedBy { it.name }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("cannot read '$dir' while scanning: ${e.message}", e)
        }

    /**
     * Where [repo] lands: its path below [root], with the last segment named the way every other
     * input is named, so a bare `backend.git` lands at `backend` rather than at `backend.git`.
     */
    private fun subdirOf(root: Path, repo: Path): String {
        val segments = root.relativize(repo).map { it.toString() }
        return (segments.dropLast(1) + SourceRepository.defaultName(repo)).joinToString("/")
    }
}
