package why.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Plain JUnit 4 for the two parts of `WhyWatcher.kt` that hold no platform type:
 * [taskFilePath] (string work only) and [ReloadCoalescer] (no clock, no threads).
 * No fixture and no sleeps anywhere in this file.
 *
 * The platform adapter — [WhyTasksVfsListener] plus [WhyModelService] — is in
 * [WhyWatcherVfsTest], which needs a project.
 */
class WhyWatcherTest {

    private lateinit var tmp: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("whywatcher")
    }

    @After
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- fake scheduler --------------------------------------------------------

    /**
     * Stands in for the application's scheduled executor. Queues actions instead of
     * timing them; [elapse] runs everything queued, which is what the real executor
     * does once each delay has passed.
     *
     * That is enough to reproduce real timing exactly, because a superseded action is
     * a no-op whenever it fires: calling [elapse] once after several touches is "all
     * of them landed inside one window", and calling it between touches is "the window
     * expired in between".
     */
    private class FakeScheduler {
        val delays = mutableListOf<Long>()
        private val pending = ArrayDeque<() -> Unit>()

        fun schedule(delay: Long, action: () -> Unit) {
            delays += delay
            pending += action
        }

        fun elapse() {
            while (pending.isNotEmpty()) pending.removeFirst()()
        }
    }

    private class Recorder {
        val batches = mutableListOf<Set<Path>>()
        fun reload(paths: Set<Path>) {
            batches += paths
        }
    }

    private fun path(name: String): Path = Path.of("/p/$WHY_DIR_NAME/$TASKS_DIR_NAME/$name")

    // --- ReloadCoalescer -------------------------------------------------------

    /** Four touches of one file inside the window collapse to exactly one reload. */
    @Test
    fun touchesInsideTheWindowCauseExactlyOneReload() {
        val clock = FakeScheduler()
        val seen = Recorder()
        val coalescer = ReloadCoalescer(RELOAD_DEBOUNCE_MS, clock::schedule, seen::reload)
        val file = path("T-91.jsonl")

        repeat(4) { coalescer.touch(listOf(file)) }
        clock.elapse()

        assertEquals(listOf(setOf(file)), seen.batches)
        // One scheduled action per touch; three of the four were superseded.
        assertEquals(listOf(RELOAD_DEBOUNCE_MS, RELOAD_DEBOUNCE_MS, RELOAD_DEBOUNCE_MS, RELOAD_DEBOUNCE_MS), clock.delays)
    }

    /** A touch after the window has expired is a second reload, not a merge. */
    @Test
    fun touchesBeyondTheWindowCauseTwoReloads() {
        val clock = FakeScheduler()
        val seen = Recorder()
        val coalescer = ReloadCoalescer(RELOAD_DEBOUNCE_MS, clock::schedule, seen::reload)
        val file = path("T-91.jsonl")

        coalescer.touch(listOf(file))
        clock.elapse()
        coalescer.touch(listOf(file))
        clock.elapse()

        assertEquals(listOf(setOf(file), setOf(file)), seen.batches)
    }

    /**
     * Two different task files inside one window are one reload of *both*, not one
     * reload of one of them: the debounce is per batch, and dropping a path would
     * lose a task's notes until something else touched it.
     */
    @Test
    fun twoTaskFilesInOneWindowAreOneReloadOfBoth() {
        val clock = FakeScheduler()
        val seen = Recorder()
        val coalescer = ReloadCoalescer(RELOAD_DEBOUNCE_MS, clock::schedule, seen::reload)
        val a = path("T-91.jsonl")
        val b = path("T-92.jsonl")

        coalescer.touch(listOf(a))
        coalescer.touch(listOf(b))
        clock.elapse()

        assertEquals(1, seen.batches.size)
        assertEquals(setOf(a, b), seen.batches.single())
    }

    /** An event batch with no task file in it schedules nothing. */
    @Test
    fun emptyTouchSchedulesNothing() {
        val clock = FakeScheduler()
        val seen = Recorder()
        val coalescer = ReloadCoalescer(RELOAD_DEBOUNCE_MS, clock::schedule, seen::reload)

        coalescer.touch(emptyList())
        clock.elapse()

        assertEquals(emptyList<Long>(), clock.delays)
        assertEquals(emptyList<Set<Path>>(), seen.batches)
    }

    // --- taskFilePath ----------------------------------------------------------

    @Test
    fun jsonlDirectlyUnderTasksIsAccepted() {
        val accepted = taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl")
        assertEquals(Path.of("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl"), accepted)
        assertEquals(Path.of("/home/k/proj"), whyRootOfTaskFile(accepted!!))
    }

    @Test
    fun nonJsonlUnderTasksIsRejected() {
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.json"))
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl.bak"))
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/README.md"))
    }

    /** Under `.why/` but not under `tasks/`: `index.json` and anything beside it. */
    @Test
    fun jsonlUnderWhyButNotTasksIsRejected() {
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/index.json"))
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/scratch.jsonl"))
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/archive/T-90.jsonl"))
    }

    @Test
    fun jsonlElsewhereInTheProjectIsRejected() {
        assertNull(taskFilePath("/home/k/proj/Assets/Scripts/data.jsonl"))
        assertNull(taskFilePath("/home/k/proj/tasks/T-91.jsonl"))
        assertNull(taskFilePath("/home/k/proj/T-91.jsonl"))
    }

    /**
     * A subdirectory of `tasks/` is rejected, matching [TaskStore.loadAll], which
     * folds only the directory's own entries.
     */
    @Test
    fun jsonlInASubdirectoryOfTasksIsRejected() {
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/archive/T-90.jsonl"))
    }

    /**
     * Directories.
     *
     * `tasks/` itself, and an ordinary subdirectory of it, are rejected by the suffix
     * test. A directory whose *name* ends in `.jsonl` is accepted here and rejected
     * one layer down: [taskFilePath] is deliberately pure string work, so it cannot
     * tell a directory from a file, and [TaskStore.reloadTask] tests
     * `Files.isRegularFile` before parsing — a directory therefore reaches the store
     * and is treated as a deleted task, which is the same handling a real deleted file
     * gets. Recorded here as the actual behaviour, not as a wish.
     */
    @Test
    fun directoriesAreRejectedByTheSuffixTestExceptOneNamedLikeATaskFile() {
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME"))
        assertNull(taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/archive"))
        assertEquals(
            Path.of("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/odd.jsonl"),
            taskFilePath("/home/k/proj/$WHY_DIR_NAME/$TASKS_DIR_NAME/odd.jsonl"),
        )
    }

    /**
     * A `.why` segment that is not the outermost one is accepted, and resolves to its
     * own root — `/home/k/proj/src`, not `/home/k/proj`. That is R8.1's nested-root
     * rule (the nearest `.why/` wins) and not an accident of the string match: the
     * marker is found with `lastIndexOf`, so the innermost `.why/tasks/` is the one
     * that counts.
     */
    @Test
    fun aNestedWhyDirectoryResolvesToItsOwnRoot() {
        val nested = taskFilePath("/home/k/proj/src/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl")
        assertEquals(Path.of("/home/k/proj/src/$WHY_DIR_NAME/$TASKS_DIR_NAME/T-91.jsonl"), nested)
        assertEquals(Path.of("/home/k/proj/src"), whyRootOfTaskFile(nested!!))
    }

    // --- deletion, through the coalescer --------------------------------------

    /**
     * A deleted task file drops its notes from the model.
     *
     * Driven through [ReloadCoalescer] rather than [WhyModelService] because the
     * service needs a [com.intellij.openapi.project.Project]; the wiring from the
     * service to the store is the same call, `TaskStore.reloadTask`.
     */
    @Test
    fun deletingATaskFileRemovesItsNotesFromTheModel() {
        val tasks = Files.createDirectories(tasksDir(tmp))
        val a = Files.write(
            tasks.resolve("T-91.jsonl"),
            (header("T-91") + "\n" + note("W-1111", "A.cs")).toByteArray(),
        )
        Files.write(
            tasks.resolve("T-92.jsonl"),
            (header("T-92") + "\n" + note("W-2222", "B.cs")).toByteArray(),
        )
        val store = TaskStore(tmp)
        var model = store.loadAll()
        assertEquals(setOf("A.cs", "B.cs"), model.notesByFile.keys)

        val clock = FakeScheduler()
        val coalescer = ReloadCoalescer(RELOAD_DEBOUNCE_MS, clock::schedule, { batch ->
            batch.forEach { model = store.reloadTask(it) }
        })

        Files.delete(a)
        coalescer.touch(listOf(a))
        clock.elapse()

        assertEquals(listOf("T-92"), model.tasks.map { it.task.id })
        assertEquals(setOf("B.cs"), model.notesByFile.keys)
        assertTrue(model.notesByFile.getValue("B.cs").isNotEmpty())
    }

    private fun header(id: String) =
        """{"kind":"task","id":"$id","ts":"2026-08-20T14:01:55Z","base":"a3f9c1d","prompt":"p"}"""

    private fun note(id: String, file: String) =
        """{"kind":"note","id":"$id","ts":"2026-08-20T14:02:11Z","file":"$file","base":"a3f9c1d",""" +
            """"anchor":{"symbol":"Foo.Bar","start":10,"end":20,"hash":"3f21ab"},""" +
            """"what":"w","why":"y","flags":[]}"""
}
