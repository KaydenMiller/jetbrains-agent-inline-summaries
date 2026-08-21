package why.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import why.model.Anchor
import why.model.Note
import why.model.Resolution

/**
 * R7.2 — the popup content. Plain JUnit 4: [notePopupHtml] is a pure function of a
 * [Note], which is exactly why the popup is asserted here rather than screenshotted.
 */
class WhyNotePopupTest {

    private fun note(
        what: String = "Clamps the jump impulse to the configured ceiling.",
        why: String = "Playtesters reached the roof geometry and fell out of the level.",
        flags: List<String> = listOf("needs-review", "tunable:JumpCeiling"),
    ) = Note(
        id = "W-4KQ2",
        taskId = "T-17",
        ts = "2026-08-20T14:02:11Z",
        file = "Assets/Player.cs",
        base = "a3f9c1d",
        anchor = Anchor("Game.Player.Jump", 5, 8, "3f21ab"),
        what = what,
        why = why,
        flags = flags,
    )

    @Test
    fun popupCarriesWhatWhyEveryFlagAndTheNoteId() {
        val note = note()
        val html = notePopupHtml(note, Resolution.SOLID)

        assertTrue("what missing: $html", html.contains(note.what))
        assertTrue("why missing: $html", html.contains(note.why))
        assertTrue("id missing: $html", html.contains("W-4KQ2"))
        // Every flag, not "2 flags" and not just the first one.
        note.flags.forEach { assertTrue("flag $it missing: $html", html.contains(it)) }
        // The task id ties the icon to the tool window's grouping (R7.4).
        assertTrue("task id missing: $html", html.contains("T-17"))
    }

    @Test
    fun aNoteWithNoFlagsProducesNoFlagsLine() {
        val html = notePopupHtml(note(flags = emptyList()), Resolution.SOLID)
        assertFalse("empty flag list should not print a label: $html", html.contains("flags:"))
        assertTrue(html.contains("W-4KQ2"))
    }

    /**
     * R6.2.1 — drift is informational. The drifted popup differs from the solid one by a
     * statement of fact, and it does not use the vocabulary of a warning or a prompt.
     */
    @Test
    fun theDriftedLineStatesTheFactAndAsksForNothing() {
        val drifted = notePopupHtml(note(), Resolution.DRIFTED)
        val solid = notePopupHtml(note(), Resolution.SOLID)

        assertTrue(drifted != solid)
        assertTrue(drifted.contains("changed since this note was written"))
        listOf("warning", "Warning", "outdated", "reconcile", "update the note", "invalid")
            .forEach { assertFalse("drift popup should not say '$it': $drifted", drifted.contains(it)) }
    }

    /** Note text is author-supplied and lands in an HTML pane; it must not be markup. */
    @Test
    fun noteTextIsEscapedAndLineBreaksSurvive() {
        val html = notePopupHtml(note(what = "guards <T> & null", why = "first\nsecond"), Resolution.SOLID)

        assertTrue(html.contains("guards &lt;T&gt; &amp; null"))
        assertFalse(html.contains("<T>"))
        assertTrue(html.contains("first<br/>second"))
    }
}
