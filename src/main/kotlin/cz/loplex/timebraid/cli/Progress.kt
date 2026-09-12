package cz.loplex.timebraid.cli

import com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.github.ajalt.mordant.widgets.progress.ProgressBarDefinition
import com.github.ajalt.mordant.widgets.progress.completed
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarLayout
import com.github.ajalt.mordant.widgets.progress.speed
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeRemaining
import org.eclipse.jgit.lib.ProgressMonitor
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale

/**
 * Where the program narrates what it is doing. Everything goes to stderr — stdout is reserved for
 * results (the plan summary), so `git-timebraid --dry-run … | diff` stays clean.
 *
 * @param sink takes one whole line. Wired to clikt's `echo`, which is what puts it on stderr and
 *   what a test harness records.
 * @param terminal where progress is drawn, or `null` for none at all — which is what a run gets when
 *   stderr is not a terminal somebody is watching. It must be a terminal whose output goes to
 *   stderr; [stderrTerminal] builds one.
 * @param charset what the drawing is encoded in, which decides whether a spinner may be braille —
 *   see [Glyphs]. Taken from stderr, and a parameter only so that a test can ask for a console this
 *   machine does not have.
 */
class Progress(
    private val level: Level,
    private val sink: (String) -> Unit,
    private val terminal: Terminal? = null,
    private val width: Int = DEFAULT_WIDTH,
    private val charset: Charset = Glyphs.stderrCharset,
) {

    enum class Level { QUIET, NORMAL, VERBOSE }

    /** Every bar and spinner currently on screen, so [stopDrawing] can put them all away. */
    private val open = Collections.synchronizedSet(LinkedHashSet<Drawn>())

    /**
     * Held for every repaint, every [Drawn.close] and every [detail], so that nothing is written to
     * the terminal while one of them is: mordant locks each print, but not an animation's change of
     * state together with the print that draws it.
     */
    private val drawing = Any()

    /** Guards [onExit], which two drawing threads can reach at once. */
    private val exitLock = Any()

    /** The hook registered while something is on screen; see [cleanUpOnExit]. */
    private var onExit: Thread? = null

    /**
     * Set by [stopDrawing], after which nothing is drawn again, and no bar or spinner opened after
     * it leaves its closing line either: those lines go with the drawing. A heading, and a line a
     * phase reports through [result] itself, still print.
     *
     * Taking down what is on screen is only half of stopping: a shutdown hook runs *beside* the
     * program rather than after it, so on Ctrl-C the thread doing the merge carries on and opens the
     * next phase's bar, which hides the cursor again behind the hook that has just given it back.
     * Measured, and it is not a narrow window — the cursor was left hidden in every run. So
     * stopping has to be a door that shuts.
     */
    @Volatile
    private var stopped = false

    /**
     * Takes down whatever is being drawn, and gives the cursor back.
     *
     * The caller runs this on the way out however the run ends, because the alternatives are both
     * bad and both were seen: a failure's message printed *into* a bar, the repainting thread having
     * gone on painting over it, and a terminal left without a cursor because the animation that hid
     * it was never closed. Mordant hides the cursor with a shutdown hook meant to restore it, and
     * that hook did not fire on the path a real failure takes — so nothing here depends on it.
     *
     * Safe to call twice, safe to call when nothing was ever drawn, and safe to call from a
     * shutdown hook — which is one of the ways it is called.
     */
    fun stopDrawing() {
        stopped = true
        for (drawn in open.toList()) runCatching { drawn.close() }
        // Mordant's `show` unregisters its own shutdown hook before writing anything, and
        // `removeShutdownHook` throws once shutdown has started — so on the one path that needs it
        // most, every route through the cursor writes nothing at all. Measured by interrupting a
        // long run mid-bar: the animator wipes the bar, the show that should follow it throws, and
        // the terminal is left cleared with the cursor still hidden. `rawPrint` touches no hooks,
        // so that is the route taken — only on refusal, so an ordinary run emits one show, not two.
        if (runCatching { terminal?.cursor?.show() }.isFailure) {
            runCatching { terminal?.rawPrint(SHOW_CURSOR) }
        }
        val hook = synchronized(exitLock) { onExit.also { onExit = null } } ?: return
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }

    /**
     * Arranges for [stopDrawing] to run even on a way out that unwinds nothing.
     *
     * The caller's `finally` covers a run that throws, which is the common failure and was the one
     * this was written for. It does not cover the other way a long run ends: **Ctrl-C**. A signal
     * does not unwind the stack, so no `finally` runs, and interrupting a merge part way through is
     * not an exotic path — it is how anybody stops one that is taking longer than they have.
     *
     * Mordant hides the cursor with a shutdown hook of its own meant to cover exactly this, and it
     * does not, for a reason measurable from outside: its `show` unregisters that hook and leaves
     * the field that held it set, so the guard that registers one never passes again. A run draws a
     * bar per task and each of them shows the cursor when it closes, so from the second bar onwards
     * there is no hook at all. Interrupted during a bar, this program left the cursor hidden in
     * every one of five runs measured.
     *
     * So the hook here is not a second line of defence behind mordant's — it is the only one after
     * the first bar. It also does what mordant's cannot: [stopDrawing] stops the threads that are
     * painting, and a cursor restored while something is still drawing over it is no better than a
     * cursor never restored.
     *
     * Registered when there is first something to take down rather than when this is constructed,
     * so a run that draws nothing registers nothing, and dropped again by [stopDrawing] on the
     * ordinary way out.
     */
    private fun cleanUpOnExit() = synchronized(exitLock) {
        if (onExit != null) return@synchronized
        val hook = Thread(::stopDrawing, "git-timebraid-progress-exit")
        // A bar started while the process is already going down cannot register anything, and that
        // is not a reason to fail the phase it was narrating.
        if (runCatching { Runtime.getRuntime().addShutdownHook(hook) }.isSuccess) onExit = hook
    }

    /**
     * Opens a phase: a blank line, then a heading ruled across the output.
     *
     * A run is half a dozen phases and each of them has something of its own to report, which in one
     * unbroken column reads as a single list of unrelated facts. The heading says what is about to
     * happen; what follows it, indented, is what came of it.
     *
     * Ruled in ASCII, like every other message here: `MessageCharsetTest` holds every printed
     * string literal to it, and the ANSI code page a JVM on Windows gives a redirected stream,
     * cp1252, has no box-drawing character and substitutes a `?` for one.
     */
    fun phase(title: String) {
        if (level == Level.QUIET) return
        sink("")
        val heading = "-- $title "
        sink(if (heading.length >= width) heading.trimEnd() else heading + "-".repeat(width - heading.length))
    }

    /**
     * One thing a phase has to report, under its heading.
     *
     * Mostly what a finished bar leaves behind. The bar itself goes: what it had to say while it
     * ran — how far, how fast, how long is left — is answered by the time it is over, and a screen
     * of spent bars is a screen of answered questions. The count it ended on is the part worth
     * keeping, so that is what is written here.
     *
     * Not only that, though: a phase with nothing to draw reports through this as well, and so
     * does one that says what it is about to ask for before asking. The line belongs to the
     * phase, not to a bar, which is why it prints at [Level.NORMAL] and [detail] does not.
     */
    fun result(text: String) {
        if (level != Level.QUIET) sink("  $text")
    }

    /**
     * A detail — every git subprocess, mostly. Printed only with `--verbose`.
     *
     * While a bar or a spinner is on screen, the line goes through the terminal that draws it,
     * which takes the animation off, prints the line, and draws the animation again below it.
     * Through [sink] it would land after the animation's frame, on its line, and the next repaint
     * would leave a frame and a detail sharing that line.
     */
    fun detail(message: String) {
        if (level != Level.VERBOSE) return
        synchronized(drawing) {
            val on = terminal
            val drawn = on != null && synchronized(open) { open.any { it.isDrawn } }
            // A terminal failing here costs the detail its place above the animation, and no more.
            if (!drawn || runCatching { on.rawPrint("  $message\n") }.isFailure) sink("  $message")
        }
    }

    /**
     * One drawn thing, however it is being driven: a bar over a known total, or a spinner over work
     * that cannot say how much is left.
     *
     * Whoever opens one closes it, and closing it is what leaves the finished state on screen:
     * [close] takes the bar off its line and writes the line it leaves behind in its place. It
     * completes a task that has a total first, because an animation cleared mid-task leaves mordant
     * believing it is still running.
     *
     * The repaints are driven from here too, rather than by mordant's own animator thread,
     * `animateOnThread(…).execute()`. That thread draws a frame and *then* asks whether the
     * task has finished; a task that completes in between is stopped on the frame already drawn,
     * and stopping ends the line with a newline and resets the animation — after which the
     * animator's own closing repaint starts the animation over, and a first draw carries no
     * carriage return, so the finished frame lands on the line below the stale one. That is one
     * stranded line per bar, and a run draws a bar per fetch task, per git phase, and for the
     * commit writer. Repainting under [lock], and keeping the count one short of the total until
     * [close] sets it, leaves the task no moment to finish in except the one where nothing is being
     * drawn — so the bar lives on the line it started on.
     */
    private inner class Drawn(
        /** The bar, or `null` when there is no terminal to draw one on. The line is written either way. */
        private val animation: ThreadProgressTaskAnimator<Unit>?,
        terminal: Terminal?,
        private val total: Long?,
        /** What the line left behind says. A caller that only knows it once the work is done
         *  replaces it through [relabel] rather than printing a second line. */
        private var label: String,
    ) {

        private val startedAt = System.nanoTime()

        /**
         * Held for every repaint, so that no two of them, and no [close], overlap. One lock for
         * every bar, [drawing], so that a [detail] printed through the terminal waits for them too.
         */
        private val lock = drawing

        /** Whether this is being drawn, rather than only waiting to leave its line. */
        val isDrawn: Boolean get() = animation != null

        private var closed = false

        /** As far as the count may run before [close]; see [to]. */
        private val shortOfTheEnd = total?.let { (it - 1).coerceAtLeast(0) } ?: Long.MAX_VALUE

        /** How far the caller actually got, which on a phase that failed is not the total. */
        private var reached = 0L

        private val repainting = Thread(::repaintUntilClosed, "git-timebraid-progress")

        init {
            open.add(this)
            if (animation != null) {
                cleanUpOnExit()
                // Guarded, because hiding the cursor writes on the caller's thread, and a terminal
                // failing there costs the bar and nothing else, as a failed repaint does. `hide`
                // registers a shutdown hook as well, and registering one throws once shutdown has
                // begun — but that call sits behind a guard a defect in the same class never lets
                // pass twice, so it cannot throw yet. The day that defect is fixed, it will.
                runCatching { terminal?.cursor?.hide(showOnExit = true) }
                repainting.isDaemon = true
                repainting.start()
            }
        }

        /**
         * Hands over the running count, which is all the caller's thread ever does: the drawing
         * happens on [repainting], so a phase is never paced by how often it reports itself.
         *
         * The count stops one short of the total, because reaching a total is what finishes a task,
         * and finishing one is [close]'s to do.
         */
        fun to(count: Long) {
            reached = count
            animation?.update { completed = count.coerceAtMost(shortOfTheEnd) }
        }

        /**
         * What the bar leaves behind: what it was, what it got through, and how long it took.
         *
         * A phase with nothing to count — a spinner — has only the first and the last of those,
         * which is all it ever claimed.
         */
        private fun finishedLine(): String {
            // Locale.ROOT, or a Czech or German JVM writes the separator as a comma — which is
            // correct for those readers and wrong for a line the rest of the program spells in
            // English.
            val elapsed = String.format(
                Locale.ROOT, "%.1fs", (System.nanoTime() - startedAt) / 1_000_000_000.0
            )
            // What was reached, not what was asked for: a phase that failed half way through is
            // one this line still reports, and its total would say it had finished.
            return if (total == null) "$label, $elapsed" else "$label, $reached in $elapsed"
        }

        private fun repaintUntilClosed() {
            while (true) {
                Thread.sleep(REPAINT_MILLIS)
                val again = synchronized(lock) {
                    // A repaint that fails costs this bar and nothing else — never a stack trace
                    // printed across the very output it was drawing on.
                    !closed && runCatching { animation?.refresh(refreshAll = false) }.isSuccess
                }
                if (!again) return
            }
        }

        /** Says what the finished line will say, once the work has produced it. */
        fun relabel(text: String) = synchronized(lock) { label = text }

        fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            open.remove(this)
            // The bar goes and a line takes its place. What a bar shows — how far, how fast, how
            // long is left — stops being a question once the work is done, but what it *reached*
            // is worth keeping, and fifty columns of spent blocks are not the way to keep it. This
            // is how `git` leaves its own transfers behind: one line per task, carrying its counts.
            //
            // A task with a total is completed first: reaching it is what ends the task, and an
            // animation cleared mid-task leaves mordant believing it is still running.
            //
            // Clearing writes, taking the bar off its line, and it does so on the caller's thread,
            // where the repaints' guard does not reach. A terminal failing there costs the bar, as a
            // failed repaint does, and the line is written all the same.
            animation?.let { bar ->
                runCatching {
                    total?.let { end -> bar.update { completed = end } }
                    bar.clear()
                }
            }
            result(finishedLine())
        }
    }

    /**
     * Starts [definition] running, and hands back the thing that will report it.
     *
     * The bar is the optional half. Off a terminal, and on one too narrow to lay a bar out in, there
     * is nothing to draw and nothing is drawn — but the line the phase leaves behind is owed either
     * way, which is what makes a redirected run read like a watched one with the animation taken
     * out. After [stopDrawing] neither is: the run is on its way out.
     */
    private fun draw(definition: ProgressBarDefinition<Unit>, total: Long?, label: String): Drawn? {
        if (level == Level.QUIET || stopped) return null
        // A pty opened without a size says it has no columns, which `script` and some CI runners do,
        // and mordant lays progress out to the width it is given: the alternative to skipping it is
        // repainting a line of nothing several times a second for the length of the phase.
        val on = terminal?.takeIf { it.size.width >= MIN_BAR_WIDTH }
        return Drawn(on?.let { definition.animateOnThread(it, total = total) }, on, total, label)
    }

    /**
     * A task's own name, said of whichever input it is running for.
     *
     * Git and JGit both name the step rather than the repository — `Receiving objects` says nothing
     * about which of three inputs is being received — and the phase heading above covers all of
     * them at once. The name goes in front so the bar answers that on its own.
     *
     * Bracketed rather than followed by a colon, because git's own step names carry colons of their
     * own: `remote: Counting objects` prefixed that way reads as two labels and no owner.
     */
    private fun labelled(task: String, of: String?) = if (of == null) task else "[$of] $task"

    private fun bar(label: String) = progressBarLayout {
        text(label)
        percentage()
        progressBar()
        completed()
        speed(suffix = "/s")
        timeRemaining()
    }

    /**
     * Shows how far one long phase has got, to be called with the running count. It closes itself on
     * the last item, so the caller has nothing to close.
     *
     * It exists because the phases that take the time are the ones that say the least: without it,
     * writing a braid would print its heading and then nothing for the whole of the writing, long
     * enough on a large history to look like a program that has stopped.
     *
     * Mordant draws it, which is why there is no threshold here and no notion of how long a phase is
     * expected to take. A bar repainted over itself costs the same one line of screen whether it is
     * updated twice or a million times, so there is nothing to ration; the animation keeps its own
     * refresh clock on its own thread, so the count can be handed over on every single item without
     * the drawing ever pacing the work. And off a terminal it draws nothing rather than falling back
     * to whole lines: redirected output is read later and in bulk, where a progress count is of no
     * use to anybody. `git` makes the same call: its progress is drawn when stderr is a terminal and
     * not at all otherwise (git-fetch(1), `--progress`).
     */
    fun counter(total: Int, what: String): (Int) -> Unit {
        if (total <= 0) return {}
        val drawn = draw(bar(what), total.toLong(), what) ?: return {}
        return { done ->
            drawn.to(done.toLong())
            // Closed here rather than by the caller, because what prints next is the ref writing
            // that follows in the same phase — and a line printed into a running animation lands
            // in the middle of it.
            if (done >= total) drawn.close()
        }
    }

    /**
     * Runs [work] under a spinner labelled [label], for a stretch that cannot say how far along it
     * is, and leaves the same kind of line behind as a bar does.
     *
     * Some of the longest stretches of a run are these: reading the inputs, planning the braid,
     * flushing a pack and publishing the refs on top of it. None has a count to offer — a pack
     * flush is one call that returns when it returns — and each was several silent seconds under
     * a heading that had already been printed. A spinner claims no more than that something is
     * still happening, which is exactly what is known.
     */
    fun <T> whileWorking(label: String, finished: ((T) -> String)? = null, work: () -> T): T {
        val drawn = draw(progressBarLayout { spinner(Glyphs.spinnerFor(charset)); text(label) }, null, label)
        try {
            val value = work()
            // The counts a step has to report are known only once it is done, and a second call to
            // say them would leave the step two lines, timed from two different starts. So the one
            // line the spinner leaves behind is what carries them.
            finished?.let { drawn?.relabel(it(value)) }
            return value
        } finally {
            drawn?.close()
        }
    }

    /**
     * Draws what a `git` subprocess says about itself, as it says it.
     *
     * Reading a subprocess's output is not the same as swallowing it: what arrives here is git's own
     * progress, already being captured for the log and for the tail a failure is reported with, and
     * drawn on the way past rather than only collected.
     *
     * Only the shape is read, never the words. `Updating files:  18% (7515/40000)` carries its
     * counts in a bracket that no translation moves, so the numbers are taken from there and the
     * label is whatever git called it — in whatever language git is speaking. A segment that does
     * not carry counts, which is most of what a fetch would report, is left alone: the phase heading
     * has already said what is happening, and half a bar is worse than none.
     */
    fun gitProgress(of: String? = null): GitProgress = GitProgress(of)

    /** One subprocess's worth of drawn progress. Hand it to the command; close it when that returns. */
    inner class GitProgress internal constructor(private val of: String?) :
        (String) -> Unit, AutoCloseable {

        private var drawn: Drawn? = null
        private var task: String? = null

        /** Set by anything unexpected, after which this draws no more. See [invoke]. */
        private var givenUp = false

        override fun invoke(segment: String) {
            if (givenUp) return
            try {
                val counts = GIT_COUNTS.find(segment) ?: return
                val (name, done, total) = counts.destructured
                // A total of nothing is not a bar; the digits are bounded above so that no number
                // git might print can fail to be one.
                val size = total.toLong()
                if (size <= 0) return
                if (name != task) {
                    drawn?.close()
                    task = name
                    drawn = draw(bar(labelled(name, of)), size, labelled(name, of))
                }
                drawn?.to(done.toLong().coerceIn(0, size))
            } catch (e: Throwable) {
                // The format being read belongs to a `git` whose version is nobody's to pin here.
                // Whatever still surprises this has to end in no bar rather than in a checkout that
                // reports an error it did not have. A terminal failing under the bar is not that:
                // Drawn's own guards take it, and GIT_COUNTS is bounded so that parsing cannot
                // throw. This is the last resort behind both, for what neither foresaw, and no test
                // calls it on purpose. Once surprised, it stops trying rather than repeating the
                // failure for every one of a hundred redraws.
                givenUp = true
                runCatching { drawn?.close() }
                drawn = null
            }
        }

        override fun close() {
            drawn?.close()
            drawn = null
            task = null
        }
    }

    /**
     * A [ProgressMonitor] for JGit to report a transfer through, drawing one bar per task it names.
     *
     * The fetch is the other phase long enough to look stalled — on a three-repository corpus, one
     * outside this tree, it is 39% of the run, and every second of it was silent, because the only
     * monitor ever handed to it was the one that discards. JGit sizes most of its own tasks, and
     * the ones it cannot it reports as [ProgressMonitor.UNKNOWN], which becomes a spinner.
     */
    fun monitor(of: String? = null): ProgressMonitor = object : ProgressMonitor {

        private var drawn: Drawn? = null

        /** JGit reports an increment; mordant wants the running total. */
        private var done = 0L

        override fun start(totalTasks: Int) = Unit

        override fun beginTask(title: String, totalWork: Int) {
            // JGit is not obliged to end one task before beginning the next.
            endTask()
            done = 0
            val total = if (totalWork == ProgressMonitor.UNKNOWN) null else totalWork.toLong()
            val label = labelled(title, of)
            drawn = if (total == null) {
                draw(progressBarLayout { spinner(Glyphs.spinnerFor(charset)); text(label) }, null, label)
            } else {
                draw(bar(label), total, label)
            }
        }

        override fun update(completed: Int) {
            done += completed
            drawn?.to(done)
        }

        override fun endTask() {
            drawn?.close()
            drawn = null
        }

        override fun isCancelled(): Boolean = false

        override fun showDuration(enabled: Boolean) = Unit
    }

    companion object {

        /**
         * How git writes a sized step of its own progress: `Updating files:  18% (7515/40000)`.
         *
         * The bracket is what is read. Everything ahead of the colon is a translated title and
         * everything between is a percentage that the counts already give — so the pattern anchors
         * on the digits and the slash, which no locale rewrites.
         *
         * Every part is bounded. The counts are read with [String.toLong], and a run of digits long
         * enough to overflow one would throw — which [GitProgress.invoke]'s catch-all turns into no
         * bar rather than into a failed checkout, so what the bound buys is the bar, not the run.
         */
        private val GIT_COUNTS = Regex("""^(.{0,200}?):\s*\d{1,3}%\s*\((\d{1,15})/(\d{1,15})\)""")

        /** What a heading is ruled to when nothing better is known — a pipe, a file, a CI log. */
        const val DEFAULT_WIDTH = 79

        /** Narrower than this there is nothing progress could usefully say, so none is drawn. */
        private const val MIN_BAR_WIDTH = 30

        /** `DECTCEM` set: what mordant writes to show the cursor, written here when it will not. */
        private const val SHOW_CURSOR = "\u001B[?25h"

        /**
         * How long between repaints, in milliseconds. Mordant's own animator runs a layout holding
         * a `progressBar` at its 30 Hz, which is what these hold.
         */
        private const val REPAINT_MILLIS = 1000L / 30

        fun level(quiet: Boolean, verbose: Boolean): Level = when {
            quiet -> Level.QUIET
            verbose -> Level.VERBOSE
            else -> Level.NORMAL
        }

        /**
         * A terminal that draws on stderr, or `null` when nobody is watching stderr.
         *
         * Mordant writes to stdout, and here stdout is the plan summary. Every print is therefore
         * rewritten on the way out to say it is stderr — the flag is already on [PrintRequest], so
         * nothing has to be reimplemented, and wrapping the interface [base] already uses keeps
         * whatever a test harness put there.
         *
         * `interactive` has to be overridden for the same reason. Mordant decides it by looking at
         * stdout, and the run this matters for is the one where stdout is a file and stderr is not.
         *
         * The theme is chosen here rather than left at mordant's default, because the default is
         * drawn in characters an OEM console cannot encode; [Glyphs] has the whole of that argument.
         */
        fun stderrTerminal(
            base: Terminal,
            charset: Charset = Glyphs.stderrCharset,
        ): Terminal? {
            if (!Tty.stderrIsInteractive) return null
            return Terminal(
                interactive = true,
                theme = Glyphs.themeFor(charset),
                terminalInterface = asStderr(base.terminalInterface),
            )
        }

        /** [base] with every print on it redirected to stderr. Separate so a test can drive it. */
        fun asStderr(base: TerminalInterface): TerminalInterface =
            object : TerminalInterface by base {
                override fun completePrintRequest(request: PrintRequest) {
                    base.completePrintRequest(
                        PrintRequest(request.text, request.trailingLinebreak, stderr = true)
                    )
                }
            }
    }
}
