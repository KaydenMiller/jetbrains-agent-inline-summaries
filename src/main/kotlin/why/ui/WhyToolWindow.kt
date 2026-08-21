package why.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import why.editor.notePopupHtml
import why.editor.revealNoteRegion
import why.model.Note
import why.model.Resolution
import why.model.Resolved
import why.model.Task
import why.model.needsReview
import why.resolve.Resolver
import why.store.WHY_MODEL_CHANGED
import why.store.WhyModel
import why.store.WhyModelListener
import why.store.WhyModelService
import why.store.findWhyRoot
import why.store.gcMissingFiles
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * R7.4 — every note in the corpus, grouped by task, with the task `prompt` as the
 * group header; click to navigate; a `needs-review` filter; a separate orphans
 * group; a per-task Archive action.
 *
 * Requirements-document §9 milestone 4: this is the surface that decides whether
 * inline rationale beats reading the transcript (§1.1).
 *
 * The tree is built by [buildTree], a pure function of the model plus a resolver
 * plus the two view flags, so every acceptance criterion is asserted on data
 * rather than on pixels. [WhyToolWindowPanel] is the Swing shell around it.
 *
 * ### Archive is view-only, and view-only for one session
 *
 * The plugin never writes (§2), so Archive can only remove a task from this
 * window; the `.why/` files are untouched, which [ARCHIVE_DESCRIPTION] says in
 * those words because "Archive" otherwise reads as deletion.
 *
 * The archived set is held in this panel and dies with the IDE. Persisting it is
 * possible without `.why/` — a `PersistentStateComponent` on the workspace file —
 * and is deliberately not done:
 *
 *  - it needs no store, no state class and no migration story, and §10.3 lists
 *    task lifecycle as an open question, so a persisted format now would be a
 *    guess written to disk;
 *  - a hide that survives restart hides notes with no visible way back, which
 *    needs a second control ("Show archived") to be recoverable at all. R6.2.2's
 *    rule for orphans is retained *and visible*; a permanent invisible hide is
 *    the opposite default;
 *  - restart restoring the full list is self-healing: the worst case is one
 *    re-archive, against a corpus the same restart re-reads from disk anyway.
 *
 * ponytail: session-scoped archive. Upgrade path if a real corpus accumulates
 * enough tasks for re-archiving to annoy: a `PersistentStateComponent` storing
 * task ids in `StoragePathMacros.WORKSPACE_FILE`, plus a "Show archived" toggle.
 *
 * ### Orphans
 *
 * §6.2 gives them their own group and no gutter icon, so this window is the only
 * place an orphan is visible. A click cannot navigate to a range that no longer
 * exists, so it opens the file without moving the caret — see [navigate]. The
 * stored line numbers are deliberately not used for that jump: they describe
 * where the code was, and for an orphan the resolver has already established that
 * nothing at those lines is the annotated region, so scrolling there would assert
 * something false.
 *
 * ### Notes for files that no longer exist
 *
 * Filtered here, at render time, by calling W-9's [gcMissingFiles] on the model
 * before grouping. W-9 runs its pass only on the startup fold
 * (`WhyModelService.initialLoad`), because applying it to every fold drops notes
 * for files that do not exist *yet*. The documented consequence is that any later
 * reload re-folds from the parsed corpus and brings back notes whose file is gone;
 * without this call, editing one task file would make a deleted file's notes
 * reappear in the tree.
 */

/** One task's group: the header row and the notes under it. Orphans are not here. */
data class WhyTaskGroup(val task: Task, val notes: List<Resolved>) {
    /**
     * R5.4.3: a task file with no header record parses to a [Task] whose `prompt`
     * is null and whose `id` is the filename stem, so the header falls back to the
     * task id — the string the notes' own rows and Copy Reference already use.
     */
    val header: String get() = task.prompt?.takeIf { it.isNotBlank() } ?: task.id
}

/** What the tree renders. [orphans] is the §6.2 group, drawn last. */
data class WhyTree(val tasks: List<WhyTaskGroup>, val orphans: List<Resolved>)

/** Label of the separate top-level group for orphaned notes (§6.2). */
const val ORPHANS_GROUP: String = "Orphaned"

/** R7.4's Archive tooltip. Says what Archive does *not* do, because the word implies it. */
const val ARCHIVE_DESCRIPTION: String =
    "Hides this task from this window only, until the IDE restarts. " +
        "Nothing is written, moved or deleted: the .why/ files on disk are untouched."

/**
 * The tree for one `.why/` root.
 *
 * [resolve] is injected so the grouping is testable without a document, and so the
 * caller can cache file text across notes; production is [WhyToolWindowPanel.resolver].
 *
 * A task with no visible notes is dropped rather than shown as an empty header,
 * matching [gcMissingFiles]'s reasoning: a group with nothing under it is a row the
 * user can neither expand nor act on. That includes a task whose every note is
 * orphaned — those notes are in the orphans group instead.
 */
fun buildTree(
    root: Path,
    model: WhyModel,
    resolve: (Note) -> Resolved,
    needsReviewOnly: Boolean,
    archived: Set<String>,
): WhyTree {
    val live = gcMissingFiles(root, model).model
    val orphans = mutableListOf<Resolved>()
    val groups = mutableListOf<WhyTaskGroup>()
    for (withNotes in live.tasks) {
        if (withNotes.task.id in archived) continue
        // Filter before resolving: a filtered-out note costs no document sweep.
        val rows = withNotes.notes.filter { !needsReviewOnly || it.needsReview }.map(resolve)
        val (orphaned, anchored) = rows.partition { it.state == Resolution.ORPHANED }
        orphans += orphaned
        if (anchored.isNotEmpty()) groups += WhyTaskGroup(withNotes.task, anchored)
    }
    return WhyTree(groups, orphans)
}

/**
 * One row of note text: id, `what`, where it resolved to *now*, drift state, flags.
 *
 * §6.2 asks for drift to be marked in the tool window, and R7.4's filter is on a
 * flag, so both are on the row rather than only in the popup — the flags are the
 * part of a note that says what the agent guessed at (§5.3), and a list you have to
 * click through to see them is a list you skim.
 */
fun noteRowText(resolved: Resolved): String {
    val note = resolved.note
    val where = resolved.start?.let { "${note.file}:$it" } ?: note.file
    val state = when (resolved.state) {
        Resolution.SOLID -> ""
        Resolution.DRIFTED -> " · drifted"
        Resolution.ORPHANED -> " · orphaned"
    }
    val flags = if (note.flags.isEmpty()) "" else " · " + note.flags.joinToString(" ")
    return "${note.id}  ${note.what.lineSequence().first()} — $where$state$flags"
}

/** Root child nodes in render order: task groups, then the orphans group if non-empty. */
internal fun treeRoot(tree: WhyTree): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode()
    tree.tasks.forEach { group ->
        val node = DefaultMutableTreeNode(group)
        group.notes.forEach { node.add(DefaultMutableTreeNode(it)) }
        root.add(node)
    }
    if (tree.orphans.isNotEmpty()) {
        val node = DefaultMutableTreeNode(ORPHANS_GROUP)
        tree.orphans.forEach { node.add(DefaultMutableTreeNode(it)) }
        root.add(node)
    }
    return root
}

/**
 * The tool window's content.
 *
 * ### Threading
 *
 * Same shape as W-7's `WhyGutterService`, and for the same reason: resolution is
 * `O(7 x lines)` hash computations per unresolved note and it reads document text,
 * so it never runs on the event dispatch thread. One `ReadAction.nonBlocking` per
 * refresh, `coalesceBy(this)` so a burst of model changes costs one pass,
 * `expireWith(this)` so a closing project does not apply into a disposed tree, and
 * `finishOnUiThread` for the Swing mutation.
 *
 * Unlike W-7 this needs text for files with **no open editor**, since the window
 * lists the whole corpus. [textOf] takes the open document when there is one — so
 * unsaved edits are what gets resolved against — and otherwise reads the bytes with
 * `java.nio` and decodes them. Cost: one full read and decode per annotated file
 * per refresh, off the event dispatch thread, coalesced to one refresh per model
 * change. `FileDocumentManager.getDocument` was the alternative and is worse here:
 * it would put a cached `Document` in the platform's document map for every
 * annotated file in the project, which is memory held for a list view.
 */
class WhyToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val rootNode = DefaultMutableTreeNode()

    /** Asserted on by the tests; the tree's own model, not a copy. */
    internal val treeModel = DefaultTreeModel(rootNode)

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = WhyCellRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = clicked(event)
        })
    }

    // Written by the toolbar and popup actions on the event dispatch thread, read by
    // whichever thread called refresh -- W-5's topic callback arrives on a pooled one.
    @Volatile
    private var needsReviewOnly = false
    private val archived: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The last tree applied, so the archive action can map a selection to a task. */
    private var current = WhyTree(emptyList(), emptyList())

    /**
     * Compute off the event dispatch thread, apply on it. Replaced by tests with an
     * inline runner, the same seam and the same reason as `WhyGutterService.pipeline`.
     */
    internal var pipeline: (() -> WhyTree, (WhyTree) -> Unit) -> Unit = { compute, apply ->
        ReadAction.nonBlocking(Callable { compute() })
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { apply(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private val filterAction = object : ToggleAction(
        "Only Notes Needing Review",
        "Shows only notes the agent flagged needs-review",
        AllIcons.General.Filter,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = needsReviewOnly
        override fun setSelected(e: AnActionEvent, state: Boolean) = setNeedsReviewOnly(state)
    }

    private val archiveAction = object : AnAction("Archive Task", ARCHIVE_DESCRIPTION, null) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTaskId() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedTaskId()?.let(::archive)
        }
    }

    init {
        toolbar = buildToolbar()
        setContent(ScrollPaneFactory.createScrollPane(tree))
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(archiveAction), "WhyToolWindowPopup")
        // W-5's topic: a note may have appeared, moved file or vanished, so the whole
        // tree is rebuilt. Every fold, including the startup one, arrives here.
        project.messageBus.connect(this)
            .subscribe(WHY_MODEL_CHANGED, WhyModelListener { _, _ -> refresh() })
    }

    private fun buildToolbar(): JComponent {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("WhyToolWindow", DefaultActionGroup(filterAction, archiveAction), true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    /**
     * The `.why/` root this window lists.
     *
     * ponytail: the project base path's root only. R8.1 allows several (nested
     * content roots, two worktrees in one window) and W-5 keys models per root;
     * upgrade path is to run [buildTree] per root and concatenate, once a project
     * that actually has two of them exists. Reading content roots needs
     * `ProjectRootManager` and a read action, which the base path does not.
     */
    private fun whyRoot(): Path? =
        project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }?.let(::findWhyRoot)

    /**
     * Rebuilds the tree. Safe to call from any thread; [pipeline] does the hopping.
     *
     * A project with no `.why/` goes through the same path with an empty tree rather
     * than returning early: [applyTree] mutates Swing state, so it must not be reached
     * from the pooled thread W-5's topic callback arrives on.
     */
    fun refresh() {
        val root = whyRoot()
        val onlyReview = needsReviewOnly
        val hidden = archived.toSet()
        pipeline({
            if (root == null) WhyTree(emptyList(), emptyList())
            else buildTree(root, project.service<WhyModelService>().model(root), resolver(root), onlyReview, hidden)
        }) { applyTree(it) }
    }

    /** R7.4's filter toggle. */
    internal fun setNeedsReviewOnly(only: Boolean) {
        needsReviewOnly = only
        refresh()
    }

    /** R7.4's per-task Archive. View only — see [ARCHIVE_DESCRIPTION]. */
    internal fun archive(taskId: String) {
        archived += taskId
        refresh()
    }

    /**
     * Resolves each note against the current text of its file, reading each file at
     * most once per refresh. A file that cannot be read at all resolves as orphaned
     * rather than disappearing (R6.2.2); [gcMissingFiles] has already removed the
     * notes whose file is genuinely absent, so this covers the unreadable rest.
     */
    private fun resolver(root: Path): (Note) -> Resolved {
        val texts = HashMap<String, String?>()
        return { note ->
            val text = texts.getOrPut(note.file) { textOf(root, note.file) }
            if (text == null) Resolved(note, Resolution.ORPHANED, null, null)
            else Resolver.resolve(note, text)
        }
    }

    /** Open document if there is one, else the bytes on disk. Called inside a read action. */
    private fun textOf(root: Path, key: String): String? {
        val path = runCatching { root.resolve(key) }.getOrNull() ?: return null
        LocalFileSystem.getInstance().findFileByNioFile(path)
            ?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            ?.let { return it.immutableCharSequence.toString() }
        // Not Files.readString: it throws on malformed UTF-8 where this substitutes,
        // which is TaskStore's reasoning and the same tolerance the resolver wants.
        return runCatching { String(Files.readAllBytes(path), StandardCharsets.UTF_8) }.getOrNull()
    }

    /** On the event dispatch thread. Replaces the tree wholesale. */
    private fun applyTree(tree: WhyTree) {
        current = tree
        treeModel.setRoot(treeRoot(tree))
        TreeUtil.expandAll(this.tree)
    }

    private fun clicked(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            ?.let { if (it is Resolved) navigate(it) }
    }

    /**
     * R7.4 — navigation targets [Resolved.start], the line the resolver found the
     * code at now, never [why.model.Anchor.start], which is where it was written.
     *
     * An orphan has no range, so it opens the file with no caret move (see the file
     * header). Called on the event dispatch thread.
     *
     * W-14: the resolved range is then revealed in the editor that was opened, so the row
     * click says how far the annotated region runs and not only where it starts.
     * `openTextEditor` rather than `descriptor.navigate` because it hands back that editor;
     * a file with no text editor at all falls back to the plain navigation.
     */
    internal fun navigate(resolved: Resolved) {
        val root = whyRoot() ?: return
        val path = runCatching { root.resolve(resolved.note.file) }.getOrNull() ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        val line = resolved.start
        val descriptor =
            if (line == null) OpenFileDescriptor(project, file)
            else OpenFileDescriptor(project, file, line - 1, 0)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor == null) descriptor.navigate(true)
        else revealNoteRegion(project, editor, resolved.note.id, resolved.start, resolved.end)
    }

    /** The task a row belongs to, whether the task header or one of its notes is selected. */
    private fun selectedTaskId(): String? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val value = node.userObject) {
            is WhyTaskGroup -> value.task.id
            // A note row: the orphans group holds notes from several tasks, so the id
            // comes from the note rather than from its parent node.
            is Resolved -> value.note.taskId.takeIf { id -> current.tasks.any { it.task.id == id } }
            else -> null
        }
    }

    override fun dispose() = Unit
}

/**
 * Text from [noteRowText] and [WhyTaskGroup.header]; the note's hover text is W-7's
 * [notePopupHtml], so there is one renderer of a note's content in the plugin.
 */
private class WhyCellRenderer : com.intellij.ui.ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
            is WhyTaskGroup -> {
                append(node.header, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${node.task.id}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = node.task.id
            }
            is Resolved -> {
                // Drift is stated in the row text and not by dimming it. W-7 withdrew the
                // gutter's alpha de-emphasis on the same grounds: a note is worth reading
                // whether or not its code moved, so a drifted row is marked, not faded.
                append(noteRowText(node), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = notePopupHtml(node.note, node.state)
            }
            is String -> append(node, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }
}

/**
 * Registered in `plugin.xml`. [DumbAware] because nothing here touches an index:
 * the corpus is read from disk and resolved by hashing text, so the window is
 * usable while the project is still indexing.
 */
class WhyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WhyToolWindowPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, null, false),
        )
        panel.refresh()
    }
}
