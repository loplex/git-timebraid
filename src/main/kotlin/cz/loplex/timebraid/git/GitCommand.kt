package cz.loplex.timebraid.git

import java.nio.file.Path

/** Thrown when a `git` subprocess exits non-zero; carries the tail of its output. */
class GitCommandException(message: String) : RuntimeException(message)

/**
 * The entire subprocess surface of the program: the few git operations that are *not* object
 * plumbing — cloning a remote, refreshing a clone, adding a remote, filling in a working tree.
 *
 * These shell out to the user's own `git` on purpose. It already knows their ssh keys, credential
 * helpers and `url.*.insteadOf` rewrites; JGit's own transport would have to be handed each of them.
 * Object reading and writing stays in-process (JGit), which is where control and performance matter
 * and where a merge of three repositories spends all of its time.
 *
 * @param log receives the command line and every line it prints — wired to `--verbose`.
 */
class GitCommand(private val log: (String) -> Unit = {}) {

    /**
     * `git clone --mirror <remote> <target>` — a bare clone whose `origin` fetch refspec maps every
     * ref straight across, so [fetch] can later bring it fully up to date. A plain `--bare` clone
     * records no refspec and would never refresh.
     */
    fun cloneMirror(remote: String, target: Path) =
        exec(null, "clone", "--mirror", remote, target.toString())

    /** `git fetch --prune origin` in [clone] — bring an existing mirror clone up to date. */
    fun fetch(clone: Path) =
        exec(clone, "fetch", "--prune", "origin")

    /**
     * Adds [url] as remote [name] of the repository at [repo] and fetches its branches under
     * `refs/remotes/<name>/`. Tags are deliberately left out: the output already carries every tag
     * under its own prefixed name, and fetching them again unprefixed would collide.
     */
    fun addRemote(repo: Path, name: String, url: String) {
        exec(repo, "remote", "add", name, url)
        exec(repo, "fetch", "--no-tags", name)
    }

    /** `git -C <repo> checkout -f <branch>` — populate the working tree of a freshly written repo. */
    fun checkout(repo: Path, branch: String) =
        exec(repo, "checkout", "-f", branch)

    private fun exec(cwd: Path?, vararg args: String) {
        val command = listOf("git", *args)
        log(command.joinToString(" "))
        val process = ProcessBuilder(command)
            .apply { cwd?.let { directory(it.toFile()) } }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().useLines { lines ->
            lines.onEach(log).toList()
        }
        val code = process.waitFor()
        if (code != 0) {
            throw GitCommandException(
                buildString {
                    append('`').append(command.joinToString(" ")).append("` failed (exit ").append(code).append(')')
                    output.takeLast(10).forEach { append('\n').append(it) }
                }
            )
        }
    }
}
