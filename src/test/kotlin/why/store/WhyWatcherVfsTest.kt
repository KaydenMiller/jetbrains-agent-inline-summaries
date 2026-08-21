package why.store

import com.intellij.openapi.components.service
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.VfsTestUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * The platform half of W-5: a task file written through the platform's own virtual
 * file system reaches [WhyModelService] and replaces the model.
 *
 * ### Why [HeavyPlatformTestCase] and not `BasePlatformTestCase`
 *
 * `BasePlatformTestCase` can create files, but its fixture puts them in the
 * in-memory `TempFileSystem` under paths like `/src/Foo.txt` that do not exist on
 * disk. [TaskStore] reads with `java.nio.file`, so every reload there would fold an
 * empty corpus and the assertion below could not distinguish "the listener never
 * fired" from "the file was not readable". [HeavyPlatformTestCase] opens a real
 * project in a real temporary directory on the local file system, so
 * `getOrCreateProjectBaseDir()` is a `LocalFileSystem` file and the same path is
 * visible to both the virtual file system and [TaskStore].
 *
 * ### What this proves and what it does not
 *
 * Proves: the `projectListeners` element in `plugin.xml` is loaded (the plugin is
 * built into the test sandbox at `plugins-test/` and `idea.plugins.path` points
 * there), [WhyTasksVfsListener] is instantiated with this project, its filtering
 * picks the file out of a real event batch, [WhyModelService] is resolved as a light
 * service, and the folded model reaches the [WHY_MODEL_CHANGED] topic on the
 * project bus — which also confirms in practice that a project-level subscriber
 * receives the application-level `VFS_CHANGES` topic.
 *
 * Does not prove: that an *external* writer's append is noticed. Everything here is
 * written through the platform's own API inside a write action, so the events are
 * published synchronously and no refresh is involved — and creating the file that
 * way also makes its directory known to the virtual file system, which is exactly
 * the precondition an external append does not satisfy. That is what made this test
 * pass while the sandbox saw nothing (W-5c). The measured latencies, and the
 * location that defeats watching entirely, are in the `WhyWatcher.kt` header;
 * neither is reachable from a headless test.
 */
class WhyWatcherVfsTest : HeavyPlatformTestCase() {

    private val seen = mutableListOf<Pair<Path, WhyModel>>()

    private fun taskFileText(vararg notes: String) =
        (listOf("""{"kind":"task","id":"T-91","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"p"}""") + notes)
            .joinToString("\n")

    private fun note(id: String, file: String) =
        """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$file","base":"a3f9c1d",""" +
            """"anchor":{"symbol":"Foo.Bar","start":10,"end":20,"hash":"3f21ab"},""" +
            """"what":"w","why":"y","flags":[]}"""

    /**
     * W-5c's initial load: a corpus already on disk, written with `java.nio` so the
     * virtual file system has never seen it and no event is involved, is folded and
     * announced when [WhyModelService.initialLoad] is called.
     *
     * Also the wiring half of W-9: `C.cs` is created on disk and `Gone.cs` is not,
     * so the model published at startup is the collected one. The pass itself is
     * covered without a fixture in [WhyGcTest].
     */
    fun testInitialLoadFoldsACorpusThePlatformHasNeverSeen() {
        project.messageBus.connect(testRootDisposable)
            .subscribe(WHY_MODEL_CHANGED, WhyModelListener { root, model -> seen += root to model })

        val root = Path.of(getOrCreateProjectBaseDir().path)
        val tasks = tasksDir(root)
        Files.createDirectories(tasks)
        Files.writeString(tasks.resolve("T-91.jsonl"), taskFileText(note("W-2222", "C.cs"), note("W-2223", "Gone.cs")))
        Files.writeString(root.resolve("C.cs"), "class C {}\n")

        project.service<WhyModelService>().initialLoad(root)

        assertEquals(listOf(root), seen.map { it.first })
        assertEquals(listOf("T-91"), seen.single().second.tasks.map { it.task.id })
        assertEquals(setOf("C.cs"), seen.single().second.notesByFile.keys)
        assertEquals(listOf("W-2222"), seen.single().second.tasks.single().notes.map { it.id })
    }

    fun testTaskFileWrittenThroughTheVirtualFileSystemReachesTheModel() {
        // Inline instead of the shared scheduled executor, so the reload has happened
        // by the time the write action returns and the test never sleeps. Debouncing is
        // covered without a fixture in WhyWatcherTest.
        project.service<WhyModelService>().schedule = { _, action -> action() }
        project.messageBus.connect(testRootDisposable)
            .subscribe(WHY_MODEL_CHANGED, WhyModelListener { root, model -> seen += root to model })

        val base = getOrCreateProjectBaseDir()
        val file = VfsTestUtil.createFile(
            base,
            "$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl",
            taskFileText(note("W-1111", "A.cs")),
        )

        assertTrue(
            "WhyTasksVfsListener did not fire for ${file.path}; is projectListeners registered?",
            seen.isNotEmpty(),
        )
        val (root, model) = seen.last()
        assertEquals(Path.of(base.path), root)
        assertEquals(setOf("A.cs"), model.notesByFile.keys)
        assertEquals(listOf("T-91"), model.tasks.map { it.task.id })
        assertEquals("W-1111", model.notesByFile.getValue("A.cs").single().id)

        // An append is a second event batch and a second reload of the same file, not a
        // re-fold of the corpus: TaskStore keeps the parse and reloadTask replaces one entry.
        val appended = seen.size
        VfsTestUtil.createFile(
            base,
            "$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl",
            taskFileText(note("W-1111", "A.cs"), note("W-1112", "B.cs")),
        )
        assertTrue("no reload after the append", seen.size > appended)
        assertEquals(setOf("A.cs", "B.cs"), seen.last().second.notesByFile.keys)

        // Deleting the file forgets the task, through the real listener this time.
        val beforeDelete = seen.size
        VfsTestUtil.deleteFile(file)
        assertFalse(Files.exists(Path.of(file.path)))
        assertTrue("no reload after the delete", seen.size > beforeDelete)
        assertEquals(emptyList<TaskWithNotes>(), seen.last().second.tasks)
        assertEquals(emptyMap<String, List<why.model.Note>>(), seen.last().second.notesByFile)
    }
}
