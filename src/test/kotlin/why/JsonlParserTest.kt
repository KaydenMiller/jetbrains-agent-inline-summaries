package why

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import why.model.parseTaskFile

/**
 * Plain JUnit 4 on purpose: [parseTaskFile] takes a String and touches no platform
 * service, so these run without an IDE fixture.
 *
 * [HEADER], [NOTE_1] and [NOTE_2] are the requirements document §5.2 example
 * verbatim — checked character for character against the document, which lives
 * outside this repository and so is not asserted against at test time.
 */
class JsonlParserTest {

    // --- §5.2, verbatim -----------------------------------------------------

    @Test
    fun `parses the section 5_2 example`() {
        val parsed = parseTaskFile("T-91", EXAMPLE)

        assertEquals(emptyList<String>(), parsed.warnings)

        assertEquals("T-91", parsed.task.id)
        assertEquals("2026-08-20T14:01:55Z", parsed.task.ts)
        assertEquals("a3f9c1d", parsed.task.base)
        assertEquals("Jumps feel dropped when landing", parsed.task.prompt)

        assertEquals(2, parsed.notes.size)

        val first = parsed.notes[0]
        assertEquals("W-4KQ2", first.id)
        assertEquals("T-91", first.taskId)
        assertEquals("2026-08-20T14:02:11Z", first.ts)
        assertEquals("Assets/Scripts/PlayerController.cs", first.file)
        assertEquals("a3f9c1d", first.base)
        assertEquals("PlayerController.HandleJump", first.anchor.symbol)
        assertEquals(142, first.anchor.start)
        assertEquals(168, first.anchor.end)
        assertEquals("3f21ab", first.anchor.hash)
        assertEquals(
            "Buffers jump input for 120ms before ground contact, consumes it on landing.",
            first.what,
        )
        assertEquals(
            "Input was polled once per FixedUpdate and discarded if not grounded, " +
                "so presses during landing frames vanished.",
            first.why,
        )
        assertEquals(listOf("changes-feel", "tunable:JumpBufferMs"), first.flags)

        val second = parsed.notes[1]
        assertEquals("W-4KQ3", second.id)
        assertEquals("T-91", second.taskId)
        assertEquals("2026-08-20T14:02:40Z", second.ts)
        assertEquals("Assets/Scripts/PlayerController.cs", second.file)
        assertEquals("a3f9c1d", second.base)
        assertEquals("PlayerController.JumpBufferMs", second.anchor.symbol)
        assertEquals(31, second.anchor.start)
        assertEquals(31, second.anchor.end)
        assertEquals("7e10bb", second.anchor.hash)
        assertEquals("New serialized field, default 120.", second.what)
        assertEquals("Tunable without a rebuild. 120 is a guess, not measured.", second.why)
        assertEquals(listOf("needs-review"), second.flags)
    }

    // --- §5.2, partially written file ---------------------------------------

    @Test
    fun `final line cut mid-token still yields the earlier records`() {
        val truncated = NOTE_2.take(45)
        assertTrue("fixture must really be mid-token", !truncated.endsWith("}"))

        val parsed = parseTaskFile("T-91", "$HEADER\n$NOTE_1\n$truncated")

        assertEquals("a3f9c1d", parsed.task.base)
        assertEquals(listOf("W-4KQ2"), parsed.notes.map { it.id })
        // An in-flight append is the normal case while an agent works, not a fault.
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    // --- R5.4.2 -------------------------------------------------------------

    @Test
    fun `garbage line is skipped and warned by line number`() {
        val text = "$HEADER\n$NOTE_1\nthis is not json {{{\n$NOTE_2\n"

        val parsed = parseTaskFile("T-91", text)

        assertEquals(listOf("W-4KQ2", "W-4KQ3"), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("line 3"))
    }

    // --- R5.4.1 -------------------------------------------------------------

    @Test
    fun `unknown fields are ignored on both record kinds`() {
        val header =
            """{"kind":"task","id":"T-91","ts":"T","base":"b","prompt":"p","future":{"a":[1,2]}}"""
        val note =
            """{"kind":"note","id":"W-AAAA","ts":"T","file":"F.cs","base":"b",""" +
                """"anchor":{"start":1,"end":2,"hash":"aaaaaa","confidence":0.9},""" +
                """"what":"w","why":"y","severity":"high"}"""

        val parsed = parseTaskFile("T-91", "$header\n$note\n")

        assertEquals(emptyList<String>(), parsed.warnings)
        assertEquals("p", parsed.task.prompt)
        assertEquals(listOf("W-AAAA"), parsed.notes.map { it.id })
    }

    // --- R5.4.3 -------------------------------------------------------------

    @Test
    fun `missing header record is tolerated and the task id comes from the filename`() {
        val parsed = parseTaskFile("T-91", "$NOTE_1\n$NOTE_2\n")

        assertEquals(emptyList<String>(), parsed.warnings)
        assertEquals("T-91", parsed.task.id)
        assertNull(parsed.task.ts)
        assertNull(parsed.task.base)
        assertNull(parsed.task.prompt)
        assertEquals(listOf("W-4KQ2", "W-4KQ3"), parsed.notes.map { it.id })
        assertTrue(parsed.notes.all { it.taskId == "T-91" })
    }

    // --- required fields ----------------------------------------------------

    @Test
    fun `note missing a required top-level field is skipped with a warning`() {
        val parsed = parseTaskFile("T-91", "$MINIMAL_NOTE\n$NOTE_MISSING_WHY\n$NOTE_1\n")

        assertEquals(listOf("W-AAAA", "W-4KQ2"), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("line 2"))
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("why"))
    }

    @Test
    fun `note missing anchor hash is skipped with a warning`() {
        val parsed = parseTaskFile("T-91", "$MINIMAL_NOTE\n$NOTE_MISSING_HASH\n$NOTE_1\n")

        assertEquals(listOf("W-AAAA", "W-4KQ2"), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("line 2"))
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("anchor.hash"))
    }

    @Test
    fun `note missing the whole anchor object is skipped with a warning`() {
        val note =
            """{"kind":"note","id":"W-DDDD","ts":"T","file":"F.cs","base":"b","what":"w","why":"y"}"""

        val parsed = parseTaskFile("T-91", "$note\n$MINIMAL_NOTE\n")

        assertEquals(listOf("W-AAAA"), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("anchor"))
    }

    // --- degenerate files ---------------------------------------------------

    @Test
    fun `empty file yields a synthetic task and nothing else`() {
        val parsed = parseTaskFile("T-07", "")

        assertEquals("T-07", parsed.task.id)
        assertNull(parsed.task.ts)
        assertEquals(emptyList<String>(), parsed.notes.map { it.id })
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `header only file yields a task and no notes`() {
        val parsed = parseTaskFile("T-91", "$HEADER\n")

        assertEquals("Jumps feel dropped when landing", parsed.task.prompt)
        assertEquals(emptyList<String>(), parsed.notes.map { it.id })
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `blank and whitespace-only lines are skipped silently`() {
        val text = "\n$HEADER\n\n   \n\t\n$NOTE_1\n\n$NOTE_2\n\n\n"

        val parsed = parseTaskFile("T-91", text)

        assertEquals(emptyList<String>(), parsed.warnings)
        assertEquals("a3f9c1d", parsed.task.base)
        assertEquals(listOf("W-4KQ2", "W-4KQ3"), parsed.notes.map { it.id })
    }

    // --- forward compatibility ----------------------------------------------

    @Test
    fun `unrecognised kind is skipped with a warning`() {
        val future = """{"kind":"decision","id":"D-1","rationale":"…"}"""

        val parsed = parseTaskFile("T-91", "$HEADER\n$future\n$NOTE_1\n")

        assertEquals(listOf("W-4KQ2"), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("line 2"))
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("decision"))
    }

    @Test
    fun `record with no kind at all is skipped with a warning`() {
        val parsed = parseTaskFile("T-91", """{"id":"W-EEEE"}""" + "\n")

        assertEquals(emptyList<String>(), parsed.notes.map { it.id })
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0], parsed.warnings[0].contains("line 1"))
    }

    // --- flags --------------------------------------------------------------

    @Test
    fun `absent flags is an empty list and present flags keep their order`() {
        val ordered =
            """{"kind":"note","id":"W-FFFF","ts":"T","file":"F.cs","base":"b",""" +
                """"anchor":{"start":1,"end":2,"hash":"aaaaaa"},"what":"w","why":"y",""" +
                """"flags":["zeta","needs-review","alpha","tunable:Speed"]}"""

        val parsed = parseTaskFile("T-91", "$MINIMAL_NOTE\n$ordered\n")

        assertEquals(emptyList<String>(), parsed.warnings)
        assertEquals(emptyList<String>(), parsed.notes[0].flags)
        assertEquals(
            listOf("zeta", "needs-review", "alpha", "tunable:Speed"),
            parsed.notes[1].flags,
        )
    }

    @Test
    fun `optional anchor symbol may be absent`() {
        val parsed = parseTaskFile("T-91", "$MINIMAL_NOTE\n")

        assertNull(parsed.notes[0].anchor.symbol)
    }

    companion object {
        // The requirements document §5.2 example, character for character.
        const val HEADER =
            """{"kind":"task","id":"T-91","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"Jumps feel dropped when landing"}"""
        const val NOTE_1 =
            """{"kind":"note","id":"W-4KQ2","ts":"2026-08-20T14:02:11Z","file":"Assets/Scripts/PlayerController.cs","base":"a3f9c1d","anchor":{"symbol":"PlayerController.HandleJump","start":142,"end":168,"hash":"3f21ab"},"what":"Buffers jump input for 120ms before ground contact, consumes it on landing.","why":"Input was polled once per FixedUpdate and discarded if not grounded, so presses during landing frames vanished.","flags":["changes-feel","tunable:JumpBufferMs"]}"""
        const val NOTE_2 =
            """{"kind":"note","id":"W-4KQ3","ts":"2026-08-20T14:02:40Z","file":"Assets/Scripts/PlayerController.cs","base":"a3f9c1d","anchor":{"symbol":"PlayerController.JumpBufferMs","start":31,"end":31,"hash":"7e10bb"},"what":"New serialized field, default 120.","why":"Tunable without a rebuild. 120 is a guess, not measured.","flags":["needs-review"]}"""

        val EXAMPLE = "$HEADER\n$NOTE_1\n$NOTE_2\n"

        // Synthetic minima, one required field removed per variant.
        const val MINIMAL_NOTE =
            """{"kind":"note","id":"W-AAAA","ts":"T","file":"F.cs","base":"b","anchor":{"start":1,"end":2,"hash":"aaaaaa"},"what":"w","why":"y"}"""
        const val NOTE_MISSING_WHY =
            """{"kind":"note","id":"W-BBBB","ts":"T","file":"F.cs","base":"b","anchor":{"start":1,"end":2,"hash":"aaaaaa"},"what":"w"}"""
        const val NOTE_MISSING_HASH =
            """{"kind":"note","id":"W-CCCC","ts":"T","file":"F.cs","base":"b","anchor":{"start":1,"end":2},"what":"w","why":"y"}"""
    }
}
