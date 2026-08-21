package why.editor

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.highlighting.HighlightManagerImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * W-14 — a note's region, shown while the reader has that note selected.
 *
 * A note anchored to lines 10..25 draws one gutter icon, at line 10 (W-7). Nothing in the
 * gutter states the extent, so the reader cannot tell which code the rationale is about.
 *
 * ### Why this is a reveal and not a band
 *
 * Kayden's ruling (2026-08-20, TASKS.md W-14) after seeing the permanent version in
 * Rider: the extent is not drawn permanently. The icon stays at the top of the region,
 * and for uncommitted work Rider's own VCS change bars already mark the changed lines, so
 * a second always-on gutter band beside them is the same information twice. The case the
 * change bars do not cover is an intermediate commit mid-task: the bars are gone and the
 * region becomes invisible. That case is covered here, on selection.
 *
 * Selection means one of the two routes that already existed:
 *  - a note row clicked in the tool window ([why.ui.WhyToolWindowPanel.navigate]);
 *  - the gutter icon clicked ([WhyNoteGutterIconRenderer.getClickAction]).
 *
 * ### What dismisses it
 *
 * W-15, Kayden's second ruling (2026-08-21) from the sandbox: clicking the icon again, and
 * nothing else. The first version dismissed on the caret leaving the region, on any
 * keystroke and on any edit; all three fired while the reader was still reading, which read
 * as the reveal falling over rather than as a control. So the caret subscription is gone and
 * both hide flags are off.
 *
 * Escape still dismisses: [HighlightManagerImpl] adds `HIDE_BY_ESCAPE` itself for every
 * highlight registered through the [HighlightManager.addRangeHighlight] overload used here,
 * and that flag is not exposed as a parameter. It is left in place deliberately — Escape
 * dismissing a transient editor decoration is a platform convention, and a reader who
 * presses it expects exactly this. So "only the icon dismisses" is true of everything this
 * file controls, and Escape is the one dismissal it does not.
 *
 * ### Why [HighlightManager] rather than a highlighter of our own
 *
 * This is the mechanism "Show Usages" and Highlight Usages use: the platform owns the
 * highlighter and its removal, so there is nothing here to reconcile with
 * [WhyGutterService]'s icon highlighters and nothing to leak if the editor closes first —
 * the reveal lives in the *editor's* markup model, W-7's icons in the *document's*.
 *
 * A hand-rolled [RangeHighlighter] would mean a second separately-managed set beside
 * [WhyGutterService]'s, which would then have to agree with it on ordering, teardown and
 * project disposal.
 *
 * ### R6.2.1
 *
 * A transient highlight and nothing else. No modal, no balloon, no notification, no
 * problem-view entry.
 */

/**
 * The wash drawn over the revealed lines.
 *
 * [EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES] — a colour-scheme key, not literal
 * colours, so every bundled theme (and any the user installs) supplies its own light and
 * dark values and the reveal cannot be the wrong colour for a scheme it has never seen.
 *
 * Chosen over the other candidate, [EditorColors.SEARCH_RESULT_ATTRIBUTES], on meaning:
 *
 *  - identifier-under-caret means "this is the thing you are looking at", which is what a
 *    reveal says. Search-result colouring means "one hit of several", and the platform
 *    binds F3 and Shift+F3 to stepping through those hits — a reader who sees search
 *    colouring reasonably expects a next-hit key that does nothing here.
 *  - it is a flat background with no effect type: no wave, no underline, no red, so it
 *    cannot be read as an error or a warning (R6.2.1).
 */
internal val REVEAL_ATTRIBUTES: TextAttributesKey = EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES

/**
 * The one reveal this mechanism has outstanding: which note it belongs to, where it is
 * drawn, and the highlighter drawing it.
 *
 * At most one, project-wide, which is what makes [revealedNoteId] answerable and therefore
 * what lets the gutter icon show its own state (W-15). Held as project user data rather
 * than in a service: one nullable field, and it goes when the project goes.
 *
 * [highlighter] is kept so that "is this note revealed?" is answered by asking the platform
 * whether the highlight still exists, not by trusting a flag this file set. Escape removes
 * the highlighter without telling us, so a flag would leave the icon lit over an empty
 * editor and the next click would toggle nothing.
 */
private class Reveal(val noteId: String, val editor: Editor, val highlighter: RangeHighlighter)

private val REVEALED: Key<Reveal> = Key.create("why.reveal")

/**
 * The note whose region is currently revealed, or null. Read by
 * [WhyNoteGutterIconRenderer.getIcon] to pick the dimmed or the full-opacity icon.
 */
internal fun revealedNoteId(project: Project): String? =
    project.getUserData(REVEALED)?.takeIf { !it.editor.isDisposed && it.highlighter.isValid }?.noteId

/**
 * Reveals [noteId]'s [startLine]..[endLine] (1-based, inclusive) in [editor], clearing
 * whatever this mechanism last revealed anywhere in the project.
 *
 * Both lines null means an orphan: the resolver found no range, so there is nothing to
 * reveal and nothing is drawn. The previous reveal is still cleared first, so selecting an
 * orphan does not leave the last note's region lit as if it were the orphan's.
 *
 * Revealing the note that is already revealed **dismisses** it, so the same click that
 * shows a region also puts it away. Keyed on the note, not on the range: two notes can
 * resolve to one range, and clicking the second of them means "show me that one", not
 * "put the first one away".
 *
 * Lines are the resolver's ([why.model.Resolved]), never [why.model.Anchor]'s, and are
 * clamped the same way [WhyGutterService] clamps its icons: the document may have changed
 * between the resolution pass and this call.
 *
 * Call on the event dispatch thread.
 */
fun revealNoteRegion(project: Project, editor: Editor, noteId: String, startLine: Int?, endLine: Int?) {
    val wasShowing = revealedNoteId(project)
    clearReveal(project)

    val document = editor.document
    val lastLine = (document.lineCount - 1).coerceAtLeast(0)
    if (startLine == null || endLine == null || noteId == wasShowing) {
        repaintGutters(project)
        return
    }
    val first = (startLine - 1).coerceIn(0, lastLine)
    val last = (endLine - 1).coerceIn(first, lastLine)

    val added = mutableListOf<RangeHighlighter>()
    HighlightManager.getInstance(project).addRangeHighlight(
        editor,
        document.getLineStartOffset(first),
        document.getLineEndOffset(last),
        REVEAL_ATTRIBUTES,
        // hideByTextChange, hideByAnyKey: both false. Editing or typing is not a request to
        // put the region away, and treating it as one is the W-15 report. Escape still
        // dismisses; see the file header for why that one is kept.
        false,
        false,
        added,
    )
    added.singleOrNull()?.let { project.putUserData(REVEALED, Reveal(noteId, editor, it)) }
    repaintGutters(project)
}

/**
 * Drops this mechanism's reveal, wherever it is.
 *
 * Removes exactly the highlighter [revealNoteRegion] registered, through the platform's own
 * removal call — [HighlightManagerImpl.removeSegmentHighlighter], which is what
 * `HighlightUsagesHandler.clearHighlights` uses — so nothing here manages a highlighter
 * lifecycle of its own, and a Highlight Usages mark the reader put in the same editor is
 * left alone. W-7's gutter icons live in the document markup model, are not registered with
 * [HighlightManager] at all, and are unreachable from here.
 */
private fun clearReveal(project: Project) {
    val reveal = project.getUserData(REVEALED) ?: return
    project.putUserData(REVEALED, null)
    if (reveal.editor.isDisposed) return
    (HighlightManager.getInstance(project) as? HighlightManagerImpl)
        ?.removeSegmentHighlighter(reveal.editor, reveal.highlighter)
}

/**
 * W-15: the gutter draws a note's icon dimmed or opaque according to [revealedNoteId], and
 * nothing tells it that the answer changed. `revalidateMarkup` is the documented way to ask
 * for a re-read; without it the icon keeps its old opacity until some unrelated event
 * repaints the gutter.
 *
 * Every editor in the project, not just [Editor] the reveal was drawn in: the note losing
 * its reveal can be in another file, and a file open in a split draws its icons once per
 * editor. The list is the editors the user has open, so this is a handful of repaints on a
 * click.
 */
private fun repaintGutters(project: Project) {
    EditorFactory.getInstance().allEditors
        .filter { it.project == project && !it.isDisposed }
        .forEach { (it as? EditorEx)?.gutterComponentEx?.revalidateMarkup() }
}
