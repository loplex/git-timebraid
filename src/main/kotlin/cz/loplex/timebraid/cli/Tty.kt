package cz.loplex.timebraid.cli

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Whether the program's own narration is going to a terminal somebody is watching.
 *
 * This decides whether progress is drawn at all. A terminal can be written over, so a count can be
 * redrawn in place as often as it likes and still cost one line; a pipe, a file or a CI log cannot,
 * and the same writing there is a heap of carriage returns nobody asked for. `git` makes the same
 * call: its progress is drawn when stderr is a terminal and not at all otherwise (git-fetch(1),
 * `--progress`), and that is the rule followed here.
 *
 * It has to be asked of **stderr** specifically, which is the one thing that makes this awkward.
 * The narration goes there because stdout is reserved for the plan summary, so
 * `--dry-run … > summary.txt` leaves stdout in a file while stderr is still a terminal, and that is
 * exactly the run where progress should keep being drawn. Mordant, which the CLI already uses and
 * which knows all about terminals, reports only `outputInteractive` — stdout — and so answers the
 * wrong question here.
 *
 * Which leaves `isatty` on the file descriptor. `Console.isTerminal`, from JDK 22, asks it of stdin
 * and stdout, which is the wrong question again; the panama FFM API would ask it without JNA, but
 * wants a newer JVM than the 17 this targets. JNA is already in the jar, because mordant reads the
 * terminal size through it where the JDK's own foreign function API is not open to it.
 */
internal object Tty {

    private interface PosixLibC : Library {
        fun isatty(fd: Int): Int
    }

    /**
     * Windows is asked a different question, and on purpose.
     *
     * The CRT has `_isatty`, which would read like the POSIX call above and be wrong: it answers
     * for any *character device*, so `2>NUL` reports a terminal and the bar is drawn into nothing.
     * Whether a handle is a console is what `GetConsoleMode` answers — it fails on everything that
     * is not one — and it is what mordant asks for its own streams.
     */
    private interface Kernel32 : Library {
        fun GetStdHandle(which: Int): Pointer?
        fun GetConsoleMode(handle: Pointer, mode: IntByReference): Boolean
    }

    private const val STDERR_FD = 2
    private const val STD_ERROR_HANDLE = -12

    /** What `GetStdHandle` returns for a stream that is not there; `0` means the same in practice. */
    private const val INVALID_HANDLE = -1L

    /** Answered once: a process does not change which of its descriptors are terminals. */
    val stderrIsInteractive: Boolean by lazy {
        detect(System.getenv("TERM")) {
            if (Platform.isWindows()) {
                val kernel32 = Native.load("kernel32", Kernel32::class.java)
                val handle = kernel32.GetStdHandle(STD_ERROR_HANDLE)
                // A 32-bit process spells the same invalid handle as an unsigned word.
                val value = handle?.let { Pointer.nativeValue(it) }
                if (handle == null || value == 0L || value == INVALID_HANDLE || value == 0xFFFFFFFFL) {
                    false
                } else {
                    kernel32.GetConsoleMode(handle, IntByReference())
                }
            } else {
                stderrIsTerminal(Native.load("c", PosixLibC::class.java)::isatty)
            }
        }
    }

    /**
     * The answer, given what `TERM` says and [ask], the native question.
     *
     * The question is passed in rather than asked here so that a test can stand in for the native
     * library, which loads on every machine the suite runs on and so never reaches the `catch`.
     */
    internal fun detect(term: String?, ask: () -> Boolean): Boolean {
        // A terminal that has told us it can do nothing is to be believed. `TERM=dumb` is how a
        // build log, an editor's shell and `M-x shell` say so, and drawing over a line there leaves
        // the carriage returns visible.
        if (term == "dumb") return false
        return try {
            ask()
        } catch (e: Throwable) {
            // Every way this can fail — no native library, a platform JNA has no mapping for, a
            // security manager — is a reason to draw no progress, never a reason to fail a merge
            // that is otherwise going fine. `Throwable` rather than `Exception` because a missing
            // native library arrives as an Error.
            false
        }
    }

    /** Whether stderr is a terminal, asked through [isatty], which takes a file descriptor. */
    internal fun stderrIsTerminal(isatty: (Int) -> Int): Boolean = isatty(STDERR_FD) != 0
}
