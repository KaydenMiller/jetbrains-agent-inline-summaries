package why.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import why.model.Resolution
import why.model.Resolved
import why.resolve.Anchoring
import why.store.TASKS_DIR_NAME
import why.store.WHY_DIR_NAME
import why.store.WhyModelService
import why.store.tasksDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.swing.tree.DefaultMutableTreeNode

/**
 * W-10's acceptance list, asserted on the built tree model rather than on pixels.
 *
 * ### Why [HeavyPlatformTestCase]
 *
 * Same reason as `WhyGutterTest` and `WhyWatcherVfsTest`: `TaskStore`, `findWhyRoot`
 * and W-9's collection all read with `java.nio.file`, and `BasePlatformTestCase`'s
 * fixture files do not exist on disk, so every fold would be empty and every
 * assertion here vacuous. This gives a real project directory that both the virtual
 * file system and `java.nio` can see.
 *
 * ### What this cannot cover
 *
 * The `<toolWindow>` element's own behaviour — a headless test has no tool window
 * stripe and no real `ToolWindowManager` content — so [WhyToolWindowPanel] is
 * constructed directly, exactly as `WhyToolWindowFactory` constructs it.
 * [testPluginXmlNamesTheFactoryClassThatExists] covers the one failure mode of the
 * XML that a headless test can reach: a class name that does not resolve.
 */
class WhyToolWindowTest : HeavyPlatformTestCase() {

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
        // Reload inline: a task file written through the virtual file system has reached
        // the model by the time the write returns. Debouncing is WhyWatcherTest's subject.
        project.service<WhyModelService>().schedule = { _, action -> action() }
        panel = WhyToolWindowPanel(project)
        disposeOnTearDown(panel)
        // Resolve and apply on the calling thread. Production is a non-blocking read
        // action finishing on the event dispatch thread; running that here would need the
        // event queue pumped between every act and assert, and the threading is not what
        // these tests are for. Same seam and same reason as WhyGutterTest.
        panel.pipeline = { compute, apply -> apply(compute()) }
    }

    // ---- fixture writing ----------------------------------------------------

    private fun header(id: String, prompt: String) =
        """{"kind":"task","id":"$id","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"$prompt"}"""

    private fun note(
        id: String,
        start: Int,
        end: Int,
        hash: String,
        file: String = sourceName,
        symbolHint: String = symbol,
        flags: String = """[]""",
    ) = """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$file","base":"a3f9c1d",""" +
        """"anchor":{"symbol":"$symbolHint","start":$start,"end":$end,"hash":"$hash"},""" +
        """"what":"Clamps the jump impulse.","why":"Playtesters reached the roof geometry.","flags":$flags}"""

    /** Writes `.why/tasks/<taskId>.jsonl` through the virtual file system. */
    private fun writeTask(taskId: String, vararg lines: String): VirtualFile =
        VfsTestUtil.createFile(base, "$WHY_DIR_NAME/$TASKS_DIR_NAME/$taskId.jsonl", lines.joinToString("\n"))

    private fun openSource(text: String, name: String = sourceName): VirtualFile =
        VfsTestUtil.createFile(base, name, text)

    // ---- tree readers -------------------------------------------------------

    private fun root() = panel.treeModel.root as DefaultMutableTreeNode

    private fun groupNodes(): List<DefaultMutableTreeNode> =
        (0 until root().childCount).map { root().getChildAt(it) as DefaultMutableTreeNode }

    /** Every top-level row's label: a task's header, or the orphans group's name. */
    private fun groupLabels(): List<String> = groupNodes().map {
        when (val value = it.userObject) {
            is WhyTaskGroup -> value.header
            else -> value as String
        }
    }

    private fun notesUnder(label: String): List<Resolved> {
        val node = groupNodes().single { node ->
            (node.userObject as? WhyTaskGroup)?.header == label || node.userObject == label
        }
        return (0 until node.childCount).map {
            (node.getChildAt(it) as DefaultMutableTreeNode).userObject as Resolved
        }
    }

    /** Every note row in the whole tree, group irrespective. */
    private fun allNoteIds(): List<String> =
        groupNodes().flatMap { node ->
            (0 until node.childCount).map {
                ((node.getChildAt(it) as DefaultMutableTreeNode).userObject as Resolved).note.id
            }
        }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    // ---- 1 -----------------------------------------------------------------

    /** Two tasks with notes produce two groups, each headed by that task's `prompt`. */
    fun testTwoTasksProduceTwoGroupsHeadedByTheirPrompts() {
        openSource(source(padding = 0))
        writeTask("T-17", header("T-17", "clamp the jump"), note("W-1111", 5, 8, regionHash()))
        writeTask("T-18", header("T-18", "buffer the input"), note("W-2222", 5, 8, regionHash()))

        panel.refresh()

        assertEquals(listOf("clamp the jump", "buffer the input"), groupLabels())
        assertEquals(listOf("W-1111"), notesUnder("clamp the jump").map { it.note.id })
        assertEquals(listOf("W-2222"), notesUnder("buffer the input").map { it.note.id })
    }

    // ---- 2 -----------------------------------------------------------------

    /**
     * R5.4.3 — a task file with no header record. The group is present and usable;
     * the header shows the task id taken from the filename, which is the same string
     * Copy Reference and the gutter popup print for that task.
     */
    fun testATaskFileWithNoHeaderRecordIsHeadedByItsFilenameTaskId() {
        openSource(source(padding = 0))
        writeTask("T-99", note("W-3333", 5, 8, regionHash()))

        panel.refresh()

        assertEquals(listOf("T-99"), groupLabels())
        val group = groupNodes().single().userObject as WhyTaskGroup
        assertNull("no header record means no prompt", group.task.prompt)
        assertEquals("T-99", group.task.id)
        assertEquals(listOf("W-3333"), group.notes.map { it.note.id })
    }

    // ---- 3 -----------------------------------------------------------------

    /**
     * Clicking a note whose code moved navigates to the *resolved* line, not to
     * `anchor.start`. Three padding lines push the region from 5..8 down to 8..11,
     * so the resolved line and the stored one differ by construction.
     */
    fun testClickingANoteWhoseCodeMovedNavigatesToTheResolvedLine() {
        openSource(source(padding = 3))
        writeTask("T-17", header("T-17", "clamp the jump"), note("W-1111", 5, 8, regionHash()))

        panel.refresh()

        val row = notesUnder("clamp the jump").single()
        assertEquals(Resolution.SOLID, row.state)
        assertEquals("resolved to the region's new position", 8, row.start)
        assertTrue("the resolved line must differ from anchor.start", row.start != row.note.anchor.start)

        panel.navigate(row)

        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) {
            "navigate() opened no editor"
        }
        assertEquals(sourceName, FileDocumentManager.getInstance().getFile(editor.document)?.name)
        // Caret lines are 0-based; the resolved range starts at 1-based line 8.
        assertEquals(7, editor.caretModel.logicalPosition.line)
        assertTrue(
            "the caret must not land on anchor.start",
            editor.caretModel.logicalPosition.line != row.note.anchor.start - 1,
        )
    }

    // ---- 4 -----------------------------------------------------------------

    /** The `needs-review` filter changes the visible set, and turning it off restores it. */
    fun testNeedsReviewFilterChangesTheVisibleSetAndTurningItOffRestoresIt() {
        openSource(source(padding = 0))
        writeTask(
            "T-17",
            header("T-17", "clamp the jump"),
            note("W-1111", 5, 8, regionHash()),
            note("W-2222", 5, 8, regionHash(), flags = """["needs-review"]"""),
        )
        writeTask("T-18", header("T-18", "buffer the input"), note("W-3333", 5, 8, regionHash()))

        panel.refresh()
        assertEquals(listOf("W-1111", "W-2222", "W-3333"), allNoteIds())

        panel.setNeedsReviewOnly(true)
        assertEquals(listOf("W-2222"), allNoteIds())
        // T-18 has no flagged note, so its group goes with its rows rather than
        // remaining as a header with nothing under it.
        assertEquals(listOf("clamp the jump"), groupLabels())

        panel.setNeedsReviewOnly(false)
        assertEquals(listOf("W-1111", "W-2222", "W-3333"), allNoteIds())
        assertEquals(listOf("clamp the jump", "buffer the input"), groupLabels())
    }

    // ---- 5 -----------------------------------------------------------------

    /**
     * §6.2 — an orphan is listed under the orphans group and nowhere else. The note
     * hashes a region that is not in the file and names a symbol the file does not
     * contain, and its stored range is past the end of the file, which is the
     * resolver's ORPHANED case.
     */
    fun testOrphansAppearInTheOrphansGroupAndNowhereElse() {
        openSource(source(padding = 0))
        writeTask(
            "T-17",
            header("T-17", "clamp the jump"),
            note("W-1111", 5, 8, regionHash()),
            note("W-9999", 400, 410, "abcdef", symbolHint = "Nothing.AtAll"),
        )

        panel.refresh()

        assertEquals(listOf("clamp the jump", ORPHANS_GROUP), groupLabels())
        assertEquals(listOf("W-9999"), notesUnder(ORPHANS_GROUP).map { it.note.id })
        assertEquals(Resolution.ORPHANED, notesUnder(ORPHANS_GROUP).single().state)
        assertEquals("W-9999 must not also sit under its task", listOf("W-1111"), notesUnder("clamp the jump").map { it.note.id })
        assertEquals(listOf("W-1111", "W-9999"), allNoteIds())
    }

    // ---- 6 -----------------------------------------------------------------

    /**
     * Archive removes the task from the view; the task file on disk is byte-identical
     * afterwards. Hashed, not measured by length: a rewrite of the same size would
     * pass a length check.
     */
    fun testArchiveRemovesTheTaskFromTheViewAndLeavesTheFileByteIdentical() {
        openSource(source(padding = 0))
        writeTask("T-17", header("T-17", "clamp the jump"), note("W-1111", 5, 8, regionHash()))
        writeTask("T-18", header("T-18", "buffer the input"), note("W-2222", 5, 8, regionHash()))

        panel.refresh()
        assertEquals(listOf("clamp the jump", "buffer the input"), groupLabels())

        val files = listOf("T-17.jsonl", "T-18.jsonl").map { tasksDir(Path.of(base.path)).resolve(it) }
        val before = files.map(::sha256)

        panel.archive("T-17")

        assertEquals(listOf("buffer the input"), groupLabels())
        assertEquals(listOf("W-2222"), allNoteIds())
        assertEquals("Archive must not touch the corpus", before, files.map(::sha256))
        // Also still on disk and still parsed: the model kept the task the view dropped.
        assertTrue(files.all { Files.isRegularFile(it) })
        assertEquals(
            listOf("T-17", "T-18"),
            project.service<WhyModelService>().model(Path.of(base.path)).tasks.map { it.task.id },
        )
    }

    // ---- 7 -----------------------------------------------------------------

    /**
     * A note whose `file` does not exist is not shown, even after a reload re-folds it
     * into the model. W-9's collection is startup-only, so the re-fold below genuinely
     * puts the dead note back in the model — asserted here so the test fails if that
     * ever stops being true and the filter starts passing vacuously.
     */
    fun testANoteForAMissingFileIsNotShownEvenAfterAReloadRefoldsIt() {
        openSource(source(padding = 0))
        writeTask("T-17", header("T-17", "clamp the jump"), note("W-1111", 5, 8, regionHash()))
        val dead = writeTask(
            "T-18",
            header("T-18", "buffer the input"),
            note("W-2222", 5, 8, regionHash(), file = "Deleted.cs"),
        )
        val root = Path.of(base.path)

        // Startup fold: W-9 drops W-2222 and, with it, T-18's now-empty group.
        project.service<WhyModelService>().initialLoad(root)
        panel.refresh()
        assertEquals(listOf("clamp the jump"), groupLabels())

        // An unrelated change to the *other* task file re-folds from the parsed corpus.
        project.service<WhyModelService>().taskFilesChanged(listOf(Path.of(dead.path).parent.resolve("T-17.jsonl")))

        // The model has W-2222 back — this is the flicker the tool window has to absorb.
        val refolded = project.service<WhyModelService>().model(root)
        assertTrue(
            "precondition: the re-fold is expected to restore the dead note",
            refolded.tasks.flatMap { it.notes }.any { it.id == "W-2222" },
        )

        panel.refresh()

        assertEquals(listOf("clamp the jump"), groupLabels())
        assertEquals(listOf("W-1111"), allNoteIds())
    }

    // ---- registration and rendering ----------------------------------------

    /**
     * The `<toolWindow>` element cannot be exercised headlessly, but its one
     * text-typo failure mode can: the class it names must exist and be a factory.
     */
    fun testPluginXmlNamesTheFactoryClassThatExists() {
        val xml = requireNotNull(javaClass.getResource("/META-INF/plugin.xml")).readText()
        val factory = "why.ui.WhyToolWindowFactory"
        assertTrue("plugin.xml does not register $factory", xml.contains("""factoryClass="$factory""""))
        assertTrue(
            "$factory is not a ToolWindowFactory",
            com.intellij.openapi.wm.ToolWindowFactory::class.java.isAssignableFrom(Class.forName(factory)),
        )
    }

    /**
     * §6.2 asks for drift to be marked in the tool window. Asserted on the row text
     * rather than on the rendered component, and paired with the solid case so the
     * marker is known to be a difference and not a constant.
     */
    fun testRowTextCarriesDriftStateAndFlags() {
        openSource(source(padding = 0))
        writeTask(
            "T-17",
            header("T-17", "clamp the jump"),
            note("W-1111", 5, 8, regionHash()),
            // Same lines, wrong hash, symbol present in the file: DRIFTED at 5..8.
            note("W-2222", 5, 8, "000000", flags = """["needs-review","tunable:JumpCeiling"]"""),
        )

        panel.refresh()

        val rows = notesUnder("clamp the jump").associateBy { it.note.id }
        assertEquals(Resolution.SOLID, rows.getValue("W-1111").state)
        assertEquals(Resolution.DRIFTED, rows.getValue("W-2222").state)
        assertFalse(noteRowText(rows.getValue("W-1111")).contains("drifted"))
        val drifted = noteRowText(rows.getValue("W-2222"))
        assertTrue(drifted, drifted.contains("drifted"))
        assertTrue(drifted, drifted.contains("needs-review") && drifted.contains("tunable:JumpCeiling"))
        assertTrue("the row must say where the note resolved to", drifted.contains("$sourceName:5"))
    }
}
