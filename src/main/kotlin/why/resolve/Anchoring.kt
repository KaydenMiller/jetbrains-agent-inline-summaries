package why.resolve

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The `.why` anchor hash. Kotlin half of the two implementations required by
 * `skills/why-notes/writer/HASHING.md`; the other half is `reference_hash.py` in that
 * same directory (W-11).
 *
 * `HASHING.md` is normative and supersedes `PLAN.md` section 4. Section numbers in the
 * comments below refer to it. `skills/why-notes/writer/vectors.json` is the contract
 * between the two implementations and is run in full by `AnchoringTest`.
 *
 * Nothing here is language-aware: no parsing, no Program Structure Interface, no
 * `Document`, no `VirtualFile`. Text in, six hex characters out.
 */

/** Error codes from HASHING.md sections 2 and 5. Mirrors the reference implementation's `HashRangeError.code`. */
enum class HashErrorCode {
    /** Section 5: `start > end`, or a range that is not expressible as integers. */
    INVALID_RANGE,

    /** Section 5: `start < 1`. Lines are 1-based; this is never clamped. */
    START_BELOW_ONE,

    /** Section 2: the bytes are not valid UTF-8. Strict decoding, no fallback. */
    INVALID_ENCODING,
}

/** A range or an encoding the caller must not have asked for. See [HashErrorCode]. */
class HashRangeError(val code: HashErrorCode, message: String) :
    IllegalArgumentException("$code: $message")

object Anchoring {

    /** U+FEFF. Stripped when leading (section 2.2); content anywhere else. */
    const val BOM: Char = '\uFEFF'

    /** SHA-256 of zero bytes, truncated: the hash of any range with no surviving lines (section 8). */
    val EMPTY_HASH: String = hashContent("")

    /**
     * Section 3 -- the whitespace set is **exactly** these six code points:
     * U+0009, U+000A, U+000B, U+000C, U+000D, U+0020.
     *
     * Deliberately not [Char.isWhitespace], which follows `Character.isWhitespace`:
     * that excludes U+00A0 and U+2007 but includes U+001C..U+001F, and Python's
     * `str.isspace` disagrees with it in both directions. Any such disagreement would
     * make one implementation drift a note the other calls solid, with no visible cause.
     * Everything outside this set is content, U+00A0 and U+200B included.
     */
    private fun isWhitespace(c: Char): Boolean =
        c == '\u0009' || c == '\u000A' || c == '\u000B' || c == '\u000C' || c == '\u000D' || c == '\u0020'

    /**
     * Section 4 -- file text to logical lines: CRLF to LF, then lone CR to LF, then drop
     * one trailing LF, then split. Lines carry no terminator.
     *
     * Deliberately not [String.lines], which keeps the empty string after a trailing
     * terminator and would invent a phantom final line on every file that ends in a
     * newline. A leading [BOM] is stripped here (section 2.2) so every entry point gets it.
     */
    fun splitLines(text: String): List<String> {
        var t = if (text.startsWith(BOM)) text.substring(1) else text
        t = t.replace("\r\n", "\n").replace('\r', '\n')
        if (t.isEmpty()) return emptyList()
        if (t.endsWith("\n")) t = t.substring(0, t.length - 1)
        return t.split('\n')
    }

    /**
     * Section 6 -- trim leading and trailing whitespace, collapse every internal run to
     * one U+0020. Equivalent to splitting on the character class
     * `[\t\n\u000B\u000C\r ]+`, dropping empty parts and joining with
     * one space; written as a scan because the resolver's sweep calls it once per line
     * of the document.
     */
    fun normaliseLine(line: String): String {
        val out = StringBuilder(line.length)
        var pendingSpace = false
        for (c in line) {
            if (isWhitespace(c)) {
                // A run before any content is the leading trim; a run with nothing
                // after it stays pending forever, which is the trailing trim.
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(c)
            }
        }
        return out.toString()
    }

    /** Sections 5 to 7 over raw lines: normalised content of the 1-based inclusive range. */
    fun normalise(lines: List<String>, start: Int, end: Int): String =
        join(lines, start, end) { normaliseLine(it) }

    /**
     * Section 7 over lines that are already section-6-normalised. Same result as
     * [normalise] on the corresponding raw lines; exists so the resolver can normalise a
     * document once and then hash many overlapping windows out of it.
     */
    fun joinNormalised(normalisedLines: List<String>, start: Int, end: Int): String =
        join(normalisedLines, start, end) { it }

    private inline fun join(lines: List<String>, start: Int, end: Int, transform: (String) -> String): String {
        // Section 5: validate the requested values, then clamp. The asymmetry is deliberate.
        if (start < 1) throw HashRangeError(HashErrorCode.START_BELOW_ONE, "start=$start is less than 1")
        if (start > end) throw HashRangeError(HashErrorCode.INVALID_RANGE, "start=$start is greater than end=$end")
        val last = minOf(end, lines.size) // end past end of file clamps
        val kept = StringBuilder()
        for (i in start..last) { // start past end of file selects nothing: empty, not an error
            val norm = transform(lines[i - 1])
            if (norm.isEmpty()) continue // section 7.1
            if (kept.isNotEmpty()) kept.append('\n') // section 7.2: no leading or trailing terminator
            kept.append(norm)
        }
        return kept.toString()
    }

    /** Section 8 -- SHA-256 of the UTF-8 bytes of already-normalised content, lowercase hex, first 6. */
    fun hashContent(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
        val hex = StringBuilder(6)
        for (i in 0 until 3) { // 3 bytes is exactly the 6 characters section 8 keeps
            hex.append(HEX[(digest[i].toInt() shr 4) and 0xF])
            hex.append(HEX[digest[i].toInt() and 0xF])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** Anchor hash of `[start, end]` in already-decoded file text. The plugin's entry point. */
    fun hashText(text: String, start: Int, end: Int): String =
        hashContent(normalise(splitLines(text), start, end))

    /**
     * Anchor hash of `[start, end]` in raw file bytes. Section 2.1: strict UTF-8, so
     * invalid bytes are [HashErrorCode.INVALID_ENCODING] rather than a silent
     * replacement character that would diverge from Python where it is hardest to see.
     */
    fun hashBytes(data: ByteArray, start: Int, end: Int): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (e: CharacterCodingException) {
            throw HashRangeError(HashErrorCode.INVALID_ENCODING, "file is not valid UTF-8: ${e.message}")
        }
        return hashText(text, start, end)
    }
}
