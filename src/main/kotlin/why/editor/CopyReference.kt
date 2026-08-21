package why.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import why.model.Note

/**
 * R7.3 — Copy Reference. The note id onto the system clipboard, which is the whole
 * conversation loop for v1: copy the id, paste it into an agent chat, ask there.
 *
 * ### One action, two entry points
 *
 * The gutter mark knows which note it draws, so it constructs this action with that note
 * ([WhyNoteGutterIconRenderer.getPopupMenuActions]). The keyboard shortcut is registered in
 * `plugin.xml` and therefore instantiated by the platform through the no-argument
 * constructor, with no note in hand, so it resolves one from the caret ([noteAtCaret]).
 * Two constructors rather than two actions, because the copy itself is one line and
 * duplicating it would mean two places to change the format.
 *
 * ### `Filename.cs#W-4KQ2` is not implemented
 *
 * `TASKS.md` gates the second format on it costing a single line. It does not: a format
 * choice needs a `PersistentStateComponent`, a `Configurable` and a settings form, none of
 * which is one line, so v1 ships the bare id and the qualified form is omitted.
 */
class WhyCopyReferenceAction(private val note: Note? = null) : AnAction("Copy Why Note Reference") {

    /**
     * Reads the caret and the document markup model, both of which are
     * event-dispatch-thread state.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * Enabled whenever there is something to try: a note (gutter menu) or an editor whose
     * caret can be inspected (shortcut). Deliberately *not* disabled when the caret sits
     * outside every note — a disabled action swallows the keystroke silently, and
     * [actionPerformed] has a better answer than silence (see below).
     */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = note != null || e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = note ?: caretNote(e)
        if (target == null) {
            // No note at the caret. The clipboard is left exactly as it was — clobbering it
            // with nothing, or with an error string, destroys whatever the user had copied.
            // The status bar is the quietest acknowledgement the platform has: no modal, no
            // balloon, no notification (R6.2.1 sets that tone for the whole plugin).
            e.project?.let { WindowManager.getInstance().getStatusBar(it)?.info = NO_NOTE_AT_CARET }
            return
        }
        CopyPasteManager.copyTextToClipboard(target.id)
    }

    private fun caretNote(e: AnActionEvent): Note? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val project = e.project ?: return null
        return noteAtCaret(editor, project)
    }

    companion object {
        /** Also the string the no-note test asserts the clipboard did *not* receive. */
        const val NO_NOTE_AT_CARET: String = "No why note at the caret"
    }
}

/**
 * The note whose *resolved* range contains the caret, or null.
 *
 * Reads the gutter highlighters [WhyGutterService] already attached rather than resolving
 * again. Three consequences, all of them the wanted behaviour:
 *
 *  - the range tested is the resolved one, so a note whose code moved is found where the
 *    code is now, not at its stored line numbers;
 *  - a drifted note is found, because W-7 gives it a highlighter (de-emphasised, but
 *    present) — a drifted note is still a note worth asking about;
 *  - an orphan is never found, because it has no highlighter and, having no range in this
 *    file, no caret position that could mean it.
 *
 * The highlighters cover whole lines ([com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE]),
 * so a caret anywhere on an annotated line counts.
 *
 * ### Overlapping notes
 *
 * Two notes can resolve to the same lines (W-7 draws two marks). The clipboard holds one
 * string, so one note must be picked: the **narrowest** range containing the caret, tie-broken
 * by note id. Narrowest, because the more tightly a note's range fits the caret the more
 * specifically it is about the code under it; a stable tie-break, because the same caret
 * pressing the same key twice must copy the same id. The alternative — a chooser popup —
 * turns a one-keystroke copy into a two-step interaction for a case the tool window (R7.4)
 * already lists in full.
 */
internal fun noteAtCaret(editor: Editor, project: Project): Note? {
    val markup = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
    val offset = editor.caretModel.offset
    return markup.allHighlighters
        .filter { it.isValid && it.gutterIconRenderer is WhyNoteGutterIconRenderer && offset in it.startOffset..it.endOffset }
        .minWithOrNull(compareBy({ it.endOffset - it.startOffset }, { it.whyNote().id }))
        ?.whyNote()
}

private fun RangeHighlighter.whyNote(): Note = (gutterIconRenderer as WhyNoteGutterIconRenderer).note
