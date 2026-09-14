package cz.loplex.timebraid.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That asking the question is safe, on whichever platform is asking.
 *
 * What the answer *is* cannot be asserted here — it depends on how the suite was launched — but
 * that it arrives at all can be, and the two platform paths are different native calls that only
 * their own CI runner ever executes. A missing native library, or a JNA with no mapping for the
 * platform, has to come back as "no terminal" rather than as an exception out of a merge.
 *
 * The rest is asked of the pieces with the native library stood in for: a library that fails, what
 * `TERM` says, and which descriptor is asked. The real one loads everywhere this suite runs, so
 * only a stand-in reaches the first of those.
 */
class TtyTest {

    @Test
    fun `the question is answered rather than thrown`() {
        val answer = Tty.stderrIsInteractive
        // Asked twice because the answer is cached, and a cache that recomputes on a thrown
        // initializer would fail the second time rather than the first.
        assertEquals(answer, Tty.stderrIsInteractive)
    }

    @Test
    fun `a test run is not a terminal, its output being captured`() {
        // Surefire takes the forked JVM's streams, so stderr here is a pipe on every platform. This
        // is the assertion that would have caught `_isatty` on Windows reporting a character device
        // as a console — which it does, and which is why the Windows path asks GetConsoleMode.
        assertFalse(Tty.stderrIsInteractive, "a captured stderr must not look like a terminal")
    }

    @Test
    fun `a native call that fails is answered as no terminal`() {
        // An Error as much as an exception: a missing native library arrives as one.
        assertFalse(Tty.detect("xterm-256color") { throw UnsatisfiedLinkError("no libc here") })
        assertFalse(Tty.detect("xterm-256color") { throw IllegalStateException("no mapping") })
    }

    @Test
    fun `TERM=dumb is no terminal whatever the library says`() {
        assertFalse(Tty.detect("dumb") { true })
        assertTrue(Tty.detect("xterm-256color") { true })
        assertTrue(Tty.detect(null) { true })
    }

    @Test
    fun `it is stderr that is asked, not stdout`() {
        // `--dry-run > summary.txt` leaves stdout in a file while stderr is still a terminal.
        assertTrue(Tty.stderrIsTerminal { fd -> if (fd == 2) 1 else 0 })
        assertFalse(Tty.stderrIsTerminal { fd -> if (fd == 1) 1 else 0 })
    }
}
