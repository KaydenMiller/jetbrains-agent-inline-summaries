package why.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.VfsTestUtil
import why.model.Anchor
import why.model.Note
import why.model.Resolution
import why.resolve.Anchoring
import why.store.TASKS_DIR_NAME
import why.store.WHY_DIR_NAME
import why.store.WhyModelService
import java.awt.datatransfer.DataFlavor

/**
 * W-8 / R7.3 — the note id reaches the clipboard from both entry points.
 *
 * [HeavyPlatformTestCase] for the same reason as `WhyGutterTest`: the model reads real
 * files with `java.nio.file`, and the caret entry point needs the highlighters W-7 attaches
 * to a real document markup model, which is what supplies the *resolved* ranges.
 *
 * The clipboard is read back through [CopyPasteManager], which is also what the action
 * writes through, so the assertion is on the same surface a paste would read.
 *
 * ### What this cannot cover
 *
 * The `<keyboard-shortcut>` element. Every test here invokes the action object directly,
 * which is everything downstream of the keystroke; whether `control alt shift O` is what
 * the keymap dispatches is one line of XML and only observable in a running IDE.
 */
class WhyCopyReferenceTest : HeavyPlatformTestCase() {

    private lateinit var base: VirtualFile

    private val sourceName = "Player.cs"
    private val symbol = "Game.Player.Jump"
    private val sentinel = "clipboard-sentinel"
    private val taskHeader =
        """{"kind":"task","id":"T-17","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"clamp the jump"}"""

    private val region = listOf(
        "        void Jump()",
        "        {",
        "            velocity = 5;",
        "        }",
    )

    /** [padding] filler lines above [region], which moves it down the file. */
    private fun source(padding: Int): String {
        val head = listOf("namespace Game", "{", "    public class Player", "    {")
        val pad = (1..padding).map { "        // padding $it" }
        return (head + pad + region + listOf("    }", "}")).joinToString("\n") + "\n"
    }

    /** The region's hash where it sits unpadded: lines 5 through 8. */
    private fun regionHash(): String = Anchoring.hashText(source(0), 5, 8)

    override fun setUp() {
        super.setUp()
        base = getOrCreateProjectBaseDir()
        // Same two seams WhyGutterTest uses: reload and resolve inline, so a write or an
        // open has reached the markup model by the time the call returns.
        project.service<WhyModelService>().schedule = { _, action -> action() }
        project.service<WhyGutterService>().let { gutter ->
            gutter.pipeline = { _, compute, apply -> apply(compute()) }
            gutter.onEdt = { it() }
        }
        project.messageBus.connect(testRootDisposable)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, WhyEditorGutterListener(project))
        CopyPasteManager.copyTextToClipboard(sentinel)
    }

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

    /** Opens the file for real, so W-7's highlighters are attached, and hands back its editor. */
    private fun openEditor(text: String): Editor {
        val file = VfsTestUtil.createFile(base, sourceName, text)
        return requireNotNull(
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true),
        ) { "no text editor for ${file.path}" }
    }

    /** Caret to the start of a 1-based line. */
    private fun Editor.caretToLine(line: Int) =
        caretModel.moveToOffset(document.getLineStartOffset(line - 1))

    private fun clipboard(): String? =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    private fun context(editor: Editor? = null): DataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .apply { editor?.let { add(CommonDataKeys.EDITOR, it) } }
            .build()

    /** Update then perform, as the action system does, so a disabled action stays unperformed. */
    private fun invoke(action: AnAction, context: DataContext) {
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        if (event.presentation.isEnabled) action.actionPerformed(event)
    }

    private fun note(id: String) = Note(
        id = id,
        taskId = "T-17",
        ts = "2026-08-20T14:02:11Z",
        file = sourceName,
        base = "a3f9c1d",
        anchor = Anchor(symbol, 5, 8, "3f21ab"),
        what = "Clamps the jump impulse.",
        why = "Playtesters reached the roof geometry.",
        flags = emptyList(),
    )

    /** 1. The gutter mark's own menu. One action, and it copies that mark's note id. */
    fun testGutterMenuActionCopiesExactlyTheNoteId() {
        val renderer = WhyNoteGutterIconRenderer(project, note("W-4KQ2"), Resolution.SOLID, startLine = 5, endLine = 8)
        val actions = renderer.getPopupMenuActions().getChildren(null)

        assertEquals("expected one action in the gutter menu", 1, actions.size)
        invoke(actions.single(), context())

        assertEquals("W-4KQ2", clipboard())
    }

    /** The drifted mark offers the same menu — drift changes the icon, not the action. */
    fun testGutterMenuIsOfferedForADriftedNoteToo() {
        val renderer = WhyNoteGutterIconRenderer(project, note("W-DRIF"), Resolution.DRIFTED, startLine = 5, endLine = 8)
        invoke(renderer.getPopupMenuActions().getChildren(null).single(), context())
        assertEquals("W-DRIF", clipboard())
    }

    /** 2. Keyboard entry point: caret inside a solid note's range. */
    fun testCaretInsideANoteCopiesThatNoteId() {
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        val editor = openEditor(source(padding = 0))

        editor.caretToLine(6)
        invoke(WhyCopyReferenceAction(), context(editor))

        assertEquals("W-1111", clipboard())
    }

    /** 3. Caret outside every note: the clipboard is left alone and nothing throws. */
    fun testCaretOutsideEveryNoteLeavesTheClipboardUntouched() {
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        val editor = openEditor(source(padding = 0))

        editor.caretToLine(1)
        invoke(WhyCopyReferenceAction(), context(editor))

        assertEquals("clipboard must survive a miss", sentinel, clipboard())
    }

    /** An orphan has no range in the file, so no caret position can name it. */
    fun testAnOrphanedNoteIsNeverCopied() {
        writeTasks(
            noteJson("W-5555", start = 900, end = 901, hash = "000000", symbolHint = "Game.Player.Teleport"),
        )
        val editor = openEditor(source(padding = 0))

        (1..editor.document.lineCount).forEach { line ->
            editor.caretToLine(line)
            invoke(WhyCopyReferenceAction(), context(editor))
        }

        assertEquals(sentinel, clipboard())
    }

    /**
     * 4. The note's stored range is 5..8; the code now sits at 17..20. The caret position
     * that copies it is the resolved one, and the stored one copies nothing.
     */
    fun testTheRangeThatMattersIsTheResolvedOneNotTheStoredOne() {
        writeTasks(noteJson("W-2222", start = 5, end = 8, hash = regionHash()))
        val editor = openEditor(source(padding = 12))

        editor.caretToLine(6)
        invoke(WhyCopyReferenceAction(), context(editor))
        assertEquals("the stored range is no longer where the code is", sentinel, clipboard())

        editor.caretToLine(18)
        invoke(WhyCopyReferenceAction(), context(editor))
        assertEquals("W-2222", clipboard())
    }

    /** 5. A drifted note is still a note worth asking about, so the caret finds it. */
    fun testCaretInsideADriftedNotesRangeCopiesTheId() {
        // No window in the file hashes to this and the stored range is in bounds:
        // resolution step 4, drifted, still anchored at 5..8.
        writeTasks(noteJson("W-4444", start = 5, end = 8, hash = "000000"))
        val editor = openEditor(source(padding = 0))

        val marker = DriftCheck.stateOf(editor, project, "W-4444")
        assertEquals("fixture must actually be drifted", Resolution.DRIFTED, marker)

        editor.caretToLine(7)
        invoke(WhyCopyReferenceAction(), context(editor))

        assertEquals("W-4444", clipboard())
    }

    /** 6a. Overlapping ranges: the narrowest one containing the caret wins. */
    fun testOverlappingNotesCopyTheNarrowestRangeUnderTheCaret() {
        writeTasks(
            noteJson("W-WIDE", start = 5, end = 8, hash = regionHash()),
            // Drifts in place at 6..7 — inside the wide note, and two lines narrower.
            noteJson("W-NARR", start = 6, end = 7, hash = "000000"),
        )
        val editor = openEditor(source(padding = 0))

        editor.caretToLine(6)
        invoke(WhyCopyReferenceAction(), context(editor))
        assertEquals("W-NARR", clipboard())

        // Line 5 is covered by the wide note only, so the wide note is what a caret there means.
        editor.caretToLine(5)
        invoke(WhyCopyReferenceAction(), context(editor))
        assertEquals("W-WIDE", clipboard())
    }

    /** 6b. Identical ranges: the tie-break is stable, so the same caret copies the same id. */
    fun testTwoNotesOnTheSameRangeResolveToTheSameIdEveryTime() {
        writeTasks(
            noteJson("W-BBBB", start = 5, end = 8, hash = regionHash()),
            noteJson("W-AAAA", start = 5, end = 8, hash = regionHash()),
        )
        val editor = openEditor(source(padding = 0))
        editor.caretToLine(6)

        repeat(3) {
            CopyPasteManager.copyTextToClipboard(sentinel)
            invoke(WhyCopyReferenceAction(), context(editor))
            assertEquals("lowest id, every time", "W-AAAA", clipboard())
        }
    }

    /** No editor in the data context — a shortcut fired from a tool window, say. */
    fun testWithNoEditorTheActionIsDisabledAndCopiesNothing() {
        val action = WhyCopyReferenceAction()
        val event = TestActionEvent.createTestEvent(action, context())
        action.update(event)

        assertFalse("no editor and no note: nothing to copy", event.presentation.isEnabled)
        assertEquals(sentinel, clipboard())
    }

    /** Reads back the state W-7 attached, so the drift fixture is asserted, not assumed. */
    private object DriftCheck {
        fun stateOf(editor: Editor, project: com.intellij.openapi.project.Project, id: String): Resolution? =
            com.intellij.openapi.editor.impl.DocumentMarkupModel
                .forDocument(editor.document, project, false)
                ?.allHighlighters
                ?.mapNotNull { it.gutterIconRenderer as? WhyNoteGutterIconRenderer }
                ?.firstOrNull { it.note.id == id }
                ?.state
    }
}
