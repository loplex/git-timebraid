package cz.loplex.timebraid.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * What [Progress] lets through at each level, and how each source of a count — the commit counter,
 * JGit's monitor, git's own progress lines — is drawn, repainted, closed and cut short.
 */
class ProgressTest {

    private fun recorder() = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 90)

    /**
     * A recorder that tells the test what the repainting thread is doing: a permit in [repaints]
     * for each frame it writes, and, with [holdMillis], a permit in [holding] each time a repaint
     * has begun and is being held there.
     *
     * The hold sits where mordant asks for the terminal's size, which a repaint does before it
     * draws anything: a repaint held there has already decided to draw, and draws once let go.
     */
    private class Watched(val recorder: TerminalRecorder, private val holdMillis: Long = 0) :
        TerminalInterface by recorder {

        val repaints = Semaphore(0)
        val holding = Semaphore(0)

        override fun shouldAutoUpdateSize() = holdMillis > 0 || recorder.shouldAutoUpdateSize()

        override fun getTerminalSize(): com.github.ajalt.mordant.rendering.Size {
            if (holdMillis > 0 && Thread.currentThread().name == REPAINTING) {
                holding.release()
                Thread.sleep(holdMillis)
            }
            return recorder.getTerminalSize()
        }

        override fun completePrintRequest(request: PrintRequest) {
            recorder.completePrintRequest(request)
            if (Thread.currentThread().name == REPAINTING) repaints.release()
        }

        private companion object {
            /** The name `Progress` gives the thread a bar is repainted on. */
            const val REPAINTING = "git-timebraid-progress"
        }
    }

    /** Waits until the repainting thread has written [count] more times. */
    private fun Watched.awaitRepaints(count: Int) {
        assertTrue(repaints.tryAcquire(count, 20, TimeUnit.SECONDS), "no repaint was drawn")
    }

    private fun lines(level: Progress.Level, use: (Progress) -> Unit): List<String> {
        val collected = ArrayList<String>()
        use(Progress(level, { collected.add(it) }))
        return collected
    }

    /**
     * Drives a whole phase past a counter, which closes itself on the last item.
     *
     * Slowly on purpose. Repainting runs on a clock, so a phase finishing inside one tick draws
     * nothing whatever — which is right, there having been nothing to watch, and useless to a test
     * that wants to know where the frames landed.
     */
    private fun countTo(progress: Progress, total: Int) {
        val tick = progress.counter(total, "commits written")
        for (done in 1..total) {
            tick(done)
            if (done % (total / 10).coerceAtLeast(1) == 0) Thread.sleep(20)
        }
    }

    @Test
    fun `a level prints the messages below it and no others`() {
        assertEquals(emptyList<String>(), lines(Progress.Level.QUIET) { it.result("r"); it.detail("d") })
        assertEquals(
            listOf("  r"),
            lines(Progress.Level.NORMAL) { it.result("r"); it.detail("d") },
        )
        assertEquals(
            listOf("  r", "  d"),
            lines(Progress.Level.VERBOSE) { it.result("r"); it.detail("d") },
        )
    }

    @Test
    fun `without a terminal a counter leaves its line but draws no bar`() {
        // No terminal means a pipe, a file or a CI log. A bar is of no use to any of them and none
        // is drawn — but the line it would have left behind is owed all the same, which is what
        // makes a redirected run read like a watched one with the animation taken out.
        val collected = ArrayList<String>()
        countTo(Progress(Progress.Level.NORMAL, { collected.add(it) }, terminal = null), 10_000)
        assertEquals(1, collected.size, collected.toString())
        assertTrue(collected.single().trimStart().startsWith("commits written, 10000 in "), collected.toString())
    }

    @Test
    @Timeout(30)
    fun `on a terminal a counter draws a bar naming the phase`() {
        val recorder = recorder()
        countTo(
            Progress(
                Progress.Level.NORMAL,
                { },
                terminal = Terminal(interactive = true, terminalInterface = recorder),
            ),
            500,
        )
        // The animation refreshes on its own thread, and closing the bar completes and clears it
        // under the same lock, so there is nothing still to arrive by the time the loop is over.
        val drawn = recorder.output()
        assertTrue(drawn.contains("commits written"), drawn)
        assertTrue(drawn.contains("500"), drawn)
    }

    @Test
    @Timeout(30)
    fun `a drawn bar goes to stderr, because stdout carries the plan summary`() {
        // The whole reason Progress builds a terminal of its own rather than drawing on the
        // command's: mordant writes to stdout, and here stdout is the plan summary.
        val recorder = recorder()
        val base = Terminal(interactive = true, terminalInterface = recorder)
        val stderrTerminal = Terminal(
            interactive = true,
            terminalInterface = Progress.asStderr(base.terminalInterface),
        )
        countTo(Progress(Progress.Level.NORMAL, { }, terminal = stderrTerminal), 100)

        assertEquals("", recorder.stdout(), "progress must not reach stdout")
        assertTrue(recorder.stderr().contains("commits written"), recorder.stderr())
    }

    @Test
    @Timeout(30)
    fun `a finished bar is taken off the screen, not left spent on it`() {
        // A bar answers how far, how fast and how long is left, and none of those is still a
        // question once it is done. What it ended on is written as a line by whoever opened it;
        // leaving the spent bar there as well would say the same thing twice, in fifty more
        // columns, once per task of every phase.
        val recorder = recorder()
        countTo(
            Progress(
                Progress.Level.NORMAL,
                { },
                terminal = Terminal(interactive = true, terminalInterface = recorder),
            ),
            500,
        )
        val frames = recorder.output().split("\r").filter { it.contains("commits written") }
        assertTrue(frames.isNotEmpty(), "nothing was ever drawn: ${recorder.output()}")
        val screen = recorder.output().substringAfterLast("\r")
        assertFalse(screen.contains("commits written"), "a spent bar was left behind: $screen")
    }

    @Test
    @Timeout(60)
    fun `a bar repaints over one line rather than stacking a line per frame`() {
        // A bar costs one line of screen however often it is repainted: every frame after the first
        // is preceded by a carriage return and nothing else, so the terminal overwrites the line
        // instead of scrolling it away.
        //
        // What pins that down is that nothing a bar writes ends a line. The way a bar strands one is
        // a repaint drawing a finished task, which mordant ends with a newline, so each half below
        // waits, by counting the repaints themselves, until frames have been drawn while the work
        // stood where it matters, rather than sleeping and hoping the clock obliged.
        //
        // Note also what a captured terminal session cannot tell you: read a capture in a mode that
        // translates line endings, as a language's default text mode does, and every carriage
        // return reads as a newline — which makes correct output look like exactly this bug.
        val counted = Watched(recorder())
        val tick = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = counted),
        ).counter(500, "commits written")
        tick(250)
        counted.awaitRepaints(2)
        tick(500)
        val drawn = counted.recorder.output()
        assertTrue(drawn.contains("commits written"), "nothing was drawn: $drawn")
        assertFalse(drawn.contains("\n"), "a counter ended a line: $drawn")

        // A counter closes its bar in the call that reaches the total. JGit's monitor is the other
        // shape, and the ordinary one: update() reaches the total, and endTask() comes later, with
        // repaints in between. Those are the repaints that would draw a finished frame to strand.
        val watched = Watched(recorder())
        val monitor = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = watched),
        ).monitor()
        monitor.beginTask("Receiving objects", 100)
        monitor.update(50)
        watched.awaitRepaints(2)
        monitor.update(50)
        watched.repaints.drainPermits()
        watched.awaitRepaints(2)
        monitor.endTask()
        val monitored = watched.recorder.output()
        assertTrue(monitored.contains("Receiving objects"), "nothing was drawn: $monitored")
        assertFalse(monitored.contains("\n"), "a monitor task ended a line: $monitored")
    }

    @Test
    @Timeout(60)
    fun `a close that arrives while a repaint is being written waits for it`() {
        // The lock Drawn repaints under is what keeps a repaint that has already decided to draw
        // from drawing after close() has taken the bar off its line. Here a repaint is held after
        // it has checked that the bar is still open, and the call that closes the bar is made
        // while it is held: without the lock, close() clears the line and the held repaint then
        // draws its frame back onto it; with it, close() waits until the frame is out, and clears
        // it.
        val watched = Watched(recorder(), holdMillis = 300)
        val tick = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = watched),
        ).counter(10, "commits written")
        tick(1)
        assertTrue(watched.holding.tryAcquire(20, TimeUnit.SECONDS), "no repaint was drawn")
        watched.repaints.drainPermits()
        tick(10)
        watched.awaitRepaints(1)
        val drawn = watched.recorder.output()
        assertFalse(
            drawn.substringAfterLast("\r").contains("commits written"),
            "a frame was drawn after close(): $drawn",
        )
    }

    @Test
    @Timeout(30)
    fun `a detail printed while a bar is drawn gets a line of its own above it`() {
        // On a real terminal the sink and the terminal write to one stream, so a detail printed
        // past the terminal landed after the bar's frame, on its line. Through the terminal it is
        // printed where the frame was, and the frame is drawn again on the line below.
        val watched = Watched(recorder())
        val printed = ArrayList<String>()
        val progress = Progress(
            Progress.Level.VERBOSE,
            { printed.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = watched),
        )
        val detail = "  git -C out update-ref refs/tags/v1 abc"
        val tick = progress.counter(500, "commits written")
        tick(250)
        watched.awaitRepaints(2)
        progress.detail(detail.trimStart())
        watched.repaints.drainPermits()
        watched.awaitRepaints(2)
        tick(500)

        assertFalse(printed.any { "update-ref" in it }, "the detail went past the terminal: $printed")
        val drawn = watched.recorder.output().split("\n")
        val at = drawn.indexOfFirst { "update-ref" in it }
        assertTrue(at >= 0, "the detail was not printed: $drawn")
        // What a carriage return leaves on the line: the detail, and none of the frame it replaced.
        val shown = drawn[at].substringAfterLast("\r")
        assertTrue(shown.endsWith(detail) && "commits written" !in shown, "the detail shares its line: $drawn")
        assertTrue(drawn.drop(at + 1).any { "commits written" in it }, "no frame below it: $drawn")
    }

    @Test
    @Timeout(30)
    fun `a detail printed while a spinner is drawn gets a line of its own above it`() {
        // On a real terminal the sink and the terminal write to one stream, so a detail printed
        // past the terminal landed after the spinner's frame, on its line. Through the terminal it
        // is printed where the frame was, and the frame is drawn again on the line below.
        val watched = Watched(recorder())
        val printed = ArrayList<String>()
        val progress = Progress(
            Progress.Level.VERBOSE,
            { printed.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = watched),
        )
        val detail = "  git -C out update-ref refs/tags/v1 abc"
        progress.whileWorking("publishing the refs") {
            watched.awaitRepaints(2)
            progress.detail(detail.trimStart())
            watched.repaints.drainPermits()
            watched.awaitRepaints(2)
        }

        assertFalse(printed.any { "update-ref" in it }, "the detail went past the terminal: $printed")
        val drawn = watched.recorder.output().split("\n")
        val at = drawn.indexOfFirst { "update-ref" in it }
        assertTrue(at >= 0, "the detail was not printed: $drawn")
        // What a carriage return leaves on the line: the detail, and none of the frame it replaced.
        val shown = drawn[at].substringAfterLast("\r")
        assertTrue(shown.endsWith(detail) && "publishing" !in shown, "the detail shares its line: $drawn")
        assertTrue(drawn.drop(at + 1).any { "publishing the refs" in it }, "no frame below it: $drawn")
    }

    @Test
    fun `a detail the terminal fails to print goes to the sink instead`() {
        val recorder = recorder()
        var failing = false
        val sizeFails = object : com.github.ajalt.mordant.terminal.TerminalInterface by recorder {
            override fun shouldAutoUpdateSize() = true
            override fun getTerminalSize(): com.github.ajalt.mordant.rendering.Size =
                if (failing) error("the terminal went away") else recorder.getTerminalSize()
        }
        val printed = ArrayList<String>()
        val progress = Progress(
            Progress.Level.VERBOSE,
            { printed.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = sizeFails),
        )
        val tick = progress.counter(2, "commits written")
        tick(1)
        failing = true
        progress.detail("git -C out update-ref refs/tags/v1 abc")
        failing = false
        tick(2)

        assertTrue(printed.any { "update-ref" in it }, "the detail was lost: $printed")
    }

    @Test
    @Timeout(30)
    fun `a phase longer than the repaint clock is redrawn in place`() {
        // The other half of it: the frames a running phase collects all belong to the same line.
        val watched = Watched(recorder())
        val progress = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = watched),
        )
        val tick = progress.counter(500, "commits written")
        // Held half way until several repaints have happened, counted rather than slept for: a
        // phase that outruns the repaint clock draws one frame and proves nothing about where a
        // second one would land.
        tick(250)
        watched.awaitRepaints(3)
        tick(500)

        val drawn = watched.recorder.output()
        val linesWithFrames = drawn.split("\n").filter { it.contains("commits written") }
        assertEquals(1, linesWithFrames.size, "every frame belongs on one line, got: $drawn")
        val frames = linesWithFrames.single().split("\r").filter { it.contains("commits written") }
        assertTrue(frames.size > 1, "a bar drawn once is not a bar being repainted: $drawn")
    }

    @Test
    @Timeout(30)
    fun `a JGit monitor draws one bar per task it is given`() {
        val recorder = recorder()
        val lines = ArrayList<String>()
        val monitor = Progress(
            Progress.Level.NORMAL,
            { lines.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        ).monitor()

        monitor.start(2)
        // Each task outlasts a repaint or two, because a task over inside one draws nothing — see
        // `countTo`.
        monitor.beginTask("Receiving objects", 100)
        // JGit reports an increment per call, not a running total.
        repeat(100) { monitor.update(1); if (it % 10 == 0) Thread.sleep(10) }
        // Not ended before the next begins: JGit is not obliged to, so beginning a task ends the
        // one before it.
        monitor.beginTask("Resolving deltas", 40)
        repeat(40) { monitor.update(1); if (it % 5 == 0) Thread.sleep(10) }
        monitor.endTask()

        val drawn = recorder.output()
        assertTrue(drawn.contains("Receiving objects"), drawn)
        assertTrue(drawn.contains("Resolving deltas"), drawn)
        // What each task reached is in the line that ending it leaves, not in a frame: the bar is
        // taken off the screen (see `a finished bar is taken off the screen`). Summed from the
        // increments, where a running total would have left 1.
        assertEquals(
            listOf("Receiving objects, 100 in", "Resolving deltas, 40 in"),
            lines.map { it.trim().substringBeforeLast(' ') },
        )
    }

    @Test
    fun `a task run for an input leaves a line bracketed with that input`() {
        // A run fetches every input in turn, and their tasks are named alike; the bracket is what
        // tells one input's line from the next.
        val collected = ArrayList<String>()
        val progress = Progress(Progress.Level.NORMAL, { collected.add(it) }, terminal = null)

        val monitor = progress.monitor("backend")
        monitor.start(1)
        monitor.beginTask("Receiving objects", 10)
        monitor.update(10)
        monitor.endTask()
        progress.gitProgress("webui").use { it("Updating files: 100% (4/4)") }

        assertEquals(2, collected.size, collected.toString())
        assertTrue(collected[0].trimStart().startsWith("[backend] Receiving objects, 10 in "), collected.toString())
        assertTrue(collected[1].trimStart().startsWith("[webui] Updating files"), collected.toString())
    }

    @Test
    fun `a count of nothing draws no bar and leaves no line`() {
        // A total of nothing is not a bar, whoever reports one: there is nothing to draw, and no
        // line to leave.
        val collected = ArrayList<String>()
        val recorder = recorder()
        val progress = Progress(
            Progress.Level.NORMAL,
            { collected.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        )

        progress.gitProgress().use { it("Updating files: 100% (0/0)") }

        assertEquals(emptyList<String>(), collected)
        assertEquals("", recorder.output(), "a count of nothing drew on the terminal")
    }

    @Test
    fun `a spinner's line carries the counts its work finished with`() {
        val collected = ArrayList<String>()
        val progress = Progress(Progress.Level.NORMAL, { collected.add(it) }, terminal = null)

        progress.whileWorking("reading", finished = { n: Int -> "2 repositories, $n commits" }) { 7 }

        assertEquals(1, collected.size, collected.toString())
        assertTrue(collected.single().trimStart().startsWith("2 repositories, 7 commits, "), collected.toString())
    }

    @Test
    fun `nothing opened after stopDrawing is drawn or reported`() {
        // A shutdown hook stops the drawing while the merge thread goes on into its next phase,
        // and that phase must neither draw again nor leave its line.
        val collected = ArrayList<String>()
        val progress = Progress(Progress.Level.NORMAL, { collected.add(it) }, terminal = null)

        progress.stopDrawing()
        progress.whileWorking("planning") { }
        val tick = progress.counter(3, "commits written")
        for (done in 1..3) tick(done)

        assertEquals(emptyList<String>(), collected)
    }

    @Test
    @Timeout(30)
    fun `git's own progress is read for its counts and drawn`() {
        val recorder = recorder()
        val lines = ArrayList<String>()
        val progress = Progress(
            Progress.Level.NORMAL,
            { lines.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        )
        progress.gitProgress().use { forward ->
            for (done in 1..40_000 step 400) {
                forward("Updating files:  ${done / 400}% ($done/40000)")
                if (done % 4_000 == 1) Thread.sleep(10)
            }
        }
        val drawn = recorder.output()
        assertTrue(drawn.contains("Updating files"), drawn)
        assertTrue(drawn.contains("40000") || drawn.contains("40.0K"), drawn)
        // Every frame shows the total, so the count read is asserted where it ends up: the last
        // one git printed, 39601 of 40000, is what the finished line reports.
        assertEquals(listOf("Updating files, 39601 in"), lines.map { it.trim().substringBeforeLast(' ') })
    }

    @Test
    @Timeout(30)
    fun `a title in words this cannot read still draws`() {
        // Only the bracket is read, and that is the whole point: git ships translated, so the words
        // ahead of it are whatever locale the user runs in. The title below is a stand-in rather
        // than any real translation — a test that quoted one would be claiming to know what git
        // says in that language, which is exactly the dependency this is here to rule out.
        val recorder = recorder()
        val progress = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        )
        progress.gitProgress().use {
            it("\u0141\u00f6\u0159\u00ebm \u00efp\u0161\u00fbm:  50% (20/40)")
            Thread.sleep(120)
        }
        assertTrue(recorder.output().contains("\u0141\u00f6\u0159\u00ebm"), recorder.output())
    }

    @Test
    @Timeout(30)
    fun `drawing that fails on the caller's thread costs the bar, not the command`() {
        // A terminal failing on the thread running the checkout costs the bar and no more, and it
        // is Drawn's guards that keep this, not the catch in GitProgress. mordant reads the size
        // again before each thing it writes, and a bar writes on that thread as it starts, hiding
        // the cursor, and as it ends, clearing its line. So the terminal fails once from the start
        // and once only by the end.
        for (fromTheStart in listOf(true, false)) {
            val recorder = recorder()
            val lines = ArrayList<String>()
            var failing = false
            val sizeFails = object : com.github.ajalt.mordant.terminal.TerminalInterface by recorder {
                override fun shouldAutoUpdateSize() = true
                override fun getTerminalSize(): com.github.ajalt.mordant.rendering.Size =
                    if (failing) error("the terminal went away") else recorder.getTerminalSize()
            }
            val progress = Progress(
                Progress.Level.NORMAL,
                { lines.add(it) },
                terminal = Terminal(interactive = true, terminalInterface = sizeFails),
            )
            failing = fromTheStart
            progress.gitProgress().use { forward ->
                forward("Updating files:  18% (1/2)")
                forward("Updating files: 100% (2/2)")
                failing = true
            }
            if (fromTheStart) {
                assertFalse(recorder.output().contains("Updating files"), recorder.output())
            }
            // The bar is lost, not what it was for: the line it leaves still says how far. That
            // line is what tells the guards from the catch, which would have dropped the bar whole
            // and left nothing to write it.
            assertEquals(
                listOf("Updating files, 2 in"),
                lines.map { it.trim().substringBeforeLast(' ') },
                "failing from the start: $fromTheStart",
            )
        }
    }

    @Test
    @Timeout(30)
    fun `anything git might print instead is survived without an exception`() {
        // The format belongs to a git whose version is nobody's to pin here. A change to it, or
        // anything else arriving on that stream, has to cost the bar and nothing else — never an
        // exception out of a callback, which would fail the checkout it was narrating.
        val recorder = recorder()
        val progress = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        )
        val surprises = listOf(
            "",
            "Updating files: 18%",
            "Updating files:  18% (0/0)",
            "Updating files:  18% (${"9".repeat(40)}/${"9".repeat(40)})",
            "remote: Counting objects: 1234",
            "error: pathspec 'x' did not match",
            "\u001b[31mUpdating files:  18% (1/2)\u001b[0m",
            "a".repeat(10_000) + ":  18% (1/2)",
        )
        progress.gitProgress().use { forward -> surprises.forEach { forward(it) } }
    }

    @Test
    @Timeout(30)
    fun `a run cut short ends its bar on the count it reached`() {
        // What a failure used to leave: the repainting thread still painting, so the message saying
        // what went wrong was printed into a bar and could not be read.
        val recorder = recorder()
        val lines = ArrayList<String>()
        val progress = Progress(
            Progress.Level.NORMAL,
            { lines.add(it) },
            terminal = Terminal(interactive = true, terminalInterface = recorder),
        )

        val tick = progress.counter(500, "commits written")
        for (done in 1..100) {
            tick(done)
            if (done % 10 == 0) Thread.sleep(10)
        }
        // Nobody closes it: this is the phase that threw.
        progress.stopDrawing()

        // What it got to is what the line says — not the total, which it never reached.
        assertTrue(lines.single().trimStart().startsWith("commits written, 100 in "), lines.toString())
    }

    @Test
    @Timeout(30)
    fun `a run cut short gives the cursor back where mordant's own show writes nothing`() {
        // The other thing a failure used to leave: a terminal whose cursor the animation had hidden
        // and never showed again. Once shutdown has begun, which is how a run cut short by Ctrl-C
        // ends, mordant's cursor.show() throws before it writes anything: it removes a shutdown
        // hook first, and that throws then. This terminal fails every show() the same way, so the
        // escape at the end can only be the one stopDrawing writes for itself.
        val recorder = recorder()
        val showFails = object : TerminalInterface by recorder {
            override fun completePrintRequest(request: PrintRequest) {
                val inShow = Thread.currentThread().stackTrace
                    .any { it.methodName == "show" && "TerminalCursor" in it.className }
                check(!inShow) { "Shutdown in progress" }
                recorder.completePrintRequest(request)
            }
        }
        val progress = Progress(
            Progress.Level.NORMAL,
            { },
            terminal = Terminal(interactive = true, terminalInterface = showFails),
        )

        val tick = progress.counter(500, "commits written")
        for (done in 1..100) {
            tick(done)
            if (done % 10 == 0) Thread.sleep(10)
        }
        progress.stopDrawing()

        val drawn = recorder.output()
        assertTrue(drawn.endsWith("\u001b[?25h"), "the cursor was left hidden: ${drawn.takeLast(40)}")
    }

    @Test
    @Timeout(30)
    fun `--quiet silences the counter as it silences every step`() {
        val recorder = recorder()
        countTo(
            Progress(
                Progress.Level.QUIET,
                { },
                terminal = Terminal(interactive = true, terminalInterface = recorder),
            ),
            10_000,
        )
        assertEquals("", recorder.output())
    }
}
