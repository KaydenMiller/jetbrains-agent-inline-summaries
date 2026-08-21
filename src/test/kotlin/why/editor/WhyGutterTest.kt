package why.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import why.model.Resolution
import why.resolve.Anchoring
import why.store.TASKS_DIR_NAME
import why.store.WHY_DIR_NAME
import why.store.WhyModelService

/**
 * W-7 against a real project on a real disk: notes on disk under `.why/tasks/` become
 * gutter highlighters on the document markup model of the file they annotate.
 *
 * ### Why [HeavyPlatformTestCase]
 *
 * Same reason as `WhyWatcherVfsTest`: `TaskStore` and `findWhyRoot` read with
 * `java.nio.file`, and `BasePlatformTestCase`'s in-memory fixture files do not exist on
 * disk, so every model fold would be empty and every assertion below would be vacuous.
 * [HeavyPlatformTestCase] gives a project rooted in a real temporary directory.
 *
 * ### The one thing this cannot cover
 *
 * The `<listener>` element itself. W-7 does not own `plugin.xml` (see `REGISTRATION.md`),
 * so [WhyEditorGutterListener] is subscribed here by hand, to the same topic and with the
 * same project the XML element would hand it. Everything downstream of the element is
 * therefore exercised — including a real `FileEditorManager` open and close — and the
 * only unverified step is the one line of XML.
 *
 * Assertions are on `DocumentMarkupModel.forDocument(...).allHighlighters` and each
 * highlighter's `gutterIconRenderer`, never on pixels.
 */
class WhyGutterTest : HeavyPlatformTestCase() {

    private lateinit var base: VirtualFile

    /** The annotated region. Lines 5..8 of [source] when it is built with no padding. */
    private val region = listOf(
        "        void Jump()",
        "        {",
        "            velocity = 5;",
        "        }",
    )

    private val sourceName = "Player.cs"
    private val symbol = "Game.Player.Jump"
    private val taskHeader =
        """{"kind":"task","id":"T-17","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"clamp the jump"}"""

    /** [padding] filler lines between the class brace and [region], which shifts the region down. */
    private fun source(padding: Int): String {
        val head = listOf("namespace Game", "{", "    public class Player", "    {")
        val pad = (1..padding).map { "        // padding $it" }
        return (head + pad + region + listOf("    }", "}")).joinToString("\n") + "\n"
    }

    /** The region's hash as it sits in an unpadded file: lines 5 through 8. */
    private fun regionHash(): String = Anchoring.hashText(source(0), 5, 8)

    override fun setUp() {
        super.setUp()
        base = getOrCreateProjectBaseDir()
        // Reload inline, so a task file written through the virtual file system has reached
        // the model by the time the write returns. Debouncing is WhyWatcherTest's subject.
        project.service<WhyModelService>().schedule = { _, action -> action() }
        // Resolve and apply inline on the calling thread. The production pipeline is a
        // non-blocking read action finishing on the event dispatch thread; running it here
        // would need the event queue pumped between every act and assert. The threading
        // itself is not what these tests are for.
        project.service<WhyGutterService>().let { gutter ->
            gutter.pipeline = { _, compute, apply -> apply(compute()) }
            gutter.onEdt = { it() }
        }
        project.messageBus.connect(testRootDisposable)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, WhyEditorGutterListener(project))
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
        flags: String = """["needs-review","tunable:JumpCeiling"]""",
    ) = """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$sourceName","base":"a3f9c1d",""" +
        """"anchor":{"symbol":"$symbolHint","start":$start,"end":$end,"hash":"$hash"},""" +
        """"what":"Clamps the jump impulse.","why":"Playtesters reached the roof geometry.","flags":$flags}"""

    private fun openSource(text: String): Pair<VirtualFile, Document> {
        val file = VfsTestUtil.createFile(base, sourceName, text)
        FileEditorManager.getInstance(project).openFile(file, false)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) {
            "no document for ${file.path}"
        }
        return file to document
    }

    /** Only the highlighters this plugin added; the daemon owns others in the same model. */
    private fun whyHighlighters(document: Document): List<RangeHighlighter> =
        DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .filter { it.gutterIconRenderer is WhyNoteGutterIconRenderer }
            .sortedBy { it.startOffset }

    private fun renderer(highlighter: RangeHighlighter) =
        highlighter.gutterIconRenderer as WhyNoteGutterIconRenderer

    /** 1-based inclusive line range a highlighter covers, to compare against the resolver's. */
    private fun lines(document: Document, highlighter: RangeHighlighter) =
        (document.getLineNumber(highlighter.startOffset) + 1)..(document.getLineNumber(highlighter.endOffset) + 1)

    /** The icon files are real resources, not a path that silently resolves to a placeholder. */
    fun testIconResourcesExist() {
        assertNotNull("/icons/whyNote.svg is not on the classpath", WhyIcons::class.java.getResource("/icons/whyNote.svg"))
        assertNotNull("/icons/whyNote_dark.svg is not on the classpath", WhyIcons::class.java.getResource("/icons/whyNote_dark.svg"))
        // The dimmed variant is a real composite over the same icon, not the same reference:
        // if these were equal the reveal state would be invisible in the gutter (W-15).
        assertNotSame(WhyIcons.NOTE, WhyIcons.NOTE_DIMMED)
        assertEquals("the sandbox-tuned alpha", 0.7f, WhyIcons.DIMMED_ALPHA)
    }

    /** 1. Solid note, one highlighter, at the resolved range. */
    fun testSolidNoteProducesOneHighlighterAtTheResolvedRange() {
        writeTasks(noteJson("W-1111", start = 5, end = 8, hash = regionHash()))
        val (_, document) = openSource(source(padding = 0))

        val markers = whyHighlighters(document)
        assertEquals("expected exactly one gutter highlighter", 1, markers.size)
        assertEquals(5..8, lines(document, markers.single()))
        assertEquals(Resolution.SOLID, renderer(markers.single()).state)
        assertEquals("W-1111", renderer(markers.single()).note.id)
        // Nothing is revealed, so the icon is the dimmed variant (W-15). `WhyRevealTest` owns
        // the transition between the two.
        assertSame(WhyIcons.NOTE_DIMMED, renderer(markers.single()).getIcon())
    }

    /**
     * 2. The note's stored range says 5..8; the code now sits at 17..20. The highlighter
     * must be at 17..20, which only happens if the resolver is actually in the path.
     */
    fun testMovedCodeIsMarkedAtTheNewRangeNotTheStoredOne() {
        writeTasks(noteJson("W-2222", start = 5, end = 8, hash = regionHash()))
        val (_, document) = openSource(source(padding = 12))

        val marker = whyHighlighters(document).single()
        assertEquals(Resolution.SOLID, renderer(marker).state)
        assertEquals("resolved range, not the stored one", 17..20, lines(document, marker))
    }

    /**
     * 3. Drifted renders, and is indistinguishable from solid in the gutter: same icon, and
     * the difference reaches the reader through the popup text instead.
     */
    fun testDriftedNoteIsNotDistinguishedInTheGutter() {
        writeTasks(
            noteJson("W-3333", start = 5, end = 8, hash = regionHash()),
            // No window in the file hashes to this, and the stored range is in bounds:
            // resolution step 4, drifted at 5..8.
            noteJson("W-4444", start = 6, end = 7, hash = "000000"),
        )
        val (_, document) = openSource(source(padding = 0))

        val byId = whyHighlighters(document).associate { renderer(it).note.id to renderer(it) }
        assertEquals(setOf("W-3333", "W-4444"), byId.keys)

        val solid = byId.getValue("W-3333")
        val drifted = byId.getValue("W-4444")
        // The state is still tracked per note, which is what the popup and the tool window
        // report. It is not expressed in the gutter: both render the same icon, because a
        // rationale note is worth reading whether or not its code moved. Since W-15 the icon
        // states reveal instead, and neither of these is revealed here.
        assertEquals(Resolution.SOLID, solid.state)
        assertEquals(Resolution.DRIFTED, drifted.state)
        assertSame(WhyIcons.NOTE_DIMMED, solid.getIcon())
        assertSame(WhyIcons.NOTE_DIMMED, drifted.getIcon())
        // Drift reaches the reader through the popup text, not through the icon.
        assertTrue(
            "drifted popup must still say the code changed",
            drifted.getTooltipText().contains("changed since this note was written"),
        )
        assertFalse(
            "solid popup must not claim drift",
            solid.getTooltipText().contains("changed since this note was written"),
        )
    }

    /** 4. Orphaned renders nothing at all — not a dimmed icon, not a margin marker. */
    fun testOrphanedNoteProducesNoHighlighter() {
        writeTasks(
            // Hash matches nothing, stored range is past end of file, symbol is absent:
            // resolution step 5.
            noteJson("W-5555", start = 900, end = 901, hash = "000000", symbolHint = "Game.Player.Teleport"),
        )
        val (_, document) = openSource(source(padding = 0))

        assertEquals(emptyList<RangeHighlighter>(), whyHighlighters(document))
    }

    /**
     * 5. The leak test. Three close/reopen cycles; one highlighter per note every time,
     * and none left behind on the closed document.
     */
    fun testCloseAndReopenLeavesExactlyOneHighlighterPerNote() {
        writeTasks(
            noteJson("W-6666", start = 5, end = 8, hash = regionHash()),
            noteJson("W-7777", start = 6, end = 7, hash = "000000"),
        )
        val (file, document) = openSource(source(padding = 0))
        assertEquals(2, whyHighlighters(document).size)

        val manager = FileEditorManager.getInstance(project)
        repeat(3) { cycle ->
            manager.closeFile(file)
            assertEquals("highlighters left on the closed file, cycle $cycle", 0, whyHighlighters(document).size)
            manager.openFile(file, false)
            assertEquals("wrong count after reopen, cycle $cycle", 2, whyHighlighters(document).size)
            assertEquals(
                "duplicate renderers for one note, cycle $cycle",
                setOf("W-6666", "W-7777"),
                whyHighlighters(document).map { renderer(it).note.id }.toSet(),
            )
        }
    }

    /** 6. A model change reaches the open editor. */
    fun testModelChangeRefreshesTheHighlighters() {
        val (_, document) = openSource(source(padding = 0))
        assertEquals("nothing to show before any task file exists", 0, whyHighlighters(document).size)

        // Written through the virtual file system, so this is W-5's real listener, W-5's
        // real reload and the real WHY_MODEL_CHANGED publication, not a synthetic event.
        writeTasks(noteJson("W-8888", start = 5, end = 8, hash = regionHash()))
        assertEquals(1, whyHighlighters(document).size)
        assertEquals("W-8888", renderer(whyHighlighters(document).single()).note.id)

        // A second change replaces rather than adds: the note moves to a drifted state.
        writeTasks(
            noteJson("W-8888", start = 5, end = 8, hash = regionHash()),
            noteJson("W-9999", start = 6, end = 7, hash = "000000"),
        )
        assertEquals(2, whyHighlighters(document).size)

        // And a task file emptied of notes clears the gutter.
        writeTasks()
        assertEquals(0, whyHighlighters(document).size)
    }

    /** Two notes on the same line stay two independent marks, each with its own popup. */
    fun testTwoNotesOnTheSameLineProduceTwoIndependentMarks() {
        writeTasks(
            noteJson("W-AAAA", start = 5, end = 8, hash = regionHash()),
            noteJson("W-BBBB", start = 5, end = 8, hash = regionHash()),
        )
        val (_, document) = openSource(source(padding = 0))

        val markers = whyHighlighters(document)
        assertEquals(2, markers.size)
        assertEquals(setOf(5..8), markers.map { lines(document, it) }.toSet())
        // Distinct marks, so the gutter does not merge them and each popup names one note.
        assertFalse(renderer(markers[0]) == renderer(markers[1]))
        assertEquals(setOf("W-AAAA", "W-BBBB"), markers.map { renderer(it).note.id }.toSet())
        markers.forEach { assertTrue(renderer(it).getTooltipText().contains(renderer(it).note.id)) }
    }
}
