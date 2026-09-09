package cz.loplex.timebraid

import cz.loplex.timebraid.cli.MergeCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Every string literal the program can print is spelled in ASCII.
 *
 * A JVM on Windows picks the charset for `System.out` from where the stream goes: an OEM code page
 * (cp437, cp852) for a console, the ANSI one (cp1252) for a pipe. The OEM pages have no em dash and
 * no ellipsis, and what the encoder does with a character its charset lacks is substitute a `?` —
 * so a message written with one loses it, and loses it only for the user running the tool by hand.
 * Redirected output keeps it, which is why a CI log is no evidence either way.
 *
 * The check reads the compiled classes rather than the sources: a string literal is one the
 * compiler put in the constant pool, so comments and KDoc — read in an editor, where the dash is
 * worth having — are out of scope without a rule having to describe them. A `Char` literal is not
 * a string, though: it compiles to a number, which nothing in a class file tells apart from any
 * other, so a `'─'` handed to `padEnd` or `repeat` goes unseen here, as does a character made at
 * run time.
 */
class MessageCharsetTest {

    @Test
    fun `no compiled string holds a character a console might not have`() {
        val classes = Path.of(MergeCommand::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(classes.isDirectory(), "expected a directory of compiled classes, got $classes")

        val offenders = mutableListOf<String>()
        var scanned = 0
        Files.walk(classes).use { tree ->
            for (file in tree.filter { it.extension == "class" }) {
                scanned++
                for (text in stringConstants(Files.readAllBytes(file))) {
                    val outside = text.filter { it.code > 127 }.toSortedSet()
                    if (outside.isNotEmpty()) {
                        offenders += "${file.name}: $outside in \"${text.take(60)}\""
                    }
                }
            }
        }

        // A run that found nothing because it walked the wrong tree would pass without checking
        // anything, so what it looked at is asserted too.
        assertTrue(scanned > 0, "no class files under $classes")
        assertEquals(emptyList<String>(), offenders, "write these in ASCII, or the console eats them")
    }

    /**
     * The string literals in one class file: the `CONSTANT_Utf8` entries a `CONSTANT_String` points
     * at, and nothing else.
     *
     * The pool holds far more text than that — type names, descriptors, and the packed Kotlin
     * `@Metadata`, which is not ASCII and would be reported every time if the entries were read
     * without asking what refers to them.
     *
     * An entry is modified UTF-8, which differs from the ordinary kind in two places. It writes
     * U+0000 as the two bytes `C0 80`, which ordinary UTF-8 refuses, so those are put back before
     * decoding; and it writes a character beyond U+FFFF as two surrogates, which are outside ASCII
     * either way, so a string decoded imperfectly for that is one the test was going to report.
     */
    private fun stringConstants(bytes: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(bytes)
        buf.position(8) // the magic number and the two version shorts
        val count = buf.short.toInt() and 0xFFFF
        val utf8 = HashMap<Int, String>()
        val referenced = mutableListOf<Int>()

        var slot = 1
        while (slot < count) {
            when (val tag = buf.get().toInt()) {
                1 -> {
                    val text = ByteArray(buf.short.toInt() and 0xFFFF)
                    buf.get(text)
                    utf8[slot] = String(withNul(text), UTF_8)
                }
                8 -> referenced += buf.short.toInt() and 0xFFFF
                7, 16, 19, 20 -> buf.skip(2)
                15 -> buf.skip(3)
                3, 4, 9, 10, 11, 12, 17, 18 -> buf.skip(4)
                // A long or a double is written once and numbered twice, a quirk the format is
                // stuck with; miss it and every entry after one is read at the wrong offset.
                5, 6 -> {
                    buf.skip(8)
                    slot++
                }
                else -> throw AssertionError("unknown constant pool tag $tag at slot $slot")
            }
            slot++
        }

        return referenced.mapNotNull(utf8::get)
    }

    /** [bytes] with each modified-UTF-8 `C0 80` put back as the single NUL it stands for. */
    private fun withNul(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == 0xC0.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0x80.toByte()) {
                out.write(0)
                i += 2
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun ByteBuffer.skip(bytes: Int) = position(position() + bytes)
}
