package cz.loplex.timebraid.cli

import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.rendering.plus
import com.github.ajalt.mordant.widgets.Spinner
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * What the progress animation may be drawn with, asked of the charset it is going to be encoded in.
 *
 * The bar and the spinner are the one part of the output this program does not spell itself:
 * `MessageCharsetTest` holds every string of ours to ASCII, for the reason written there — a JVM on
 * Windows encodes a console in an OEM code page, and an encoder substitutes a `?` for a character
 * its charset lacks — but mordant's own drawing characters are outside that check, being mordant's.
 * `Theme.Default` draws the bar with a heavy box-drawing character and `Spinner.Dots` cycles through
 * braille, and on a cp437 or cp852 console both come out as rows of question marks.
 *
 * Mordant ships the ASCII alternatives, so the whole of the work is deciding when to ask for them,
 * and that decision is the part worth reading:
 *
 * **The question is put to the encoder, not to the platform.** No `os.name`, no list of code pages,
 * no "legacy console" flag. [Charset.newEncoder] already knows whether a character survives, so
 * asking it answers correctly for a `chcp 65001` console, for a locale nobody here thought about,
 * and for a terminal that gains the glyphs in some future version — and needs no maintenance when
 * any of that changes. It also makes the whole decision a pure function of a charset, which is what
 * lets it be tested without the platform it is about.
 *
 * **Colour is a different question and is left alone.** `Theme.PlainAscii` would serve on its own,
 * but it is `Theme.Plain` underneath and so carries no styles: taking it whole would drop colour,
 * which an OEM console does perfectly well. What is wrong there is the characters, so the characters
 * are what changes — mordant's `+` keeps the left theme's styles when the right one has none.
 *
 * **The bar and the spinner are asked separately, because the answers differ.** It is tempting to
 * settle both with one "does this console do unicode" flag, and cp932 — the default console code
 * page on a Japanese Windows — is the counterexample: it carries the box-drawing character and has
 * no braille at all. One flag would either strand the spinner as question marks or give up a bar
 * that would have drawn.
 */
internal object Glyphs {

    /**
     * The charset `System.err` is encoded in.
     *
     * Three property names because the JDK moved it: `stderr.encoding` is where 19 and later publish
     * it, `sun.stderr.encoding` is the same value on the 17 this program targets, and
     * `native.encoding` is the platform's answer for streams in general. [Charset.defaultCharset] is
     * the last resort rather than the first guess: since JDK 18 it reads UTF-8 whatever the console
     * is encoded in, which is the wrong answer to exactly the question being asked here.
     */
    val stderrCharset: Charset by lazy { consoleCharset(System::getProperty) }

    /**
     * [stderrCharset] as read through [property], which a test can stand in for: the system
     * properties are the JVM's, and a suite running on a UTF-8 console cannot change them.
     */
    internal fun consoleCharset(property: (String) -> String?): Charset =
        sequenceOf("stderr.encoding", "sun.stderr.encoding", "native.encoding")
            .mapNotNull { property(it) }
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .firstOrNull() ?: Charset.defaultCharset()

    /**
     * Which charset the drawing is decided against, given what the caller asked for.
     *
     * The two flags do not switch a mode on: they answer the one question this file asks, which is
     * what the console can take, and the way to say "nothing but ASCII" or "anything at all" is to
     * name a charset that it is true of. So a forced run goes down the same path an automatic one
     * does, and there is no second way of choosing a theme to keep in step with the first.
     *
     * [forceAscii] wins if both are somehow given; the command line refuses that pair before it gets
     * here.
     *
     * @param console what the console itself reports, [stderrCharset] unless a test names another.
     */
    fun charsetFor(forceAscii: Boolean, forceUnicode: Boolean, console: Charset = stderrCharset): Charset =
        when {
            forceAscii -> StandardCharsets.US_ASCII
            forceUnicode -> StandardCharsets.UTF_8
            else -> console
        }

    /** The theme to draw a bar with, on output encoded in [charset]. */
    fun themeFor(charset: Charset): Theme =
        if (encodable(charset, barGlyphs(Theme.Default))) Theme.Default else ASCII_THEME

    /**
     * A spinner for output encoded in [charset].
     *
     * A fresh one each time: a [Spinner] carries the frame it is on, so two tasks sharing one would
     * share a tick.
     */
    fun spinnerFor(charset: Charset): Spinner =
        if (encodable(charset, BRAILLE)) Spinner.Dots() else Spinner.Lines()

    /**
     * Mordant's defaults with the bar drawn in ASCII, and everything else — the colours above all —
     * as it was.
     *
     * The strings it does not draw with come across too, `+` merging whole themes rather than the
     * three entries in question. Harmless, because what is checked and what is drawn are the same
     * keys: a string this program never renders is never encoded, whatever it holds.
     *
     * That equality is the load-bearing part, and is worth stating because the obvious shortcut is
     * false. `Theme.PlainAscii` is documented as using "only ASCII characters" and does not: it
     * overrides most of the default's non-ASCII strings but inherits `hr.rule`, which stays at
     * U+2500 — while `markdown.h2.rule`, the same character, is overridden. So a widget added here
     * that draws through the theme cannot assume the fallback covers it. Extend the check to
     * whatever it draws with, and let the test that already asserts the fallback is encodable say
     * so for the new keys too.
     */
    private val ASCII_THEME: Theme = Theme.Default + Theme.PlainAscii

    /**
     * What a bar is drawn with, according to [theme] itself.
     *
     * Read from the theme rather than written down here, which is the same rule this file follows
     * everywhere: a list of characters copied out of a library is wrong the moment the library
     * changes one, and wrong silently. The prefix is mordant's own namespace for them, so a string
     * added under it is covered without anything here being touched.
     */
    private fun barGlyphs(theme: Theme): String =
        theme.strings.filterKeys { it.startsWith("progressbar.") }.values.joinToString("")

    /**
     * The braille block, which is where `Spinner.Dots` takes its ten frames from.
     *
     * The block rather than the frames, because mordant keeps them private and there is nothing to
     * read them from. Copying the ten out would be the very thing [barGlyphs] avoids, so what is
     * asked instead is a question no future change to that list can invalidate: a charset either
     * carries braille or does not, and none in practice carries part of it.
     */
    private val BRAILLE: String = String(CharArray(0x100) { (0x2800 + it).toChar() })

    /** Whether [charset] can represent every character of [text] rather than substituting for one. */
    private fun encodable(charset: Charset, text: String): Boolean =
        charset.canEncode() && charset.newEncoder().canEncode(text)
}
