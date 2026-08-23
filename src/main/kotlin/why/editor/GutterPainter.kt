package why.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import why.model.Note
import why.model.Resolution
import why.resolve.Resolver
import why.store.WHY_MODEL_CHANGED
import why.store.WhyModelListener
import why.store.WhyModelService
import why.store.findWhyRoot
import why.store.projectRelativePath
import java.nio.file.Path
import java.util.WeakHashMap
import java.util.concurrent.Callable
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.ScrollPaneConstants
import kotlin.math.min

/**
 * R7.1 and R7.2 — a gutter icon on every solid and drifted note's *resolved* range,
 * and a popup carrying `what`, `why`, the flags and the note id.
 *
 * ### Why not `LineMarkerProvider`
 *
 * `LineMarkerProvider` is driven by the daemon walking a `PsiFile`, and this plugin has
 * no Program Structure Interface dependency (`PLAN.md` section 2, decision D1: Rider does
 * not expose C# as IntelliJ PSI in the frontend). Highlighters are added to a
 * [com.intellij.openapi.editor.markup.MarkupModel] directly instead, which needs nothing
 * but a [Document].
 *
 * ### Which markup model
 *
 * `DocumentMarkupModel.forDocument(document, project, true)` — the model keyed by
 * *(document, project)*, not the per-editor `editor.markupModel`.
 *
 * Consequence for split editors, stated because the two models fail differently: the
 * document-level model is shared by every editor showing that document, so adding one
 * highlighter per *editor* into it puts two highlighters on the same line when a file is
 * open in a split, and the gutter draws both — duplicate icons. The per-editor model has
 * the mirror-image property: nothing is shared, so every editor needs its own attachment
 * and its own teardown, and a file open in three splits resolves three times.
 *
 * This file takes the document-level model and keys its bookkeeping by [Document]
 * ([attached]), so attachment is once per document however many editors show it: no
 * duplicates in a split, and one resolution pass per file rather than per view. Notes are
 * a property of the file, not of the view, so that is also the correct cardinality.
 *
 * ### Which lifecycle hook
 *
 * `FileEditorManagerListener` on the project bus, declared in `plugin.xml` under
 * `projectListeners` (see `REGISTRATION.md`). Chosen over `EditorFactoryListener`
 * because:
 *
 *  - it is project-scoped, and everything downstream needs the [Project] — the model
 *    service, the markup model and `.why/` root discovery are all per project. An
 *    application-level `EditorFactoryListener` receives editors with no project and has
 *    to recover one from the document.
 *  - it fires for *file* editors only. `EditorFactoryListener.editorCreated` also fires
 *    for console views, commit-message fields, diff panes and every other throwaway
 *    editor, each of which would need filtering back out.
 *  - `fileClosed` is the matching teardown signal, and it is the one that says whether
 *    the file is still open somewhere else (see [WhyGutterService.fileClosed]).
 *
 * ### R6.2.1
 *
 * Drift is informational. Nothing here creates a `Notification`, a balloon, a modal
 * dialog, a problem-view entry or an inspection. A drifted note differs from a solid one
 * by one line of popup text and by nothing else — not even by icon alpha, which since
 * W-15 states reveal state (see [WhyIcons]).
 */

/**
 * The gutter icon, 12x12, with a `_dark` variant that [IconLoader] selects by theme, in two
 * opacities.
 *
 * ### What the two opacities mean
 *
 * W-15, Kayden's ruling (2026-08-21): **reveal state**, and nothing else. [NOTE_DIMMED] for
 * a note whose region is not currently revealed, [NOTE] for the one whose region is. At
 * most one reveal exists project-wide ([revealedNoteId]), so at most one icon is opaque, and
 * the difference between the two is what tells the reader that clicking the mark again puts
 * the region away.
 *
 * ### What they do not mean
 *
 * Not drift. Section 6.2 asks for drifted notes to be "visually de-emphasised"; that was
 * built as a 40%- then 65%-alpha composite and then withdrawn on Kayden's call, because a
 * rationale note is worth reading whether or not its code moved since. Drift is reported by
 * the popup text ([stateLine]) and by the tool window (R7.4), and by nothing in the gutter.
 * A drifted note and a solid one at the same reveal state are the same pixels.
 */
object WhyIcons {
    /**
     * Alpha of the not-revealed icon. One place, because this is a value the sandbox tunes:
     * 0.4 was rejected as looking like a rendering fault, 0.65 was accepted, and 0.7 is the
     * conservative reading of "slightly dimmed".
     */
    const val DIMMED_ALPHA: Float = 0.7f

    val NOTE: Icon = IconLoader.getIcon("/icons/whyNote.svg", WhyIcons::class.java)

    /** The resting state: no reveal outstanding for this note. */
    val NOTE_DIMMED: Icon = IconLoader.getTransparentIcon(NOTE, DIMMED_ALPHA)
}

/**
 * R7.2 — the popup body for one note: `what`, `why`, every flag, the note id.
 *
 * One string serves both reveal routes, hover and click: it is the gutter mark's tooltip
 * text and the click popup's content. Pure function of the note, so the content is
 * assertable without a screenshot.
 *
 * The task id is included alongside the note id because the tool window (R7.4) groups by
 * task and the two identifiers are what tie a gutter icon to a row there.
 *
 * [state] contributes one informational line. It never says "warning" or asks for an
 * action (R6.2.1).
 *
 * [wrapWidthPx] wraps the body in a fixed-width `div`, in device pixels, for the two
 * consumers whose component the platform owns and which therefore cannot be clamped from
 * the outside: the gutter mark's hover tooltip ([WhyNoteGutterIconRenderer.getTooltipText])
 * and the tool window's row tooltips. Left null for the click popup, where
 * [notePopupComponent] measures and clamps the real component — a declared `div` width
 * would there be a *minimum* as much as a maximum, since Swing has no `max-width`, and a
 * two-line note would open as a half-empty box.
 */
fun notePopupHtml(note: Note, state: Resolution, wrapWidthPx: Int? = null): String {
    val body = StringBuilder("<html><body>")
    if (wrapWidthPx != null) body.append("<div style='width: ").append(wrapWidthPx).append("px'>")
    body.append("<b>").append(html(note.what)).append("</b>")
    body.append("<br/>").append(html(note.why))
    if (note.flags.isNotEmpty()) {
        body.append("<br/><br/>flags: ").append(note.flags.joinToString(", ") { html(it) })
    }
    body.append("<br/><br/><small>")
        .append(html(note.id)).append(" &middot; task ").append(html(note.taskId))
        .append(" &middot; ").append(stateLine(state))
        .append("</small>")
    if (wrapWidthPx != null) body.append("</div>")
    return body.append("</body></html>").toString()
}

/**
 * W-17 — the size limits for a note's rendered body, in *logical* pixels. Both are
 * [JBUI.scale]d at the point of use, so a HiDPI display or a scaled-up IDE font grows them
 * rather than cropping the text.
 *
 * Two independent caps apply, and the smaller wins ([popupMaxSize]):
 *
 *  - **The readability cap**, [POPUP_MAX_WIDTH]/[POPUP_MAX_HEIGHT] below. This is the one
 *    that binds on any ordinary display.
 *  - **The screen cap**, [POPUP_MAX_SCREEN_FRACTION] of the usable bounds of the screen the
 *    popup is opening on. A safety net for a small display or a large scale factor, where
 *    the scaled readability cap could come out wider than the screen itself.
 */
object WhyPopupSize {
    /**
     * Maximum width. 560 logical pixels is about 80 characters of the default UI font
     * (a 13 px sans face averages close to 7 px per character in prose), which sits at the
     * top of the 45-90 character band that prose stays readable across. Wider than this
     * and the eye loses the start of the next line; Kayden's screenshot was a `why`
     * sentence rendered as one line across the whole editor.
     */
    const val POPUP_MAX_WIDTH: Int = 560

    /**
     * Maximum height. 360 logical pixels is roughly 20 lines at the default UI line height.
     * A note is `what` plus a one-to-three-line `why` plus two short trailer lines, so a
     * typical note wrapped at [POPUP_MAX_WIDTH] comes out near 6 lines; this limit only
     * engages for a note some three times longer than that, which is the point — the scroll
     * bar should be the exception, not the resting state.
     */
    const val POPUP_MAX_HEIGHT: Int = 360

    /**
     * Fraction of the usable screen the popup may occupy on either axis. Kayden offered
     * 75% or 50%; 50% is taken because this cap exists only for the case the readability
     * cap fails to cover, and there the conservative number is the useful one.
     */
    const val POPUP_MAX_SCREEN_FRACTION: Double = 0.5

    /** The readability cap in device pixels. Scaled per call, not at class init. */
    fun absoluteMax(): Dimension = Dimension(JBUI.scale(POPUP_MAX_WIDTH), JBUI.scale(POPUP_MAX_HEIGHT))

    /**
     * The effective cap: the readability cap, or the screen fraction where that is smaller.
     *
     * [absolute] is expected already scaled; [screen] must **not** be scaled. Screen bounds
     * come back from [ScreenUtil] in the same coordinate space a component measures in, so
     * putting them through [JBUI.scale] would multiply a device-pixel number by the device
     * scale and *shrink* the popup on the display where it most needs the room.
     *
     * Pure, so the interesting half of the sizing is assertable without a display.
     */
    fun popupMaxSize(absolute: Dimension, screen: Rectangle): Dimension = Dimension(
        min(absolute.width, (screen.width * POPUP_MAX_SCREEN_FRACTION).toInt()),
        min(absolute.height, (screen.height * POPUP_MAX_SCREEN_FRACTION).toInt()),
    )

    /** [content] clamped to [popupMaxSize]. Never inflates: a short note stays short. */
    fun clampedPopupSize(content: Dimension, absolute: Dimension, screen: Rectangle): Dimension =
        popupMaxSize(absolute, screen).let {
            Dimension(min(content.width, it.width), min(content.height, it.height))
        }
}

/**
 * The click popup's content: the note's HTML in a scrolling pane, sized to the content up
 * to [WhyPopupSize]'s limits and no further.
 *
 * ### Why the measure-then-clamp dance
 *
 * A `text/html` [JEditorPane] has no width to wrap to until it is given one, and an
 * unsized one reports the preferred size of a single unbroken line — which is the bug being
 * fixed here. So the pane is sized to the maximum width first, with an effectively
 * unbounded height, and only then is [JEditorPane.getPreferredSize] read; that read is the
 * *wrapped* height. Clamping that against the maximum is what makes a long note scroll
 * instead of growing.
 *
 * ### Scroll policies
 *
 * Vertical `AS_NEEDED`, horizontal `NEVER`. With the text wrapped there is nothing to
 * scroll horizontally, and a horizontal bar appearing would be the signal that the wrap
 * did not happen.
 *
 * A vertical bar, where the theme draws a space-taking one rather than an overlay, eats a
 * few pixels off the right of the text column. Not compensated for: the cost is the last
 * character or two of a wrapped line sitting under the bar on the long notes that scroll
 * at all, against a second layout pass on every popup.
 *
 * [screen] is a parameter rather than fetched here so that the sizing is exercisable with a
 * synthetic screen; [showNotePopup] supplies the real one.
 */
internal fun notePopupComponent(note: Note, state: Resolution, screen: Rectangle): JBScrollPane {
    val pane = JEditorPane("text/html", notePopupHtml(note, state)).apply {
        isEditable = false
        background = UIUtil.getToolTipBackground()
        border = JBUI.Borders.empty(8)
    }
    val absolute = WhyPopupSize.absoluteMax()
    pane.setSize(WhyPopupSize.popupMaxSize(absolute, screen).width, Short.MAX_VALUE.toInt())
    return JBScrollPane(pane).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getToolTipBackground()
        preferredSize = WhyPopupSize.clampedPopupSize(pane.preferredSize, absolute, screen)
    }
}

/** Informational, not a call to action. See R6.2.1. */
private fun stateLine(state: Resolution): String = when (state) {
    Resolution.SOLID -> "anchored code unchanged"
    Resolution.DRIFTED -> "anchored code changed since this note was written"
    // Not reachable from a gutter icon (section 6.2 renders no icon for an orphan); present
    // so the popup builder is total and usable from the tool window later.
    Resolution.ORPHANED -> "anchored code no longer found in this file"
}

/** Escape, then keep the note author's line breaks visible. */
private fun html(text: String): String =
    StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")

/**
 * One note's gutter mark.
 *
 * Hover is served by [getTooltipText], which the platform renders as a hint popup, and
 * click by [getClickAction], which opens a component popup with the same content — the
 * click route exists because a tooltip disappears and its text cannot be selected.
 *
 * Two notes resolving to the same line produce two highlighters and therefore two
 * renderers, which the gutter lays out side by side on that line; hovering or clicking
 * one shows that note and only that note. No merging, no "2 notes here" aggregate: the
 * alternative — one icon carrying a list — would need the highlighters keyed by line
 * rather than by note, and would then have to pick one icon for a line holding both a
 * solid and a drifted note. Where the gutter is too narrow for both icons the tool
 * window (R7.4) is the complete list.
 *
 * [equals] and [hashCode] are abstract on [GutterIconRenderer] — the gutter uses them to
 * decide whether two marks are the same mark — so they are defined over the identity of
 * what is rendered: the note and its state. Not over [startLine]/[endLine]: the gutter only
 * ever compares marks it is laying out on one line, where the note id already separates
 * them, and two marks for one note in one document cannot be at two ranges.
 *
 * Reveal state is deliberately **not** in [equals], and cannot be: it is not held here at
 * all. [getIcon] asks [revealedNoteId] on every paint, so revealing a note changes what this
 * renderer draws without producing a different renderer. Putting it in equality would say
 * the mark had been replaced, which is a claim about a different note being there — and
 * would need this renderer rebuilt on every reveal, which would mean re-running W-7's whole
 * resolve-and-attach pass to change one icon's alpha.
 *
 * [startLine] and [endLine] are the *resolved* range, carried here so that W-14's reveal
 * has it at click time (see [revealNoteRegion]); they are 1-based and inclusive, as
 * [why.model.Resolved].
 */
class WhyNoteGutterIconRenderer(
    private val project: Project,
    val note: Note,
    val state: Resolution,
    val startLine: Int,
    val endLine: Int,
) : GutterIconRenderer() {

    /**
     * W-15: opacity is reveal state. Dimmed unless this note is the one note whose region is
     * revealed right now. Asked per paint rather than cached, so a reveal elsewhere in the
     * project dims this icon with no bookkeeping here; [revealNoteRegion] asks the gutter to
     * repaint when the answer changes.
     */
    override fun getIcon(): Icon =
        if (revealedNoteId(project) == note.id) WhyIcons.NOTE else WhyIcons.NOTE_DIMMED

    /**
     * W-17: the hover route cannot be wrapped in a scroll pane — the platform owns the
     * component — so the width limit goes into the markup instead, which Swing's HTML
     * renderer honours on a `div`.
     *
     * This is needed rather than assumed. `LineTooltipRenderer.correctLocation` sizes the
     * tooltip from `getPreferredSize()` and clamps it to the editor's layered pane, and the
     * content it clamps is a `ScrollPaneFactory.createScrollPane` — so an unwrapped note
     * does not run off the screen, it runs to the full width of the editor window and grows
     * a horizontal scroll bar. Which is exactly the screenshot W-17 came from. The tool
     * window's row tooltips are plainer still: a Swing `toolTipText` rendered by
     * `BasicToolTipUI`, with no clamp of any kind.
     *
     * No height limit and no scrolling here, for the same reason: the component is the
     * platform's. The editor tooltip already caps its own height against the layered pane.
     */
    override fun getTooltipText(): String =
        notePopupHtml(note, state, JBUI.scale(WhyPopupSize.POPUP_MAX_WIDTH))

    override fun getAlignment(): Alignment = Alignment.RIGHT

    /**
     * The popup, plus W-14's reveal of the region the note claims: the icon says where the
     * region starts and the reveal says how far it runs, for as long as the reader is
     * looking at it. The editor comes from the data context the gutter builds for a click
     * (`EditorGutterComponentImpl` supplies [CommonDataKeys.EDITOR]), which is the same
     * route [WhyCopyReferenceAction] already takes.
     */
    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            val project = e.project
            if (editor != null && project != null) {
                revealNoteRegion(project, editor, note.id, startLine, endLine)
            }
            showNotePopup(note, state, e)
        }
    }

    /**
     * R7.3 — the right-click menu on the mark. The note is known here, so the action is
     * built with it and does not go looking at the caret.
     */
    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(WhyCopyReferenceAction(note))

    override fun equals(other: Any?): Boolean =
        other is WhyNoteGutterIconRenderer && other.note.id == note.id && other.state == state

    override fun hashCode(): Int = 31 * note.id.hashCode() + state.hashCode()
}

/**
 * A plain component popup — not a `Notification`, not a `Balloon`, not a modal (R6.2.1).
 * Untestable headlessly, which is why every byte it displays comes from [notePopupHtml] and
 * every pixel of its size from [notePopupComponent]. What is left here is the two things a
 * headless test cannot reach: the real screen rectangle and the placement.
 */
private fun showNotePopup(note: Note, state: Resolution, event: AnActionEvent) {
    // The screen the click happened on, usable bounds only — dock and menu bar already
    // subtracted. Not `Toolkit.getDefaultToolkit().screenSize`, which answers for the
    // primary display whichever monitor the IDE is on, and counts the space the dock owns.
    val owner = (event.inputEvent as? MouseEvent)?.component
    val screen = if (owner != null) ScreenUtil.getScreenRectangle(owner) else ScreenUtil.getMainScreenBounds()

    val content = notePopupComponent(note, state, screen)
    val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(content, content.viewport.view as JComponent)
        .setRequestFocus(false)
        // The clamp above sets the *initial* size. A reader who wants the whole of a long
        // note without scrolling can still drag the popup larger.
        .setResizable(true)
        .createPopup()

    // Position at the click, not at the caret. `showInBestPositionFor(DataContext)` resolves
    // "best" for an editor context to the caret position, and a gutter click does not move
    // the caret — so clicking the mark on line 10 opened the popup beside line 23, wherever
    // the caret happened to be. Reported from a sandbox run; no headless test can catch it,
    // since popup placement is exactly the part `WhyNotePopupTest` cannot assert.
    val mouse = event.inputEvent as? MouseEvent
    if (mouse != null) popup.show(RelativePoint(mouse)) else popup.showInBestPositionFor(event.dataContext)
}

/** One note and where it currently sits. Lines are 1-based inclusive, as [why.model.Resolved]. */
internal data class NoteMarker(
    val note: Note,
    val state: Resolution,
    val startLine: Int,
    val endLine: Int,
)

/** Gutter icons live below the daemon's own severities and above plain syntax. */
private const val LAYER = HighlighterLayer.ADDITIONAL_SYNTAX

/**
 * Owns the highlighters for one project: attaches on file open, re-attaches on model
 * change, detaches on the last close, and detaches everything on project dispose.
 *
 * ### Threading
 *
 * Resolution is `O(7 x lines)` hash computations per unresolved note (`PLAN.md` section 4
 * step 3), so it never runs on the event dispatch thread. Each refresh is one
 * `ReadAction.nonBlocking`:
 *
 *  - **Snapshot.** The computation reads `document.immutableCharSequence`, which is a
 *    snapshot rather than a live view, inside a read action. So the text cannot change
 *    under the resolver mid-sweep, and the line numbers it returns describe the document
 *    that was read.
 *  - **Staleness.** `ReadAction.nonBlocking` cancels and re-runs its computation when a
 *    write action touches the document, and `finishOnUiThread` drops a result whose read
 *    state no longer holds. That is the platform's own version of the modification-stamp
 *    check this would otherwise hand-roll, so it is not hand-rolled.
 *  - **Apply.** `finishOnUiThread` puts [apply] back on the event dispatch thread, which
 *    is where the markup model is mutated and where [attached] is read and written.
 *    [attached] therefore needs no lock; it is event-dispatch-thread-confined.
 *  - **Pileups.** `coalesceBy(this, document)` drops a queued refresh for a document when
 *    a newer one arrives, so a burst of model changes costs one resolution pass per file.
 *  - **Expiry.** `expireWith(this)` ties every in-flight computation to this service, so
 *    a project closing mid-sweep does not apply to a disposed markup model.
 *
 * Highlighters are applied only through [apply], and [apply] detaches before it attaches,
 * so a document can never accumulate two generations of icons.
 */
@Service(Service.Level.PROJECT)
class WhyGutterService(private val project: Project) : Disposable {

    /**
     * Live highlighters per document. Event-dispatch-thread-confined.
     *
     * Weak keys so that a document released without a `fileClosed` — the platform can
     * drop an unmodified document from its cache — cannot be kept alive by this map. Its
     * highlighters live in that document's own markup model and go with it.
     */
    private val attached = WeakHashMap<Document, List<RangeHighlighter>>()

    /**
     * Compute off the event dispatch thread, apply on it. Replaced by tests with an
     * inline runner, so an assertion follows the call without pumping the event queue —
     * the same seam, and the same reason, as `WhyModelService.schedule`.
     */
    internal var pipeline: (Any, () -> List<NoteMarker>, (List<NoteMarker>) -> Unit) -> Unit =
        { key, compute, apply -> submitNonBlocking(key, compute, apply) }

    /** Hop to the event dispatch thread. Replaced by tests with an inline runner. */
    internal var onEdt: (() -> Unit) -> Unit = { action ->
        ApplicationManager.getApplication()
            .invokeLater({ if (!project.isDisposed) action() }, ModalityState.any())
    }

    init {
        // W-5's topic. A model change can mean a note appeared, moved between files or
        // vanished, none of which is visible per file, so every open file is refreshed.
        project.messageBus.connect(this)
            .subscribe(WHY_MODEL_CHANGED, WhyModelListener { _, _ -> refreshAllOpenFiles() })
    }

    private fun submitNonBlocking(
        key: Any,
        compute: () -> List<NoteMarker>,
        apply: (List<NoteMarker>) -> Unit,
    ) {
        ReadAction.nonBlocking(Callable { compute() })
            .expireWith(this)
            .coalesceBy(this, key)
            .finishOnUiThread(ModalityState.any()) { apply(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** [WhyEditorGutterListener.fileOpened]. */
    fun fileOpened(file: VirtualFile) = refresh(file)

    /**
     * [WhyEditorGutterListener.fileClosed].
     *
     * A split or a second window closing leaves the file open elsewhere, and the
     * document-level markup model is shared by all of them, so the highlighters stay
     * until the last editor on the file is gone. `fileClosed` is published after the
     * editor has been removed from the manager, so `isFileOpen` answers "still open
     * somewhere *else*".
     */
    fun fileClosed(file: VirtualFile) {
        if (FileEditorManager.getInstance(project).isFileOpen(file)) return
        FileDocumentManager.getInstance().getCachedDocument(file)?.let(::detach)
    }

    /** Every file with an open editor, re-resolved. Safe to call from any thread. */
    fun refreshAllOpenFiles() = onEdt {
        FileEditorManager.getInstance(project).openFiles.forEach(::refresh)
    }

    private fun refresh(file: VirtualFile) {
        // Null for a binary file, which cannot carry notes. The editor is already open, so
        // this is a cache hit rather than a decode.
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        pipeline(document, { markersFor(file, document) }, { markers -> apply(document, markers) })
    }

    /**
     * Off the event dispatch thread, inside a read action. Everything expensive is here:
     * `.why/` discovery stats the disk, the first [WhyModelService.model] call for a root
     * folds the corpus, and [Resolver.resolve] sweeps the document per note.
     *
     * Section 6.2: orphans are dropped here, so no highlighter of any kind reaches the
     * gutter for them. They stay in the model for the tool window (R6.2.2).
     */
    private fun markersFor(file: VirtualFile, document: Document): List<NoteMarker> {
        val path = runCatching { Path.of(file.path) }.getOrNull() ?: return emptyList()
        val root = findWhyRoot(path) ?: return emptyList()
        val key = projectRelativePath(root, path) ?: return emptyList()
        val notes = project.service<WhyModelService>().model(root).notesByFile[key] ?: return emptyList()
        val text = document.immutableCharSequence.toString()
        return notes.mapNotNull { note ->
            val resolved = Resolver.resolve(note, text)
            val start = resolved.start
            val end = resolved.end
            if (resolved.state == Resolution.ORPHANED || start == null || end == null) null
            else NoteMarker(note, resolved.state, start, end)
        }
    }

    /**
     * On the event dispatch thread. Replaces this document's icons wholesale.
     *
     * Ranges come from [NoteMarker], which carries [why.model.Resolved]'s lines, not
     * [why.model.Anchor]'s: a note whose code moved is drawn where the code is now.
     * Lines are clamped because the document may have grown or shrunk between the
     * snapshot and this call.
     */
    private fun apply(document: Document, markers: List<NoteMarker>) {
        detach(document)
        if (markers.isEmpty()) return
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        attached[document] = markers.map { marker ->
            val startLine = (marker.startLine - 1).coerceIn(0, lastLine)
            val endLine = (marker.endLine - 1).coerceIn(startLine, lastLine)
            markup.addRangeHighlighter(
                null as TextAttributesKey?,
                document.getLineStartOffset(startLine),
                document.getLineEndOffset(endLine),
                LAYER,
                HighlighterTargetArea.LINES_IN_RANGE,
            ).also {
                it.gutterIconRenderer = WhyNoteGutterIconRenderer(
                    project, marker.note, marker.state, marker.startLine, marker.endLine,
                )
            }
        }
    }

    /** Removes only what this service added. Idempotent. */
    private fun detach(document: Document) {
        val existing = attached.remove(document) ?: return
        // Once the project is disposing, `DocumentMarkupModel.forDocument` hands back an
        // immutable empty model whose `removeHighlighter` throws `ProcessCanceledException`
        // — and a cancellation thrown out of a `dispose()` is itself an error the platform
        // logs. There is nothing to remove at that point anyway: see [dispose].
        if (project.isDisposed) return
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        existing.forEach { if (it.isValid) markup.removeHighlighter(it) }
    }

    /**
     * Drops this service's references only. The highlighters live in per-project document
     * markup models, which the platform tears down in the same disposal pass
     * (`DocumentMarkupModel.removeMarkupModel`), so removing them one at a time here would
     * be both redundant and too late.
     */
    override fun dispose() {
        attached.clear()
    }
}

/**
 * Thin adapter. Holds no state; everything worth testing is in [WhyGutterService].
 *
 * Not registered for files already open when the project opens — `fileOpened` does fire
 * during editor restoration, and W-5's initial load publishes [WHY_MODEL_CHANGED], so
 * either event attaches the icons. Whichever arrives second finds the same markers and
 * re-applies them, which is why [WhyGutterService.apply] detaches first.
 */
internal class WhyEditorGutterListener(private val project: Project) : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        project.service<WhyGutterService>().fileOpened(file)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        project.service<WhyGutterService>().fileClosed(file)
    }
}
