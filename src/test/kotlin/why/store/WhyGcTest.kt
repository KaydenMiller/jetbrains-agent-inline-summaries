package why.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

/**
 * W-9. Plain JUnit 4: [gcMissingFiles] is a filter over [WhyModel] and touches no
 * platform type. The corpus is real — written with `java.nio` and folded by the
 * real [TaskStore] — because the thing under test is a decision about the disk.
 *
 * The wiring into [WhyModelService.initialLoad] is asserted in [WhyWatcherVfsTest].
 */
class WhyGcTest {

    private lateinit var tmp: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("whygc")
    }

    @After
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- fixture helpers -------------------------------------------------------

    private fun taskFile(name: String, vararg lines: String): Path =
        Files.write(
            Files.createDirectories(tasksDir(tmp)).resolve(name),
            lines.joinToString("\n").toByteArray(),
        )

    private fun headerLine(id: String) =
        """{"kind":"task","id":"$id","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"why $id"}"""

    private fun noteLine(id: String, file: String) =
        """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$file","base":"a3f9c1d",""" +
            """"anchor":{"symbol":"Foo.Bar","start":10,"end":20,"hash":"3f21ab"},""" +
            """"what":"w","why":"y","flags":[]}"""

    /** Creates `<tmp>/<relative>` with content, parent directories included. */
    private fun sourceFile(relative: String): Path {
        val path = tmp.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.write(path, "class X {}\n".toByteArray())
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }

    private fun gc() = gcMissingFiles(tmp, TaskStore(tmp).loadAll())

    // --- tests ----------------------------------------------------------------

    /**
     * R7.6, the acceptance case: three files referenced, one absent, so two files'
     * worth of notes survive and the result reports exactly one note dropped.
     */
    @Test
    fun aNoteForAnAbsentFileIsDroppedAndCounted() {
        taskFile(
            "T-91.jsonl",
            headerLine("T-91"),
            noteLine("W-0001", "src/A.cs"),
            noteLine("W-0002", "src/B.cs"),
            noteLine("W-0003", "src/Deleted.cs"),
        )
        sourceFile("src/A.cs")
        sourceFile("src/B.cs")

        val result = gc()

        assertEquals(1, result.droppedNotes)
        assertEquals(listOf("src/Deleted.cs"), result.missingFiles)
        assertEquals(setOf("src/A.cs", "src/B.cs"), result.model.notesByFile.keys)
        assertEquals(listOf("W-0001", "W-0002"), result.model.tasks.single().notes.map { it.id })
        // The task survives with its prompt, because it still has notes to group.
        assertEquals("why T-91", result.model.tasks.single().task.prompt)
    }

    /**
     * §2: the plugin never writes. The pass drops from memory only, so the task
     * file is byte-identical afterwards — hashed, not measured by length.
     */
    @Test
    fun taskFileOnDiskIsByteIdenticalAfterThePass() {
        val file = taskFile(
            "T-91.jsonl",
            headerLine("T-91"),
            noteLine("W-0001", "src/A.cs"),
            noteLine("W-0002", "src/Deleted.cs"),
        )
        sourceFile("src/A.cs")
        val before = sha256(file)

        val result = gc()

        assertEquals(1, result.droppedNotes)
        assertEquals("task file was rewritten by the collection pass", before, sha256(file))
        // Nothing else under .why/ appeared or vanished either — no index.json written.
        assertEquals(
            listOf("T-91.jsonl"),
            Files.list(tasksDir(tmp)).use { s -> s.map { it.fileName.toString() }.sorted().toList() },
        )
        assertEquals(
            listOf("tasks"),
            Files.list(tmp.resolve(WHY_DIR_NAME)).use { s -> s.map { it.fileName.toString() }.sorted().toList() },
        )
    }

    /**
     * Design decision: a task whose every note dropped is removed from `tasks`
     * rather than kept as an empty group, since R7.4's tool window would otherwise
     * show a task header with nothing under it. A task that still has one note
     * stays, so the two outcomes are asserted side by side.
     */
    @Test
    fun aTaskWhoseEveryNoteWasDroppedVanishesFromTheModel() {
        taskFile("T-91.jsonl", headerLine("T-91"), noteLine("W-0001", "src/Gone.cs"), noteLine("W-0002", "src/AlsoGone.cs"))
        taskFile("T-92.jsonl", headerLine("T-92"), noteLine("W-0003", "src/Kept.cs"), noteLine("W-0004", "src/Gone.cs"))
        sourceFile("src/Kept.cs")

        val result = gc()

        assertEquals(3, result.droppedNotes)
        assertEquals(listOf("T-92"), result.model.tasks.map { it.task.id })
        assertEquals(setOf("src/Kept.cs"), result.model.notesByFile.keys)
    }

    /**
     * A header-only task file has no notes to drop, so this pass leaves it alone
     * even though its group is empty. Removing notes is R7.6's remit; an empty task
     * that was empty on disk is W-10's presentation problem.
     */
    @Test
    fun aTaskThatNeverHadNotesIsNotRemoved() {
        taskFile("T-91.jsonl", headerLine("T-91"))
        taskFile("T-92.jsonl", headerLine("T-92"), noteLine("W-0001", "src/Gone.cs"))

        val result = gc()

        assertEquals(1, result.droppedNotes)
        assertEquals(listOf("T-91"), result.model.tasks.map { it.task.id })
    }

    /**
     * Two paths that exist in some sense but cannot denote a project file: one
     * escaping the root with `..`, one naming a directory. Both drop.
     */
    @Test
    fun aPathOutsideTheRootAndOneNamingADirectoryBothDrop() {
        // A root one level down, so the note escaping it with `..` can point at a file
        // that really exists — a bare existence check would keep it.
        val root = Files.createDirectories(tmp.resolve("project"))
        Files.write(tmp.resolve("Outside.cs"), "outside\n".toByteArray())
        Files.createDirectories(root.resolve("src/Directory.cs"))
        Files.write(Files.createDirectories(root.resolve("src")).resolve("A.cs"), "class X {}\n".toByteArray())
        Files.write(
            Files.createDirectories(tasksDir(root)).resolve("T-91.jsonl"),
            listOf(
                headerLine("T-91"),
                noteLine("W-0001", "../Outside.cs"),
                noteLine("W-0002", "src/Directory.cs"),
                noteLine("W-0003", "src/A.cs"),
            ).joinToString("\n").toByteArray(),
        )

        val result = gcMissingFiles(root, TaskStore(root).loadAll())

        assertEquals(2, result.droppedNotes)
        assertEquals(setOf("../Outside.cs", "src/Directory.cs"), result.missingFiles.toSet())
        assertEquals(setOf("src/A.cs"), result.model.notesByFile.keys)
    }

    /**
     * An absolute `file` key is not a §5.3 key, but if it lands inside the root it
     * names a file that exists, and R7.6 drops notes for files that do not exist —
     * so the note is kept. An absolute key pointing elsewhere fails the containment
     * check like any other escaping path.
     */
    @Test
    fun anAbsoluteFileKeyInsideTheRootIsKeptAndOneOutsideItDrops() {
        val inside = sourceFile("src/A.cs").toAbsolutePath().toString()
        val outside = Files.write(tmp.parent.resolve("gc-outside.cs"), "x\n".toByteArray())
        taskFile("T-91.jsonl", headerLine("T-91"), noteLine("W-0001", inside), noteLine("W-0002", "$outside"))

        val result = gc()

        assertEquals(1, result.droppedNotes)
        assertEquals(listOf("$outside"), result.missingFiles)
        assertEquals(listOf("W-0001"), result.model.tasks.single().notes.map { it.id })
        Files.deleteIfExists(outside)
    }

    /**
     * Nothing missing: nothing dropped, nothing to log, and the same [WhyModel]
     * instance comes back rather than an equal copy — the pass is a no-op on a
     * healthy corpus.
     */
    @Test
    fun everyFilePresentDropsNothingAndReportsNothing() {
        taskFile(
            "T-91.jsonl",
            headerLine("T-91"),
            noteLine("W-0001", "src/A.cs"),
            noteLine("W-0002", "src/B.cs"),
        )
        sourceFile("src/A.cs")
        sourceFile("src/B.cs")
        val model = TaskStore(tmp).loadAll()

        val result = gcMissingFiles(tmp, model)

        assertEquals(0, result.droppedNotes)
        assertEquals(emptyList<String>(), result.missingFiles)
        assertSame(model, result.model)
    }
}
