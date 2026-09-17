package cz.loplex.timebraid.git

import java.nio.file.Path

/** Thrown when a `git` subprocess exits non-zero; carries the tail of its output. */
class GitCommandException(message: String) : RuntimeException(message)

/**
 * Every git command the program shells out to: the few git operations that are *not* object
 * plumbing — cloning a remote, refreshing a clone, adding a remote, filling in a working tree.
 * JGit starts `git` on its own besides, to find the system configuration, and not through here.
 *
 * These shell out to the user's own `git` on purpose. It already knows their ssh keys, credential
 * helpers and `url.*.insteadOf` rewrites; JGit's own transport would have to be handed each of them.
 * Object reading and writing stays in-process (JGit), which is where control and performance matter
 * and where a merge of three repositories spends all of its time.
 *
 * Inheriting that environment is the point, so it is passed through almost whole — see
 * [dropRedirectingVariables] for the one class of variable that is not.
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
     * Records [url] as remote [name] of the repository at [repo], and nothing more.
     *
     * No fetch follows, because the merge has already done it: `TargetRepository.fetchFrom` brought
     * across everything the refs the run read reach, and the refs under `refs/remotes/<name>/`
     * pointing at what arrived are written by `BraidWriter.mirrorInputs`. What this leaves behind
     * is the configuration, so a later `git fetch <name>` picks up whatever the input has gained
     * since the merge.
     *
     * `--no-tags` is set on the remote for when that day comes: the output already carries every tag
     * under its own prefixed name, and fetching them again unprefixed would collide.
     */
    fun addRemote(repo: Path, name: String, url: String) {
        exec(repo, "remote", "add", "--no-tags", name, url)
    }

    /** `git -C <repo> checkout -f <branch>` — populate the working tree of a freshly written repo. */
    fun checkout(repo: Path, branch: String) =
        exec(repo, "checkout", "-f", branch)

    private fun exec(cwd: Path?, vararg args: String) {
        val command = listOf("git", *args)
        log(command.joinToString(" "))
        val builder = ProcessBuilder(command)
            .apply { cwd?.let { directory(it.toFile()) } }
            .redirectErrorStream(true)
        dropRedirectingVariables(builder.environment())
        val process = builder.start()
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

    internal companion object {

        /**
         * Environment variables that tell git which repository to work on, overriding both `-C` and
         * the process working directory. A caller that has one of these exported — a git hook, or any
         * script that wrapped git and exported its own repository along the way — would otherwise send
         * every `clone`, `fetch` and `checkout` here into a repository nobody asked for, and the
         * failure would look like a bug in this program rather than in its environment.
         *
         * The list is a deny-list rather than an allow-list on purpose. Shelling out is what keeps
         * `GIT_SSH_COMMAND`, `GIT_ASKPASS`, `SSH_AUTH_SOCK`, the proxy variables and `GIT_CONFIG_*`
         * working, so the environment has to arrive almost whole; only the variables that move the
         * repository out from under the command are worth taking away.
         */
        private val REDIRECTING_VARIABLES = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_NAMESPACE",
        )

        /**
         * Removes [REDIRECTING_VARIABLES] from a subprocess environment, leaving everything else.
         *
         * Removed one key at a time rather than through `keys.removeAll`, whose direction of iteration
         * depends on the relative sizes of the two collections: on Windows the environment map matches
         * names case-insensitively, and only the map's own `remove` honours that.
         */
        internal fun dropRedirectingVariables(environment: MutableMap<String, String>) {
            REDIRECTING_VARIABLES.forEach(environment::remove)
        }
    }
}
