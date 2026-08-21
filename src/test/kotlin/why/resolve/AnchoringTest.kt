package why.resolve

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The interop gate for `HASHING.md`: every vector in `vectors.json` run through the
 * Kotlin port, plus the two cases section 12 says the vector format cannot express
 * (invalid UTF-8 and a leading byte-order mark). All three files live in
 * `skills/why-notes/writer/`, which is also what ships as the Claude Code skill.
 *
 * Plain JUnit 4, no platform fixture: the hash takes a `String`, so nothing here needs an
 * IDE. If this file disagrees with `verify_vectors.py` on one byte, every note in the
 * shipped product renders as drifted, so a failure names the vector that diverged.
 */
class AnchoringTest {

    @Test
    fun everyVectorInTheContractFileReproducesItsRecordedHash() {
        val vectors = ObjectMapper().readTree(vectorsFile())
        assertTrue("vectors.json must be a non-empty JSON array", vectors.isArray && vectors.size() > 0)
        // W-2 shipped 32 vectors. Growth is fine; silent shrinkage is not.
        assertTrue("expected at least 32 vectors, found ${vectors.size()}", vectors.size() >= 32)

        val failures = ArrayList<String>()
        val actual = LinkedHashMap<String, String?>() // name -> hash, null when the vector errored

        for (vector in vectors) {
            val name = vector["name"].asText()
            if (actual.containsKey(name)) failures += "$name: duplicate vector name"

            val text = buildText(vector)
            var hash: String? = null
            var code: String? = null
            try {
                hash = Anchoring.hashText(text, vector["start"].asInt(), vector["end"].asInt())
            } catch (e: HashRangeError) {
                code = e.code.name
            }
            actual[name] = hash

            val expectedHash = vector["expected_hash"]?.takeUnless { it.isNull }?.asText()
            if (expectedHash == null) {
                val expectedError = vector["expected_error"].asText()
                if (code != expectedError) {
                    failures += "$name: expected error $expectedError, got ${code ?: "hash $hash"}"
                }
            } else if (code != null) {
                failures += "$name: expected hash $expectedHash, got error $code"
            } else if (hash != expectedHash) {
                failures += "$name: expected hash $expectedHash, got $hash"
            } else if (!SIX_LOWER_HEX.matches(hash)) {
                failures += "$name: hash '$hash' is not 6 lowercase hexadecimal characters"
            }
        }

        // The relational claims are the point of the paired vectors: checked against the
        // hashes this implementation computed, not against the recorded expectations.
        for (vector in vectors) {
            val name = vector["name"].asText()
            for ((key, mustBeEqual) in listOf("same_as" to true, "differs_from" to false)) {
                val other = vector[key]?.asText() ?: continue
                if (!actual.containsKey(other)) {
                    failures += "$name: $key names unknown vector $other"
                    continue
                }
                val equal = actual[name] == actual[other]
                if (equal != mustBeEqual) {
                    failures += "$name: $key $other violated (${actual[name]} vs ${actual[other]})"
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${vectors.size()} vectors diverged from skills/why-notes/writer/vectors.json:\n" +
                failures.joinToString("\n"))
        }
        println("AnchoringTest: ${vectors.size()} vectors from ${vectorsFile()} reproduced exactly")
    }

    /** HASHING.md section 12: invalid UTF-8 cannot live in the vector file, so it is hand-ported. */
    @Test
    fun invalidUtf8BytesAreAnInvalidEncodingError() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        try {
            val hash = Anchoring.hashBytes(bytes, 1, 1)
            fail("expected INVALID_ENCODING for 0xFF 0xFE, got hash $hash")
        } catch (e: HashRangeError) {
            assertEquals(HashErrorCode.INVALID_ENCODING, e.code)
        }
        // A truncated multi-byte sequence is the realistic version of the same failure.
        try {
            Anchoring.hashBytes(byteArrayOf(0xE6.toByte(), 0x97.toByte()), 1, 1)
            fail("expected INVALID_ENCODING for a truncated UTF-8 sequence")
        } catch (e: HashRangeError) {
            assertEquals(HashErrorCode.INVALID_ENCODING, e.code)
        }
        // Valid UTF-8 of the same characters is not an error.
        assertEquals(
            Anchoring.hashText("var msg = \"x\";\n", 1, 1),
            Anchoring.hashBytes("var msg = \"x\";\n".toByteArray(Charsets.UTF_8), 1, 1),
        )
    }

    /** HASHING.md section 2.2 / section 12: a leading byte-order mark is stripped. */
    @Test
    fun leadingByteOrderMarkIsStrippedAndElsewhereIsContent() {
        val bom = Char(0xFEFF).toString()
        assertEquals(listOf("a"), Anchoring.splitLines(bom + "a\n"))
        assertEquals(Anchoring.hashText("a\n", 1, 1), Anchoring.hashText(bom + "a\n", 1, 1))
        // Visual Studio writes the mark as EF BB BF on C# files; the byte entry point agrees.
        assertEquals(
            Anchoring.hashText("using System;\n", 1, 1),
            Anchoring.hashBytes((bom + "using System;\n").toByteArray(Charsets.UTF_8), 1, 1),
        )
        // Only one, only at the front.
        assertEquals(listOf(bom + "a"), Anchoring.splitLines(bom + bom + "a\n"))
        assertTrue(Anchoring.hashText("a" + bom + "b\n", 1, 1) != Anchoring.hashText("ab\n", 1, 1))
    }

    /** HASHING.md section 4's table, so a splitting divergence is visible without hashing. */
    @Test
    fun lineSplittingMatchesTheSpecificationTable() {
        assertEquals(emptyList<String>(), Anchoring.splitLines(""))
        assertEquals(listOf(""), Anchoring.splitLines("\n"))
        assertEquals(listOf("a"), Anchoring.splitLines("a"))
        assertEquals(listOf("a"), Anchoring.splitLines("a\n"))
        assertEquals(listOf("a", ""), Anchoring.splitLines("a\n\n"))
        assertEquals(listOf("a", "b"), Anchoring.splitLines("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), Anchoring.splitLines("a\rb"))
        assertEquals(listOf("a", "b"), Anchoring.splitLines("a\rb\r"))
        // String.lines() would produce a phantom trailing entry here; this must not.
        assertEquals(2, Anchoring.splitLines("a\nb\n").size)
    }

    /** HASHING.md section 6, including the boundary the whitespace set is drawn at. */
    @Test
    fun lineNormalisationTrimsAndCollapsesOnlyTheSixCodePoints() {
        assertEquals("if (x) {", Anchoring.normaliseLine("\tif (x) {  "))
        assertEquals("a b", Anchoring.normaliseLine("a\t\t " + Char(0x0C) + "b"))
        assertEquals("a b", Anchoring.normaliseLine("a" + Char(0x0B) + "b"))
        assertEquals("", Anchoring.normaliseLine(Char(0x0C).toString()))
        assertEquals("", Anchoring.normaliseLine("  \t "))
        // Content, not whitespace: no-break space, zero width space, ideographic space,
        // next line, and the C1 range Python's str.isspace() would have swallowed.
        for (code in listOf(0x00A0, 0x200B, 0x2007, 0x3000, 0x0085, 0x001C, 0x001F)) {
            val line = "a" + Char(code) + "b"
            assertEquals("U+%04X must survive as content".format(code), line, Anchoring.normaliseLine(line))
        }
        assertEquals("x", Anchoring.normalise(listOf("", "\t", "  ", "x"), 1, 4))
    }

    /** HASHING.md section 9's worked example, hash included. */
    @Test
    fun workedExampleFromTheSpecification() {
        val example = "public int Total(int[] xs)\n{\n\tint sum = 0;\n  \t  \n" +
            "\tforeach (var x in xs)\n\t\tsum  +=  x;\n\treturn sum;\n}\n"
        assertEquals(
            "int sum = 0;\nforeach (var x in xs)\nsum += x;",
            Anchoring.normalise(Anchoring.splitLines(example), 3, 6),
        )
        assertEquals("55046e", Anchoring.hashText(example, 3, 6))
        assertEquals("e3b0c4", Anchoring.EMPTY_HASH)
        assertEquals("e3b0c4", Anchoring.hashText("", 1, 1))
    }

    /** HASHING.md section 5: the asymmetry between the two ends of the range. */
    @Test
    fun rangeValidationIsAsymmetric() {
        val text = "a\nb\n"
        assertEquals(Anchoring.hashText(text, 1, 2), Anchoring.hashText(text, 1, Int.MAX_VALUE))
        assertEquals("e3b0c4", Anchoring.hashText(text, 5, 6)) // start past end of file: empty
        for ((start, end, code) in listOf(
            Triple(2, 1, HashErrorCode.INVALID_RANGE),
            Triple(0, 3, HashErrorCode.START_BELOW_ONE),
            Triple(-1, -1, HashErrorCode.START_BELOW_ONE),
        )) {
            try {
                Anchoring.hashText(text, start, end)
                fail("expected $code for ($start, $end)")
            } catch (e: HashRangeError) {
                assertEquals("($start, $end)", code, e.code)
            }
        }
    }

    private fun buildText(vector: JsonNode): String {
        val lines = vector["input_lines"].map { it.asText() }
        if (lines.isEmpty()) return ""
        val ending = vector["line_ending"].asText()
        val trailing = vector["trailing_newline"]?.asBoolean(true) ?: true
        return lines.joinToString(ending) + if (trailing) ending else ""
    }

    private companion object {
        val SIX_LOWER_HEX = Regex("^[0-9a-f]{6}$")

        /**
         * `skills/why-notes/writer/vectors.json`, found by walking up from the working
         * directory and from this class's own location. No absolute path is hard-coded, so
         * the test survives being run from a different directory or a different checkout.
         */
        fun vectorsFile(): File = contractFile("vectors.json")

        fun contractFile(name: String): File {
            val seeds = listOfNotNull(
                System.getProperty("user.dir")?.let { File(it) },
                runCatching {
                    File(AnchoringTest::class.java.protectionDomain.codeSource.location.toURI())
                }.getOrNull(),
            )
            for (seed in seeds) {
                var dir: File? = seed.absoluteFile.let { if (it.isDirectory) it else it.parentFile }
                while (dir != null) {
                    val candidate = File(dir, "skills/why-notes/writer/$name")
                    if (candidate.isFile) return candidate
                    dir = dir.parentFile
                }
            }
            fail("could not find skills/why-notes/writer/$name upward from any of: $seeds")
            error("unreachable")
        }
    }

    /**
     * `skills/why-notes/writer/line_split_cases.json` — section 4's splitting table,
     * shared with `verify_vectors.py` next to it.
     *
     * This exists because no hash vector can catch a section 4 violation: a phantom
     * trailing line normalises to empty and is discarded by section 7, so every hash in
     * `vectors.json` stays green while the line COUNT diverges between the two
     * implementations. Demonstrated by replacing [Anchoring.splitLines] with
     * [String.lines] — all 32 vectors still passed.
     */
    @Test
    fun everyLineSplitCaseMatchesTheSpecificationTable() {
        val cases = ObjectMapper().readTree(contractFile("line_split_cases.json"))
        assertTrue("line_split_cases.json must be a non-empty array", cases.isArray && cases.size() > 0)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case["name"].asText()
            val expected = case["lines"].map { it.asText() }
            val actual = Anchoring.splitLines(case["text"].asText())
            if (actual != expected) failures += "$name: expected $expected, got $actual"
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size()} line-split cases diverged:\n" + failures.joinToString("\n"))
        }
        println("AnchoringTest: ${cases.size()} line-split cases reproduced exactly")
    }

    @Test
    fun theContractFileIsFound() {
        assertNotNull(vectorsFile())
        assertTrue("vectors.json must be readable", vectorsFile().canRead())
    }
}
