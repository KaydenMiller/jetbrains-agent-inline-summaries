package why.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import why.model.Resolution
import why.model.Resolved
import why.resolve.Anchoring
import why.store.TASKS_DIR_NAME
import why.store.WHY_DIR_NAME
import why.store.WhyModelService
import why.ui.WhyToolWindowPanel
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/**
 * W-14 — selecting a note reveals the region it claims, and nothing else does. W-15 — the
 * reveal is dismissed by the same act that made it and by nothing else, and the note's
 * gutter icon says which state it is in.
 *
 * ### What is driven
 *
 * The tool-window route end to end: a real `.why/` corpus on disk, W-10's panel, its
 * `navigate`, a real editor, and the highlighters [revealNoteRegion] left behind. The
 * gutter route's click action opens the popup, which is not testable headlessly (see
 * `GutterPainter.showNotePopup`), so the part of it that W-14 adds is asserted instead:
 * that the renderer W-7 attaches carries the *resolved* range the reveal is called with
 * ([testGutterMarkCarriesTheResolvedRangeItWouldReveal]), and that revealing that range
 * lights exactly the same lines.
 *
 * ### Which markup model
 *
 * [HighlightManager][com.intellij.codeInsight.highlighting.HighlightManager] adds to the
 * *editor's* markup model; W-7's gutter icons live in the *document's*. They are therefore
 * separate collections, and the assertions here keep them apart by name as well: [reveals]
 * excludes anything carrying a [WhyNoteGutterIconRenderer], and [gutterMarks] requires one.
 */
class WhyRevealTest : HeavyPlatformTestCase() {

    private lateinit var base: VirtualFile
    private lateinit var panel: WhyToolWindowPanel

    private val sourceName = "Player.cs"
    private val symbol = "Game.Player.Jump"

    /** The annotated region: lines 5..8 of an unpadded [source]. */
    private val region = listOf(
        "        void Jump()",
        "        {",
        "            velocity = 5;",
        "        }",
    )

    private fun source(padding: Int): String {
        val head = listOf("namespace Game", "{", "    public class Player", "    {")
        val pad = (1..padding).map { "        // padding $it" }
        return (head + pad + region + listOf("    }", "}")).joinToString("\n") + "\n"
    }

    /** Hash of the region as it sits in an unpadded file, lines 5 through 8. */
    private fun regionHash(): String = Anchoring.hashText(source(0), 5, 8)

    override fun setUp() {
        super.setUp()
        base = getOrCreateProjectBaseDir()
        // Inline reload and inline resolve-and-apply, the same seams and the same reasons as
        // WhyGutterTest and WhyToolWindowTest: the threading is not what this test is for.
        project.service<WhyModelService>().schedule = { _, action -> action() }
        project.service<WhyGutterService>().let { gutter ->
            gutter.pipeline = { _, compute, apply -> apply(compute()) }
            gutter.onEdt = { it() }
        }
        // W-7's listener, subscribed by hand exactly as WhyGutterTest does it, so the gutter
        // icons this test compares against are the real ones.
        project.messageBus.connect(testRootDisposable)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, WhyEditorGutterListener(project))
        panel = WhyToolWindowPanel(project)
        disposeOnTearDown(panel)
        panel.pipeline = { compute, apply -> apply(compute()) }
    }

    // ---- fixture ------------------------------------------------------------

    private val taskHeader =
        """{"kind":"task","id":"T-17","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"clamp the jump"}"""

    private fun writeTasks(vararg notes: String) {
        VfsTestUtil.createFile(
            base,
            "$WHY_DIR_NAME/$TASKS_DIR_NAME/T-17.jsonl",
            (listOf(taskHeader) + notes).joinToString("\n"),
        )
    }

    private fun noteJson(
        id: String,
        start: Int,
        end: Int,
        hash: String,
        symbolHint: String = symbol,
    ) = """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$sourceName","base":"a3f9c1d",""" +
        """"anchor":{"symbol":"$symbolHint","start":$start,"end":$end,"hash":"$hash"},""" +
        """"what":"Clamps the jump impulse.","why":"Playtesters reached the roof geometry.","flags":[]}"""

    private fun openSource(text: String): VirtualFile {
        val file = VfsTestUtil.createFile(base, sourceName, text)
        FileEditorManager.getInstance(project).openFile(file, false)
        return file
    }

    // ---- reading the markup -------------------------------------------------

    /** The editor `navigate` left selected. */
    private fun editor(): Editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) {
        "navigate() opened no editor"
    }

    /**
     * W-14's highlighters: the editor-level ones, minus anything wearing a gutter renderer,
     * so a reveal can never be confused with one of W-7's icons.
     */
    private fun reveals(editor: Editor): List<RangeHighlighter> =
        editor.markupModel.allHighlighters
            .filter { it.gutterIconRenderer !is WhyNoteGutterIconRenderer }
            .sortedBy { it.startOffset }

    /** W-7's highlighters, in the document markup model, identified by their renderer. */
    private fun gutterMarks(document: Document): List<RangeHighlighter> =
        DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .filter { it.gutterIconRenderer is WhyNoteGutterIconRenderer }

    /** 1-based inclusive line range a highlighter covers, to compare against the resolver's. */
    private fun lines(editor: Editor, highlighter: RangeHighlighter): IntRange =
        (editor.document.getLineNumber(highlighter.startOffset) + 1)..
            (editor.document.getLineNumber(highlighter.endOffset) + 1)

    /** The panel's row for one note id, from the tree it actually built. */
    private fun row(noteId: String): Resolved {
        val root = panel.treeModel.root as DefaultMutableTreeNode
        val rows = (0 until root.childCount)
            .map { root.getChildAt(it) as DefaultMutableTreeNode }
            .flatMap { group -> (0 until group.childCount).map { group.getChildAt(it) as DefaultMutableTreeNode } }
            .mapNotNull { it.userObject as? Resolved }
        return requireNotNull(rows.singleOrNull { it.note.id == noteId }) {
            "no single row for $noteId in ${rows.map { it.note.id }}"
        }
    }

    /**
     * The icon W-7's mark for [noteId] draws right now. Read through the renderer the gutter
     * itself holds, so this is the same call the paint pass makes.
     */
    private fun icon(noteId: String): Icon {
        val document = editor().document
        val renderers = gutterMarks(document).map { it.gutterIconRenderer as WhyNoteGutterIconRenderer }
        return requireNotNull(renderers.singleOrNull { it.note.id == noteId }) {
            "no single gutter mark for $noteId in ${renderers.map { it.note.id }}"
        }.getIcon()
    }

    // ---- 1 ------------------------------------------------------------------

    /**
     * Revealing the region that is already revealed dismisses it — the only deliberate
     * dismissal there is, and therefore the central assertion of this file.
     *
     * W-15: the earlier version also dismissed on the caret leaving the region, on any
     * keystroke and on any edit. All three fired while the reader was still reading, which
     * read as the reveal falling over rather than as a control, so all three are gone: the
     * caret listener is deleted and both hide flags are off
     * ([testAClickAwayFromTheRegionDoesNotDismissTheReveal] covers the caret; the two flags
     * have no headless act that reaches this editor, and are a sandbox check). Escape still
     * dismisses — the platform adds that flag itself and does not expose it.
     */
    fun testRevealingTheSameRegionAgainDismissesIt() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()
        val note = row("W-1111")

        panel.navigate(note)
        assertEquals("first selection reveals", 1, reveals(editor()).size)

        panel.navigate(note)
        assertEquals("same region again dismisses", emptyList<RangeHighlighter>(), reveals(editor()))

        panel.navigate(note)
        assertEquals("and reveals again", 1, reveals(editor()).size)
    }

    /**
     * The W-15 report, asserted in reverse: clicking into the code away from the region must
     * leave the reveal alone. This is the exact case the deleted caret listener dismissed on.
     */
    fun testAClickAwayFromTheRegionDoesNotDismissTheReveal() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()
        panel.navigate(row("W-1111"))
        assertEquals(1, reveals(editor()).size)

        // Line 12 (0-based 11) is outside 5..8 — where clicking elsewhere puts the caret.
        editor().caretModel.moveToLogicalPosition(LogicalPosition(11, 0))

        assertEquals("the caret leaving the region must not dismiss", 1, reveals(editor()).size)
        assertEquals(5..8, lines(editor(), reveals(editor()).single()))
        assertEquals("W-1111", revealedNoteId(project))
        assertSame("and the icon stays opaque", WhyIcons.NOTE, icon("W-1111"))
    }

    /** Selecting a note reveals exactly its resolved range, in the editor's own markup. */
    fun testSelectingANoteRevealsExactlyItsResolvedRange() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()

        val note = row("W-1111")
        assertEquals(Resolution.SOLID, note.state)
        panel.navigate(note)

        val reveal = reveals(editor()).single()
        assertEquals(5..8, lines(editor(), reveal))
        // The colour comes from the scheme, not from literal attributes, which is what makes
        // it correct in a light and a dark theme without this file knowing either.
        assertSame(REVEAL_ATTRIBUTES, reveal.textAttributesKey)
    }

    // ---- 2 ------------------------------------------------------------------

    /**
     * The note's stored range says 5..8; three padding lines put the code at 8..11. The
     * reveal must be at 8..11, which only happens if the resolver is in the path.
     */
    fun testMovedCodeRevealsTheNewRangeNotTheStoredOne() {
        openSource(source(padding = 3))
        writeTasks(noteJson("W-2222", start = 5, end = 8, hash = regionHash()))
        panel.refresh()

        val note = row("W-2222")
        assertEquals(Resolution.SOLID, note.state)
        assertEquals(8, note.start)
        assertTrue("the resolved line must differ from anchor.start", note.start != note.note.anchor.start)

        panel.navigate(note)

        val revealed = lines(editor(), reveals(editor()).single())
        assertEquals(8..11, revealed)
        assertFalse("the reveal must not start at anchor.start", revealed.first == note.note.anchor.start)
    }

    // ---- 3 ------------------------------------------------------------------

    /** An orphan has no range. The file opens, nothing is revealed, nothing throws. */
    fun testSelectingAnOrphanRevealsNothing() {
        openSource(source(padding = 0))
        // Hash matches nothing, stored range is past the end of the file, symbol absent from
        // the text: the resolver's step 5.
        writeTasks(
            noteJson("W-9999", start = 900, end = 901, hash = "000000", symbolHint = "Game.Player.Teleport"),
        )
        panel.refresh()

        val note = row("W-9999")
        assertEquals(Resolution.ORPHANED, note.state)
        assertNull(note.start)

        panel.navigate(note)

        assertEquals(emptyList<RangeHighlighter>(), reveals(editor()))
    }

    /** And an orphan selected *after* a real note does not leave that note's region lit. */
    fun testAnOrphanSelectedAfterANoteClearsThePreviousReveal() {
        openSource(source(padding = 0))
        writeTasks(
            noteJson("W-1111", start = 5, end = 8, hash = regionHash()),
            noteJson("W-9999", start = 900, end = 901, hash = "000000", symbolHint = "Game.Player.Teleport"),
        )
        panel.refresh()

        panel.navigate(row("W-1111"))
        assertEquals(1, reveals(editor()).size)

        panel.navigate(row("W-9999"))
        assertEquals(emptyList<RangeHighlighter>(), reveals(editor()))
    }

    // ---- 4 ------------------------------------------------------------------

    /**
     * Selecting B after A leaves only B's reveal. W-3333 is drifted, so it resolves at its
     * stored 2..3 — a different range from W-1111's 5..8, which is what makes the
     * replacement visible rather than merely counted.
     */
    fun testSelectingASecondNoteReplacesTheFirstReveal() {
        openSource(source(padding = 0))
        writeTasks(
            noteJson("W-1111", start = 5, end = 8, hash = regionHash()),
            noteJson("W-3333", start = 2, end = 3, hash = "000000"),
        )
        panel.refresh()

        panel.navigate(row("W-1111"))
        assertEquals(5..8, lines(editor(), reveals(editor()).single()))

        val drifted = row("W-3333")
        assertEquals(Resolution.DRIFTED, drifted.state)
        panel.navigate(drifted)

        val remaining = reveals(editor())
        assertEquals("the first reveal must not survive the second selection", 1, remaining.size)
        assertEquals(2..3, lines(editor(), remaining.single()))
    }

    // ---- 5 ------------------------------------------------------------------

    /** W-7's gutter icons are in a different markup model and a reveal does not touch them. */
    fun testGutterHighlightersAreUnaffectedByAReveal() {
        val file = openSource(source(padding = 0))
        writeTasks(
            noteJson("W-1111", start = 5, end = 8, hash = regionHash()),
            noteJson("W-3333", start = 2, end = 3, hash = "000000"),
        )
        panel.refresh()
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

        val before = gutterMarks(document)
        assertEquals("both notes should already carry a gutter icon", 2, before.size)

        panel.navigate(row("W-1111"))
        panel.navigate(row("W-3333"))

        val after = gutterMarks(document)
        assertEquals("a reveal must not add or remove gutter icons", before.size, after.size)
        assertEquals(
            before.map { it.startOffset to it.endOffset }.toSet(),
            after.map { it.startOffset to it.endOffset }.toSet(),
        )
        assertTrue("the gutter icons must still be valid", after.all { it.isValid })
    }

    // ---- the gutter route's input -------------------------------------------

    /**
     * The other call site. `getClickAction` hands [revealNoteRegion] the range the mark
     * carries, so what W-14 needs from W-7 is that the range is the resolved one; the popup
     * the same action opens is untestable headlessly. Revealing that range then lights the
     * same lines the tool-window route does.
     */
    fun testGutterMarkCarriesTheResolvedRangeItWouldReveal() {
        val file = openSource(source(padding = 3))
        writeTasks(noteJson("W-2222", start = 5, end = 8, hash = regionHash()))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

        val renderer = gutterMarks(document).single().gutterIconRenderer as WhyNoteGutterIconRenderer
        assertEquals("W-2222", renderer.note.id)
        assertEquals("the mark must carry the resolved range", 8, renderer.startLine)
        assertEquals(11, renderer.endLine)
        assertTrue(renderer.startLine != renderer.note.anchor.start)

        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
        revealNoteRegion(project, editor, renderer.note.id, renderer.startLine, renderer.endLine)

        assertEquals(8..11, lines(editor, reveals(editor).single()))
        assertSame("the mark that was clicked is the opaque one", WhyIcons.NOTE, renderer.getIcon())
    }

    // ---- W-15: the icon states whether its region is revealed ---------------

    /**
     * Nothing revealed, so the mark is dimmed. The resting state of every icon in the file,
     * and the state the reader compares the opaque one against.
     */
    fun testAnUnrevealedNotesIconIsDimmed() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()

        assertNull("nothing is revealed yet", revealedNoteId(project))
        assertSame(WhyIcons.NOTE_DIMMED, icon("W-1111"))
    }

    /** Revealing the region takes that note's icon to full opacity. */
    fun testRevealingANoteMakesItsIconOpaque() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()

        panel.navigate(row("W-1111"))

        assertEquals(1, reveals(editor()).size)
        assertEquals("W-1111", revealedNoteId(project))
        assertSame(WhyIcons.NOTE, icon("W-1111"))
    }

    /** And dismissing it — the same region again — puts the icon back to dimmed. */
    fun testDismissingTheRevealReturnsTheIconToDimmed() {
        openSource(source(padding = 0))
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        panel.refresh()
        val note = row("W-1111")

        panel.navigate(note)
        assertSame(WhyIcons.NOTE, icon("W-1111"))

        panel.navigate(note)

        assertEquals(emptyList<RangeHighlighter>(), reveals(editor()))
        assertNull(revealedNoteId(project))
        assertSame(WhyIcons.NOTE_DIMMED, icon("W-1111"))
    }

    /**
     * One reveal exists at a time, so one icon is opaque at a time: revealing B dims A. The
     * two notes resolve to different ranges (W-3333 is drifted at its stored 2..3), so this
     * is the same act `testSelectingASecondNoteReplacesTheFirstReveal` covers for the wash,
     * asserted on the icons instead.
     */
    fun testRevealingASecondNoteDimsTheFirstsIcon() {
        openSource(source(padding = 0))
        writeTasks(
            noteJson("W-1111", start = 5, end = 8, hash = regionHash()),
            noteJson("W-3333", start = 2, end = 3, hash = "000000"),
        )
        panel.refresh()

        panel.navigate(row("W-1111"))
        assertSame(WhyIcons.NOTE, icon("W-1111"))
        assertSame(WhyIcons.NOTE_DIMMED, icon("W-3333"))

        panel.navigate(row("W-3333"))

        assertSame("A must go back to dimmed", WhyIcons.NOTE_DIMMED, icon("W-1111"))
        assertSame("and B becomes the opaque one", WhyIcons.NOTE, icon("W-3333"))
        assertEquals("W-3333", revealedNoteId(project))
        assertEquals("exactly one reveal", 1, reveals(editor()).size)
    }

    /**
     * An orphan reveals nothing and lights nothing. It has no gutter mark at all — section
     * 6.2 renders no icon for an orphan — so the assertion is that selecting one leaves the
     * only mark in the file dimmed and the project with no reveal.
     */
    fun testAnOrphanRevealsNothingAndLightsNoIcon() {
        openSource(source(padding = 0))
        writeTasks(
            noteJson("W-1111", start = 5, end = 8, hash = regionHash()),
            noteJson("W-9999", start = 900, end = 901, hash = "000000", symbolHint = "Game.Player.Teleport"),
        )
        panel.refresh()
        assertEquals(Resolution.ORPHANED, row("W-9999").state)

        panel.navigate(row("W-9999"))

        assertEquals(emptyList<RangeHighlighter>(), reveals(editor()))
        assertNull(revealedNoteId(project))
        assertEquals(
            "an orphan must have no gutter mark to light",
            listOf("W-1111"),
            gutterMarks(editor().document).map { (it.gutterIconRenderer as WhyNoteGutterIconRenderer).note.id },
        )
        assertSame(WhyIcons.NOTE_DIMMED, icon("W-1111"))
    }
}
