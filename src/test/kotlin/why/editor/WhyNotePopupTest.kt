package why.editor

import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import why.model.Anchor
import why.model.Note
import why.model.Resolution
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.ScrollPaneConstants

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

    // ---- W-17: the size limits. --------------------------------------------------------
    //
    // Two halves, tested apart. The cap arithmetic is a pure function of
    // (content, absolute cap, screen rectangle), so it is asserted against synthetic
    // screens and needs no display. The measure-then-clamp of the real component needs
    // font metrics, which headless AWT does supply.

    /** A screen big enough that the readability cap is the one that binds. */
    private val bigScreen = Rectangle(0, 0, 3840, 2160)

    private fun caps(screen: Rectangle) = WhyPopupSize.popupMaxSize(WhyPopupSize.absoluteMax(), screen)

    @Test
    fun onARoomyScreenTheReadabilityCapBinds() {
        val absolute = Dimension(560, 360)
        assertEquals(absolute, WhyPopupSize.popupMaxSize(absolute, bigScreen))
    }

    /** The safety net: half of an 800-pixel-wide screen is 400, which is under the 560 cap. */
    @Test
    fun onACrampedScreenTheScreenFractionBinds() {
        val size = WhyPopupSize.popupMaxSize(Dimension(560, 360), Rectangle(0, 0, 800, 600))
        assertEquals(400, size.width)
        assertEquals(300, size.height)
    }

    @Test
    fun theClampNeverExceedsEitherCap() {
        val absolute = Dimension(560, 360)
        listOf(Rectangle(0, 0, 800, 600), bigScreen, Rectangle(0, 0, 400, 200)).forEach { screen ->
            val cap = WhyPopupSize.popupMaxSize(absolute, screen)
            val clamped = WhyPopupSize.clampedPopupSize(Dimension(9999, 9999), absolute, screen)
            assertTrue("$clamped exceeds absolute cap $absolute", clamped.width <= absolute.width)
            assertTrue("$clamped exceeds absolute cap $absolute", clamped.height <= absolute.height)
            assertEquals("on $screen the smaller of the two caps should win", cap, clamped)
        }
    }

    /** The clamp is a ceiling, not a floor: a two-line note must not open as a wide box. */
    @Test
    fun aShortNoteIsNarrowerThanTheMaximumWidth() {
        val short = note(what = "Clamps the impulse.", why = "Testers reached the roof.", flags = emptyList())
        val size = notePopupComponent(short, Resolution.SOLID, bigScreen).preferredSize
        val cap = caps(bigScreen)

        assertTrue("width $size should be under the cap $cap", size.width < cap.width)
        assertTrue("height $size should be under the cap $cap", size.height < cap.height)
    }

    /**
     * The reported bug: one long sentence with no line breaks used to render as one line as
     * wide as the editor. Wrapped, it stops at the cap and gets taller instead.
     */
    @Test
    fun aLongSingleLineWhyWrapsAtTheMaximumWidth() {
        val long = "The jump ceiling has to be clamped here rather than in the input layer " +
            "because the input layer runs before the ground check and therefore cannot know " +
            "whether the impulse is a jump or a step off a ledge, which is the distinction " +
            "the playtest reports actually turned on."
        assertTrue("the fixture must be one unbroken line", !long.contains("\n") && long.length > 250)

        val component = notePopupComponent(note(why = long), Resolution.SOLID, bigScreen)
        val size = component.preferredSize
        val cap = caps(bigScreen)
        val oneLine = notePopupComponent(
            note(what = "x", why = "y", flags = emptyList()), Resolution.SOLID, bigScreen,
        ).preferredSize

        assertEquals("should stop at the width cap", cap.width, size.width)
        assertTrue("should have wrapped to more lines than a short note: $size vs $oneLine",
            size.height > oneLine.height)
    }

    @Test
    fun aVeryLongNoteStopsAtTheMaximumHeightAndScrolls() {
        val component = notePopupComponent(hugeNote(), Resolution.DRIFTED, bigScreen)
        val cap = caps(bigScreen)

        assertEquals("should stop at the height cap", cap.height, component.preferredSize.height)
        assertEquals(
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            component.verticalScrollBarPolicy,
        )
        // Wrapped text has nothing to scroll sideways; a horizontal bar would mean the wrap
        // failed, so the policy denies it outright.
        assertEquals(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            component.horizontalScrollBarPolicy,
        )
        assertTrue(
            "content should overflow the viewport, or there is nothing to scroll",
            component.viewport.view.preferredSize.height > component.preferredSize.height,
        )
    }

    /** The height cap must not engage on an ordinary note. */
    @Test
    fun aShortNoteNeedsNoVerticalScrollBar() {
        val component = notePopupComponent(note(), Resolution.SOLID, bigScreen)
        assertTrue(
            "content ${component.viewport.view.preferredSize} fits ${component.preferredSize}",
            component.viewport.view.preferredSize.height <= component.preferredSize.height,
        )
    }

    /**
     * The hover routes cannot be wrapped in a scroll pane, so their width limit is markup.
     * Asserted on the string because that is all this side has.
     */
    @Test
    fun theTooltipVariantCarriesAWidthAndTheClickPopupDoesNot() {
        val wrapped = notePopupHtml(note(), Resolution.SOLID, JBUI.scale(WhyPopupSize.POPUP_MAX_WIDTH))
        assertTrue(wrapped, wrapped.contains("width: ${JBUI.scale(WhyPopupSize.POPUP_MAX_WIDTH)}px"))
        assertFalse(notePopupHtml(note(), Resolution.SOLID).contains("width"))
    }

    private fun hugeNote() = note(
        what = (1..12).joinToString(" ") { "Sentence $it describing what the code does." },
        why = (1..40).joinToString(" ") { "Reason $it that the code was written this way." },
        flags = (1..8).map { "flag-number-$it" },
    )
}
