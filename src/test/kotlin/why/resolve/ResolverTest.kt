package why.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import why.model.Anchor
import why.model.Note
import why.model.Resolution

/**
 * The five-step resolution order from `PLAN.md` section 4. Plain JUnit 4: the resolver
 * takes a `String`, so no platform fixture, no project, no virtual file.
 *
 * Every fixture is C#-shaped only because the product's first target is Rider. Nothing in
 * the resolver parses it; the same tests would pass with the braces removed.
 */
class ResolverTest {

    // 1 using System;
    // 2
    // 3 public class PlayerController
    // 4 {
    // 5     public void HandleJump(float dt)
    // 6     {
    // 7         if (grounded)
    // 8             velocity.y = jumpSpeed;
    // 9     }
    // 10 }
    private val original = doc(
        "using System;",
        "",
        "public class PlayerController",
        "{",
        "\tpublic void HandleJump(float dt)",
        "\t{",
        "\t\tif (grounded)",
        "\t\t\tvelocity.y = jumpSpeed;",
        "\t}",
        "}",
    )

    /** The method, lines 5 to 9 of [original]. */
    private val methodNote = noteOver(original, 5, 9, "PlayerController.HandleJump")

    @Test
    fun unchangedFileResolvesSolidAtTheOriginalRange() {
        val resolved = Resolver.resolve(methodNote, original)
        assertEquals(Resolution.SOLID, resolved.state)
        assertEquals(5, resolved.start)
        assertEquals(9, resolved.end)
        assertEquals(methodNote.id, resolved.note.id)
    }

    @Test
    fun reindentingTabsToFourSpacesResolvesSolid() {
        val reindented = original.replace("\t", "    ")
        assertTrue("fixture must actually change", reindented != original)
        val resolved = Resolver.resolve(methodNote, reindented)
        assertEquals(Resolution.SOLID, resolved.state)
        assertEquals(5, resolved.start)
        assertEquals(9, resolved.end)
    }

    @Test
    fun blankLineInsertedInsideTheRangeResolvesSolid() {
        // Pushes the method's closing brace out of the stored window, so this resolves on
        // the K+1 window rather than on step 1.
        val edited = docOf(linesOf(original).toMutableList().also { it.add(7, "") })
        val resolved = Resolver.resolve(methodNote, edited)
        assertEquals(Resolution.SOLID, resolved.state)
        assertEquals(5, resolved.start)
        assertEquals(10, resolved.end)
    }

    @Test
    fun regionMovedFortyLinesDownResolvesSolidReanchored() {
        val moved = docOf(filler(40) + linesOf(original))
        val resolved = Resolver.resolve(methodNote, moved)
        assertEquals(Resolution.SOLID, resolved.state)
        assertEquals(45, resolved.start) // 5 + 40
        assertEquals(49, resolved.end) // 9 + 40
    }

    @Test
    fun regionMovedAndSymbolRenamedResolvesSolidViaTheSweep() {
        // The note is anchored to the body only, so renaming the method does not change the
        // hashed content -- it only destroys the step-2 search hint.
        val bodyNote = noteOver(original, 7, 8, "PlayerController.HandleJump")
        val movedAndRenamed = docOf(filler(40) + linesOf(original)).replace("HandleJump", "PerformLeap")
        assertTrue("search hint must be gone", !movedAndRenamed.contains("HandleJump"))

        val resolved = Resolver.resolve(bodyNote, movedAndRenamed)
        assertEquals(Resolution.SOLID, resolved.state)
        assertEquals(47, resolved.start) // 7 + 40
        assertEquals(48, resolved.end) // 8 + 40
    }

    @Test
    fun identifierRenamedInsideTheRegionResolvesDrifted() {
        val edited = original.replace("jumpSpeed", "leapSpeed")
        val resolved = Resolver.resolve(methodNote, edited)
        assertEquals(Resolution.DRIFTED, resolved.state)
        assertEquals(5, resolved.start)
        assertEquals(9, resolved.end)
    }

    @Test
    fun regionDeletedEntirelyResolvesOrphanedWithNoRange() {
        val lines = linesOf(original).toMutableList()
        lines.subList(4, 9).clear() // drop lines 5 to 9, the whole method
        val gutted = docOf(lines)
        assertTrue("method must be gone", !gutted.contains("HandleJump"))

        val resolved = Resolver.resolve(methodNote, gutted)
        assertEquals(Resolution.ORPHANED, resolved.state)
        assertNull(resolved.start)
        assertNull(resolved.end)
        // R6.2.2: the note itself is retained, never dropped.
        assertEquals(methodNote, resolved.note)
    }

    @Test
    fun rangeBeyondEndOfFileResolvesOrphanedWithoutThrowing() {
        // Out of range and nothing to search for: step 5.
        for (symbol in listOf(null, "Ghost.NotInThisFile")) {
            val note = noteOver(original, 5, 9, symbol)
                .let { it.copy(anchor = it.anchor.copy(start = 500, end = 520)) }
            val resolved = Resolver.resolve(note, original)
            assertEquals("symbol=$symbol", Resolution.ORPHANED, resolved.state)
            assertNull(resolved.start)
            assertNull(resolved.end)
        }
    }

    @Test
    fun rangeBeyondEndOfFileButSymbolStillPresentDriftsAtTheSymbol() {
        // Step 4 as written in PLAN.md section 4: "symbol found ... but no hash match" is
        // DRIFTED, so an out-of-range anchor whose symbol survives is reported at the symbol
        // rather than orphaned. Recorded here because it is the one case where the two halves
        // of the step-4 condition disagree.
        val note = methodNote.copy(anchor = methodNote.anchor.copy(start = 500, end = 520))
        val resolved = Resolver.resolve(note, original)
        assertEquals(Resolution.DRIFTED, resolved.state)
        assertEquals(5, resolved.start)
    }

    @Test
    fun malformedStoredRangeResolvesWithoutThrowing() {
        // start < 1 and start > end are hash errors (HASHING.md section 5); the resolver
        // must not propagate them out of a note that was written badly.
        for (anchor in listOf(
            methodNote.anchor.copy(start = 0, end = 4),
            methodNote.anchor.copy(start = 9, end = 5),
        )) {
            val resolved = Resolver.resolve(methodNote.copy(anchor = anchor), original)
            assertTrue(resolved.state in Resolution.entries)
        }
    }

    @Test
    fun nullSymbolStillResolvesViaStepsOneAndThree() {
        val note = noteOver(original, 5, 9, null)

        val unchanged = Resolver.resolve(note, original)
        assertEquals(Resolution.SOLID, unchanged.state)
        assertEquals(5, unchanged.start)
        assertEquals(9, unchanged.end)

        val moved = Resolver.resolve(note, docOf(filler(40) + linesOf(original)))
        assertEquals(Resolution.SOLID, moved.state)
        assertEquals(45, moved.start)
        assertEquals(49, moved.end)
    }

    @Test
    fun noteWhoseContentNormalisesToEmptyNeverResolvesSolid() {
        // HASHING.md section 13 rule 2. The writer is required to refuse such a note; if one
        // exists anyway, the sweep must not re-anchor it onto the first blank line it meets.
        val text = doc(
            "using System;",
            "",
            "   \t ",
            "",
            "public class Meter",
            "{",
            "}",
        )
        val blankNote = noteOver(text, 2, 4, null)
        assertEquals("fixture must be the empty-content case", Anchoring.EMPTY_HASH, blankNote.anchor.hash)

        val resolved = Resolver.resolve(blankNote, text)
        assertTrue("must not be SOLID on a blank window, was ${resolved.state}", resolved.state != Resolution.SOLID)
        assertEquals(Resolution.DRIFTED, resolved.state)

        // Same note against a document whose blank lines moved: still never SOLID.
        val movedBlanks = docOf(filler(40) + linesOf(text))
        assertTrue(Resolver.resolve(blankNote, movedBlanks).state != Resolution.SOLID)
    }

    @Test
    fun symbolFoundButContentChangedResolvesDriftedNotOrphaned() {
        // Whole method rewritten in place, method name kept: step 2 finds the name, no window
        // matches, so step 4 reports where to look.
        val rewritten = docOf(
            linesOf(original).toMutableList().also {
                it[6] = "\t\tif (canJump)"
                it[7] = "\t\t\tvelocity = Vector3.up * leapSpeed;"
            },
        )
        val resolved = Resolver.resolve(methodNote, rewritten)
        assertEquals(Resolution.DRIFTED, resolved.state)
        assertEquals(5, resolved.start)
        assertEquals(9, resolved.end)
    }

    private fun doc(vararg lines: String) = docOf(lines.toList())

    /**
     * HASHING.md section 13 rule 3. A lone `}` normalises to `}`, which is non-empty, so
     * rule 2 does not catch it — but every `}` in the file hashes identically, so a match
     * says nothing about location. Without the rule the sweep reports SOLID at the *first*
     * brace it reaches, which is a confidently wrong answer; with it the note is not SOLID.
     *
     * The writer refuses to create such a note, so this covers a corpus written before the
     * rule existed, or one hand-edited.
     */
    @Test
    fun `a note anchored to a lone brace never resolves solid`() {
        val text = docOf(
            listOf(
                "class A",
                "{",
                "    void One()",
                "    {",
                "        x = 1;",
                "    }",
                "",
                "    void Two()",
                "    {",
                "        y = 2;",
                "    }",
                "}",
            ),
        )
        // Line 11 is the closing brace of Two(); line 6 closes One() and hashes identically.
        assertEquals(
            "fixture must really collide for this test to mean anything",
            Anchoring.hashText(text, 6, 6),
            Anchoring.hashText(text, 11, 11),
        )

        val resolved = Resolver.resolve(noteOver(text, 11, 11, "A.Two"), text)

        assertNotEquals(
            "a lone-brace anchor must not report SOLID, at line 6 or anywhere else",
            Resolution.SOLID,
            resolved.state,
        )
    }

    private fun docOf(lines: List<String>) = lines.joinToString("\n") + "\n"

    private fun linesOf(text: String) = Anchoring.splitLines(text)

    private fun filler(count: Int) = (1..count).map { "// filler $it" }

    /** A note anchored to `[start, end]` of [text], with the hash that region has today. */
    private fun noteOver(text: String, start: Int, end: Int, symbol: String?) = Note(
        id = "W-0a1b",
        taskId = "T-0001",
        ts = "2026-08-20T12:00:00Z",
        file = "Assets/PlayerController.cs",
        base = "abc1234",
        anchor = Anchor(symbol, start, end, Anchoring.hashText(text, start, end)),
        what = "Jump handling",
        why = "Fixed-step physics needs the impulse applied before the integration step.",
        flags = emptyList(),
    )
}
