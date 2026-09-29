package cz.loplex.timebraid.git

import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path

/**
 * Thrown when a `git` subprocess exits non-zero, carrying the tail of its output, or cannot be
 * started at all.
 */
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
 * Each of the three that have something to report asks git for `--progress`, because git prints
 * none when its output is not a terminal — and this one's never is, being read here rather than
 * shown. Adding a remote is the fourth and reports nothing; filling in a working tree moves no
 * objects but counts files, so it draws. Reading it is not the same as dropping it: what arrives is
 * handed to the caller's `onProgress` as it arrives, while the lines go on being collected for the
 * log and for the tail a failure is reported with.
 *
 * @param log receives the command line and every line it prints — wired to `--verbose`. The line is
 *   rendered to be run: a command with a working directory is written with the `-C` that would take
 *   it there, so it says which repository it acted on rather than leaving that to the reader.
 */
class GitCommand(private val log: (String) -> Unit = {}) {

    /**
     * `git clone --mirror --progress <remote> <target>` — a bare clone whose `origin` fetch refspec
     * maps every ref straight across, so [fetch] can later bring it fully up to date. A plain
     * `--bare` clone records no refspec and would never refresh.
     */
    fun cloneMirror(remote: String, target: Path, onProgress: (String) -> Unit = {}) =
        exec(null, "clone", "--mirror", "--progress", remote, target.toString(), onProgress = onProgress)

    /**
     * `git fetch --prune --progress origin` in [clone] — bring an existing mirror clone up to date.
     */
    fun fetch(clone: Path, onProgress: (String) -> Unit = {}) =
        exec(clone, "fetch", "--prune", "--progress", "origin", onProgress = onProgress)

    /**
     * Records [url] as remote [name] of the repository at [repo], and nothing more.
     *
     * No fetch follows, because the merge has already done it: `TargetRepository.fetchFrom` brought
     * across everything the refs the run read reach, and the refs under `refs/remotes/<name>/`
     * pointing at what arrived are named by `RefNames` and written by `BraidWriter`. What this
     * leaves behind is the configuration, so a later `git fetch <name>` picks up whatever the input
     * has gained since the merge.
     *
     * `--no-tags` is set on the remote for when that day comes: the output already carries every tag
     * the run carried over, under its own prefixed name, and fetching them again unprefixed would
     * collide.
     */
    fun addRemote(repo: Path, name: String, url: String) {
        exec(repo, "remote", "add", "--no-tags", name, url)
    }

    /** `git -C <repo> checkout -f --progress <branch>` — fill the working tree of a fresh repo. */
    fun checkout(repo: Path, branch: String, onProgress: (String) -> Unit = {}) =
        exec(repo, "checkout", "-f", "--progress", branch, onProgress = onProgress)

    private fun exec(cwd: Path?, vararg args: String, onProgress: ((String) -> Unit)? = null) {
        val command = listOf("git", *args)
        // The process takes its working directory from the builder below; a line that only quotes
        // the arguments leaves that out, and says `git fetch --prune --progress origin` without
        // naming the repository it fetched into. So the rendering puts it back as the `-C` the
        // command would need to run anywhere else — which is what the in-process operations log
        // beside it, and what a failure has to name to be worth reading.
        val shown =
            if (cwd == null) command.joinToString(" ")
            else (listOf("git", "-C", cwd.toString()) + args).joinToString(" ")
        log(shown)
        val builder = ProcessBuilder(command)
            .apply { cwd?.let { directory(it.toFile()) } }
            .redirectErrorStream(true)
        dropRedirectingVariables(builder.environment())
        val process = try {
            builder.start()
        } catch (e: IOException) {
            // No git on PATH is the likely cause, and not the only one: a working directory that is
            // not there fails start() the same way, so the message asks rather than says, and the
            // JVM's own reason follows it. MergeCommand reports this exception as a message; the
            // IOException itself would reach the user as a stack trace.
            throw GitCommandException(
                "`$shown` could not be started -- is git on PATH? (${e.message})"
            )
        }
        val output = process.inputStream.bufferedReader().use { reader -> read(reader, onProgress) }
        val code = process.waitFor()
        if (code != 0) {
            throw GitCommandException(
                buildString {
                    append('`').append(shown).append("` failed (exit ").append(code).append(')')
                    output.takeLast(10).forEach { append('\n').append(it) }
                }
            )
        }
    }

    /**
     * Everything the process printed, split the two ways git splits it.
     *
     * A line ends with `\n` and is what the log and a failure's tail are made of. Progress ends with
     * a bare `\r`, because it is meant to be drawn over rather than added to — so reading by lines
     * alone would hold the whole of it back as one enormous line and deliver it once the work was
     * already done. Splitting on both is the difference between forwarding progress and collecting
     * it.
     *
     * @return the `\n`-terminated lines only. Progress is for watching, not for quoting back in a
     *   refusal, where a hundred redraws of the same sentence would bury the error under itself.
     */
    private fun read(reader: BufferedReader, onProgress: ((String) -> Unit)?): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) break
            when (val c = next.toChar()) {
                '\n' -> {
                    log(current.toString())
                    lines.add(current.toString())
                    current.setLength(0)
                }
                '\r' -> {
                    // A `\r\n` is one line ending, not progress followed by an empty line — which
                    // is how every line would read on a Windows git.
                    reader.mark(1)
                    val following = reader.read()
                    if (following.toChar() == '\n') {
                        log(current.toString())
                        lines.add(current.toString())
                    } else {
                        if (following >= 0) reader.reset()
                        onProgress?.invoke(current.toString())
                    }
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) {
            log(current.toString())
            lines.add(current.toString())
        }
        return lines
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
