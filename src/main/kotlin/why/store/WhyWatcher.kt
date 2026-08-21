package why.store

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * R7.5 — keeping the in-memory model in step with `.why/tasks/` on disk without an
 * IDE restart or a manual refresh action.
 *
 * Three pieces, split so that only the last one needs a running IDE:
 *
 *  - [taskFilePath] — pure path filtering, no filesystem and no platform types.
 *  - [ReloadCoalescer] — pure debounce, no clock and no threads.
 *  - [WhyModelService] / [WhyTasksVfsListener] — the platform adapter.
 *
 * ### Listener mechanism
 *
 * `VirtualFileManager.VFS_CHANGES` with a [BulkFileListener], declared in
 * `plugin.xml` under `projectListeners`. Chosen over
 * `VirtualFileManager.addAsyncFileListener` because the only reason to prefer
 * `AsyncFileListener` is to move expensive per-event work off the write thread,
 * and there is no expensive work here: [taskFilePath] is a suffix test and a
 * substring search, and everything after it is handed to a scheduled pooled
 * thread. `AsyncFileListener` would also need code to register it — a startup
 * activity or an eager service — where the message-bus route needs one XML
 * element and no Kotlin at all.
 *
 * A project-level subscriber does receive `VFS_CHANGES`, which is published on
 * the application bus: the topic is constructed with
 * `Topic.BroadcastDirection.TO_DIRECT_CHILDREN` (verified in the bytecode of
 * `VirtualFileManager`'s static initialiser, platform 261), and a project bus is
 * a direct child of the application bus.
 *
 * ### What the platform does and does not observe
 *
 * `.why/tasks/T-NN.jsonl` is normally written by an agent process *outside* the IDE.
 * An earlier version of this header derived a latency claim from platform 261
 * bytecode — about 15 s unfocused, immediate on focus gain. A sandbox run
 * disproved it, so what follows is measurement only, from Rider 261.23567.144 on
 * macOS 15.6.1, five `why note` appends from a shell, deltas taken from the
 * `why: reloaded` lines in `log_runRider/idea.log`:
 *
 *  - The path needs a virtual-file-system record before any of this applies. At
 *    project open it does not have one; [WhyProjectOpenActivity] gives it one.
 *  - Window in the background: 5.4 s, 17.1 s, 42.1 s.
 *  - Window frontmost: 11.6 s, 15.7 s.
 *  - No user action in either case, and no `Sync` invoked. Spread that wide over
 *    five samples means the interval is not the thing to design against; what is
 *    established is that it lands, unattended, in seconds to tens of seconds.
 *  - The old claim that a focus *gain* surfaces a pending write immediately was
 *    not reproduced and is not restated. It also was not fairly retested: in the
 *    two runs that regained focus with a change pending, one was the
 *    `/private/tmp` fixture below, where nothing was ever delivered. Untested,
 *    not disproved.
 *
 * Location matters more than any of the above. The same fixture, same plugin,
 * same IDE, under `/private/tmp` produced **zero** reloads for an external
 * append: 49 s unfocused, 25 s after focus gain, and still nothing 54 s after
 * the tasks directory had been refreshed into the virtual file system *and*
 * registered with [com.intellij.openapi.vfs.LocalFileSystem.addRootToWatch]
 * (`belongsToWatchRoots` reported true throughout). Copied to
 * `~/Documents/workspaces`, the identical fixture reloaded in 5 s. Native
 * watching of that temporary directory is what fails; nothing in this plugin can
 * substitute for it short of a poller, which R7.5 rules out. Fixtures for this
 * plugin's sandbox checks therefore belong in a normal location.
 */
private val LOG = Logger.getInstance("why.store.WhyWatcher")

/**
 * How long to wait for a task file to go quiet before re-parsing it.
 *
 * Lower bound: it has to exceed the gap between the events of one logical batch.
 * The writer appends one line per note as separate open/write/close calls, and a
 * refresh session can also report create plus content-change for the same new
 * file; both land within single-digit milliseconds.
 *
 * Upper bound: it is added to a refresh latency measured at 5.4-42.1 seconds (see
 * above), so anything under a second is invisible.
 *
 * 250 ms sits one to two orders of magnitude above the lower bound and at worst
 * 2 percent of the smallest measured refresh latency. Reasoned from the write
 * pattern rather than tuned: the sandbox run that produced those five latencies
 * never showed a split batch, but it also appended one note at a time.
 */
const val RELOAD_DEBOUNCE_MS: Long = 250

/** Published after [WhyModelService] has replaced the model for one `.why/` root. */
fun interface WhyModelListener {
    /** [root] is the parent of `.why/`, as returned by [findWhyRoot]. Called on a pooled thread. */
    fun modelChanged(root: Path, model: WhyModel)
}

/** Project-level. Subscribe on `project.messageBus`. */
val WHY_MODEL_CHANGED: Topic<WhyModelListener> = Topic(WhyModelListener::class.java)

/**
 * The task file [vfsPath] denotes, or null when it is not one.
 *
 * Pure string work on a virtual-file-system path, which is forward-slash
 * separated on every platform. Deliberately does not call [findWhyRoot]: that
 * stats the disk, and a delete event names a path that no longer exists.
 *
 * Returns null for a `.jsonl` in a subdirectory of `tasks/`, because [TaskStore]
 * only folds the directory's own entries.
 */
fun taskFilePath(vfsPath: String): Path? {
    if (!vfsPath.endsWith(".jsonl")) return null
    val marker = "/$WHY_DIR_NAME/$TASKS_DIR_NAME/"
    val at = vfsPath.lastIndexOf(marker)
    if (at < 0) return null
    if ('/' in vfsPath.substring(at + marker.length)) return null
    return Path.of(vfsPath)
}

/** The `.why/` root owning a path returned by [taskFilePath]. */
fun whyRootOfTaskFile(taskFile: Path): Path = taskFile.parent.parent.parent

/**
 * Trailing-edge debounce, per batch rather than per path: paths accumulate, and
 * the batch is released only once [delayMs] has passed with nothing new arriving.
 *
 * No clock and no threads. [schedule] receives a delay and an action; each call to
 * [touch] schedules one, and all but the last are discarded by the generation
 * check when they run. A test can therefore substitute a scheduler that collects
 * actions and run them itself, and reproduce real timing exactly, because a
 * superseded action is a no-op whenever it fires.
 */
class ReloadCoalescer(
    private val delayMs: Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val reload: (Set<Path>) -> Unit,
) {
    private val dirty = LinkedHashSet<Path>()
    private var generation = 0L

    fun touch(paths: Collection<Path>) {
        if (paths.isEmpty()) return
        val mine = synchronized(this) {
            dirty += paths
            ++generation
        }
        schedule(delayMs) { fire(mine) }
    }

    private fun fire(generationAtSchedule: Long) {
        val batch = synchronized(this) {
            if (generationAtSchedule != generation) return
            LinkedHashSet(dirty).also { dirty.clear() }
        }
        reload(batch)
    }
}

/**
 * The model for every `.why/` root this project has seen a change under (R8.1
 * allows several: nested content roots, or two worktrees in one window).
 *
 * A root's [TaskStore] is created on first change under it and folded once with
 * [TaskStore.loadAll]; every change after that goes through
 * [TaskStore.reloadTask], which re-parses one file. The corpus is never re-read
 * as a whole again.
 *
 * ponytail: one lock over the whole service rather than per root. Reloads are
 * rare, seconds apart at worst, and hold the lock only for a parse of one file;
 * split per root if a project ever has enough `.why/` roots for that to contend.
 */
@Service(Service.Level.PROJECT)
class WhyModelService(private val project: Project) {

    private val stores = HashMap<Path, TaskStore>()
    private val models = HashMap<Path, WhyModel>()

    /**
     * Overridden by tests to run the action inline, so a fixture test asserts on
     * the model without sleeping. Production value is the application's shared
     * scheduler — no thread or executor of our own to shut down.
     */
    internal var schedule: (Long, () -> Unit) -> Unit = { delay, action ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(action, delay, TimeUnit.MILLISECONDS)
    }

    private val coalescer =
        ReloadCoalescer(RELOAD_DEBOUNCE_MS, { delay, action -> schedule(delay, action) }, ::reload)

    /** The model for [root], folding the corpus on first ask. */
    fun model(root: Path): WhyModel = synchronized(this) {
        models.getOrPut(root) { stores.getOrPut(root) { TaskStore(root) }.loadAll() }
    }

    /** Called by [WhyTasksVfsListener]. Returns once the reload is queued, not done. */
    fun taskFilesChanged(files: Collection<Path>) = coalescer.touch(files)

    /**
     * W-5c — fold [root]'s corpus at project open and announce it, so a project
     * opened on existing notes has a model before anything changes. Called by
     * [WhyProjectOpenActivity]; every later fold comes from a change event.
     *
     * W-9's collection runs on this fold only, per R7.6's "on startup".
     *
     * Running it on every fold was tried and reverted. It drops notes for a file
     * that does not exist *yet*: an agent that appends a note before the file it
     * annotates lands on disk loses that note from the model until the next task
     * file change, and no event re-adds it. Six tests caught this by writing the
     * task file before the source file.
     *
     * The cost of collecting only here is that a later [reloadTask] re-folds from
     * the parsed corpus and brings the dropped notes of *other* files back, so the
     * model is not self-consistent across folds. That is a display concern rather
     * than a correctness one, and R7.4's tool window filters it — see W-10.
     */
    fun initialLoad(root: Path) {
        val gc = gcMissingFiles(root, model(root))
        synchronized(this) { models[root] = gc.model }
        if (gc.droppedNotes > 0) {
            LOG.info(
                "why: dropped ${gc.droppedNotes} note(s) for ${gc.missingFiles.size} missing file(s) " +
                    "under $root (${gc.missingFiles.joinToString()})",
            )
        }
        LOG.info("why: initial load under $root -> ${summarise(gc.model)}")
        publish(root, gc.model)
    }

    private fun reload(files: Set<Path>) {
        if (project.isDisposed) return
        val changed = synchronized(this) {
            files.groupBy(::whyRootOfTaskFile).map { (root, group) ->
                val store = stores[root]
                val model = if (store == null) {
                    // First change under this root: nothing is held yet, so there is
                    // no single file to reload — fold once, which already includes it.
                    stores.getOrPut(root) { TaskStore(root) }.loadAll()
                } else {
                    var latest = models[root] ?: WhyModel.EMPTY
                    group.forEach { latest = store.reloadTask(it) }
                    latest
                }
                models[root] = model
                Triple(root, model, group)
            }
        }
        // The acceptance log line for W-5. Info, not debug: a person tailing idea.log
        // during the sandbox check should not have to raise the logger's level.
        changed.forEach { (root, model, group) ->
            LOG.info(
                "why: reloaded ${group.size} task file(s) under $root " +
                    "(${group.joinToString { it.fileName.toString() }}) -> ${summarise(model)}",
            )
            publish(root, model)
        }
    }

    private fun summarise(model: WhyModel) =
        "${model.tasks.size} task(s), ${model.notesByFile.values.sumOf { it.size }} note(s) " +
            "across ${model.notesByFile.size} file(s)"

    private fun publish(root: Path, model: WhyModel) {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(WHY_MODEL_CHANGED).modelChanged(root, model)
    }
}

/**
 * Thin adapter: turn a virtual-file-system event batch into task file paths and
 * hand them to [WhyModelService]. Holds no state, so everything worth testing
 * lives in [taskFilePath], [ReloadCoalescer] and [WhyModelService].
 *
 * Registered per project, so it also fires for other open projects' files. That
 * is left unfiltered on purpose: filtering by content root needs a read action on
 * the write thread, and the cost of not filtering is one redundant fold of
 * another project's corpus, keyed under that project's own root.
 *
 * ponytail: a delete of `.why/` or `.why/tasks/` itself arrives as one directory
 * event with no per-child events, so those notes stay in the model until the
 * project is reopened. Add an "is this an ancestor of a tracked tasks directory"
 * check if anyone actually removes a corpus mid-session.
 */
internal class WhyTasksVfsListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val changed = LinkedHashSet<Path>()
        for (event in events) {
            // A rename or move can take a file out of tasks/ as well as into it, so
            // both ends matter. Every other event kind has one path.
            when (event) {
                is VFilePropertyChangeEvent -> {
                    taskFilePath(event.oldPath)?.let(changed::add)
                    taskFilePath(event.newPath)?.let(changed::add)
                }
                is VFileMoveEvent -> {
                    taskFilePath(event.oldPath)?.let(changed::add)
                    taskFilePath(event.newPath)?.let(changed::add)
                }
                else -> taskFilePath(event.path)?.let(changed::add)
            }
        }
        if (changed.isEmpty()) return
        project.service<WhyModelService>().taskFilesChanged(changed)
    }
}
