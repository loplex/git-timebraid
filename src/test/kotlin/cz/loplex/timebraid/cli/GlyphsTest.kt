package cz.loplex.timebraid.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.Terminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8

/**
 * That nothing is drawn in a character the console it is drawn on cannot encode.
 *
 * Windows is what this is about and no Windows is needed to test it, which is the point of putting
 * the question to a [Charset] rather than to the platform: the OEM code pages a Windows console uses
 * are ordinary charsets that any JVM can encode with, so the decision can be exercised here exactly
 * as it would be there.
 */
class GlyphsTest {

    /** The block `Spinner.Dots` takes its frames from, which the ASCII fallback has none of. */
    private val BRAILLE_BLOCK = '\u2800'..'\u28ff'

    /** The OEM pages a Windows console picks, and the ANSI one a JVM uses for a redirected stream. */
    private val windowsPages = listOf("cp437", "cp852", "cp1252").map { Charset.forName(it) }

    @Test
    fun `a console that can encode mordant's own characters keeps them`() {
        assertSame(Theme.Default, Glyphs.themeFor(UTF_8), "UTF-8 draws mordant's default bar")
        assertTrue(
            spinnerFrames(UTF_8).all { it in BRAILLE_BLOCK },
            "UTF-8 draws mordant's default spinner, which is braille",
        )
    }

    @Test
    fun `a console that cannot is drawn in ASCII instead`() {
        for (page in windowsPages) {
            assertTrue(
                page.newEncoder().canEncode(barGlyphs(Glyphs.themeFor(page))),
                "$page cannot encode the bar it was given",
            )
            assertTrue(
                page.newEncoder().canEncode(spinnerFrames(page)),
                "$page cannot encode the spinner it was given",
            )
        }
    }

    /**
     * The bar and the spinner are decided separately, and cp932 — a Japanese Windows console — is
     * why: it carries the box-drawing character mordant's bar is drawn with and has no braille at
     * all. One answer for both would either leave the spinner as question marks or give up a bar
     * that would have drawn perfectly well.
     */
    @Test
    fun `each is answered on its own, since a console may have one and not the other`() {
        val cp932 = Charset.forName("cp932")
        assertSame(Theme.Default, Glyphs.themeFor(cp932), "cp932 has the bar character")
        assertEquals(
            "-/\\|", spinnerFrames(cp932).toSortedSet().joinToString(""),
            "cp932 has no braille, so the spinner is the four ASCII frames",
        )
        assertTrue(
            cp932.newEncoder().canEncode(spinnerFrames(cp932)),
            "cp932 cannot encode the spinner it was given",
        )
    }

    /**
     * A theme's characters are designed together, so the fallback takes them together.
     *
     * Swapping only the characters a charset cannot encode looks equivalent and is not. The
     * default's `progressbar.separator` is a space, which every charset carries, so a per-character
     * swap keeps it — while the ASCII spelling makes it `>` precisely because a bar drawn in `#`
     * needs a visible head. The result of being clever there is `#######` with no head, and a bar
     * that no longer says where it has got to.
     */
    @Test
    fun `the ASCII bar keeps its head, the characters being a set rather than a list`() {
        val fallback = Glyphs.themeFor(Charset.forName("cp437"))
        for (key in listOf("progressbar.pending", "progressbar.complete", "progressbar.separator")) {
            assertEquals(
                Theme.PlainAscii.strings[key], fallback.strings[key],
                "$key is not what the ASCII bar is drawn with",
            )
        }
    }

    /**
     * Only the characters change. `Theme.PlainAscii` would serve on its own but carries no styles,
     * and colour is not what an OEM console is short of.
     */
    @Test
    fun `falling back to ASCII gives up no colour`() {
        assertEquals(
            Theme.Default.styles,
            Glyphs.themeFor(Charset.forName("cp437")).styles,
            "the ASCII fallback dropped a style",
        )
    }

    /** Whatever `System.err` turns out to be, the drawing that goes there survives it. */
    @Test
    fun `the charset this run would actually draw in is one it can draw in`() {
        val charset = Glyphs.stderrCharset
        assertTrue(
            charset.newEncoder().canEncode(barGlyphs(Glyphs.themeFor(charset))),
            "this run would draw a bar $charset cannot encode",
        )
        assertTrue(
            charset.newEncoder().canEncode(spinnerFrames(charset)),
            "this run would draw a spinner $charset cannot encode",
        )
    }

    /**
     * The two options do not add a second way of choosing: they name the charset the same decision
     * is then made against, so a forced run and an automatic one go down one path.
     */
    @Test
    fun `asking outright is asking about a different console, not taking a different route`() {
        val forcedAscii = Glyphs.charsetFor(forceAscii = true, forceUnicode = false)
        // Asked of ASCII itself, not of the charset --ascii handed back: a theme is chosen to suit
        // whatever charset it is given, so that question answers yes for any of them.
        assertTrue(
            US_ASCII.newEncoder().canEncode(barGlyphs(Glyphs.themeFor(forcedAscii))),
            "--ascii drew something outside ASCII",
        )
        assertEquals(
            "-/\\|", spinnerFrames(forcedAscii).toSortedSet().joinToString(""),
            "--ascii drew a spinner outside ASCII",
        )

        val forcedUnicode = Glyphs.charsetFor(forceAscii = false, forceUnicode = true)
        // The console it asks about, which is the whole of what the flag does: the bar and the
        // spinner below would come out the same for any charset that carries their glyphs.
        assertEquals(UTF_8, forcedUnicode, "--no-ascii asked about some other console")
        assertSame(
            Theme.Default, Glyphs.themeFor(forcedUnicode),
            "--no-ascii settled for less than mordant's own bar",
        )
        assertTrue(
            spinnerFrames(forcedUnicode).all { it in BRAILLE_BLOCK },
            "--no-ascii settled for less than mordant's own spinner",
        )

        assertSame(
            Glyphs.stderrCharset, Glyphs.charsetFor(forceAscii = false, forceUnicode = false),
            "with neither given, the console is what decides",
        )
    }

    /**
     * The same, on a console that is not UTF-8. Asked of this run's own console, the flags cannot be
     * told from it wherever stderr is UTF-8 already, as it is on the machines this suite runs on.
     */
    @Test
    fun `on a console that is not UTF-8, each flag still names its own charset`() {
        val cp437 = Charset.forName("cp437")
        assertEquals(UTF_8, Glyphs.charsetFor(forceAscii = false, forceUnicode = true, console = cp437))
        assertEquals(US_ASCII, Glyphs.charsetFor(forceAscii = true, forceUnicode = false, console = cp437))
        assertEquals(cp437, Glyphs.charsetFor(forceAscii = false, forceUnicode = false, console = cp437))
    }

    @Test
    fun `the console's charset is read from the first property that names one`() {
        val properties = mapOf("stderr.encoding" to "no-such-charset", "sun.stderr.encoding" to "cp852")
        assertEquals(Charset.forName("cp852"), Glyphs.consoleCharset { properties[it] })
        assertEquals(Charset.forName("cp437"), Glyphs.consoleCharset { if (it == "native.encoding") "cp437" else null })
        assertEquals(Charset.defaultCharset(), Glyphs.consoleCharset { null })
        // All three usable at once, as on a Windows console whose code page is not the system's:
        // the first wins, and without it the second.
        val all = mapOf(
            "stderr.encoding" to "cp852", "sun.stderr.encoding" to "cp437", "native.encoding" to "windows-1252",
        )
        assertEquals(Charset.forName("cp852"), Glyphs.consoleCharset { all[it] })
        assertEquals(Charset.forName("cp437"), Glyphs.consoleCharset { if (it == "stderr.encoding") null else all[it] })
    }

    /** What a bar is drawn with, read back from the theme the same way the decision reads it. */
    private fun barGlyphs(theme: Theme): String =
        theme.strings.filterKeys { it.startsWith("progressbar.") }.values.joinToString("")

    /**
     * Every frame the chosen spinner cycles through, as text.
     *
     * Rendered rather than read: mordant keeps a spinner's frames private, so what is asked here is
     * what would reach the console — which is the thing that has to be encodable anyway.
     */
    private fun spinnerFrames(charset: Charset): String {
        val terminal = Terminal(ansiLevel = AnsiLevel.NONE)
        val spinner = Glyphs.spinnerFor(charset)
        return (0 until 16).joinToString("") {
            spinner.tick = it
            terminal.render(spinner.currentFrame)
        }
    }
}
