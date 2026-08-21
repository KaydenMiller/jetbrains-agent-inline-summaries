package why.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * R7.6 — at project open, drop notes whose `file` no longer exists on disk.
 *
 * A filter over [WhyModel], nothing more. **The plugin never writes** (§2, and
 * R5.1.4 makes `.why/` the writer's territory), so this drops from the in-memory
 * model and touches no file: it stats paths and returns a new [WhyModel].
 * `WhyGcTest.taskFileOnDiskIsByteIdenticalAfterThePass` holds that line.
 *
 * Deliberately startup-only, matching R7.6's wording. A file deleted while the
 * IDE is open is not handled here and must not be: R6.2.2 requires orphaned notes
 * to stay visible, and §6.2 already renders them with no icon. The consequence of
 * that scoping is that a task file reloaded mid-session by W-5's watcher re-folds
 * from the parsed corpus and so brings its dropped notes back — correct under
 * R6.2.2, and the reason nothing here has to be remembered.
 *
 * No IntelliJ platform types, so the pass is testable without a fixture. The log
 * line R7.6 asks for is emitted by the one caller,
 * [WhyModelService.initialLoad], which already holds a logger.
 */

/** What [gcMissingFiles] produced, and how much it removed. */
data class GcResult(
    val model: WhyModel,
    /** Notes removed. Zero means [model] is the input, unchanged and un-copied. */
    val droppedNotes: Int,
    /** The `file` keys those notes pointed at. Ordered as the input model was. */
    val missingFiles: List<String>,
)

/**
 * [model] with every note whose [why.model.Note.file] does not resolve to a
 * regular file under [root] removed from both of its views.
 *
 * "Does not exist" is decided per distinct `file` key, one stat each rather than
 * one per note, by [fileKeyResolves]. Three cases drop:
 *
 *  - nothing at that path;
 *  - a path that resolves outside [root], whether by `..` segments or by being
 *    absolute: it cannot denote a project file however much exists at the other
 *    end of it. An absolute key that happens to land *inside* the root is not a
 *    legal §5.3 key either, but it names a file that exists, and R7.6 removes
 *    notes for files that do not exist — so that note is kept
 *    (`WhyGcTest.anAbsoluteFileKeyInsideTheRootIsKeptAndOneOutsideItDrops`);
 *  - a path that resolves to a directory. A note anchors line ranges in a text
 *    file; a directory cannot satisfy the anchor, and W-6 could not resolve one.
 *
 * A task whose every note dropped is removed with them, rather than kept as an
 * empty group: R7.4's tool window groups notes under the task `prompt`, and a
 * group with nothing under it is a header the user cannot expand or act on. Tasks
 * that lost no notes are passed through untouched, so a header-only task file
 * with no notes to begin with is left alone — this pass removes notes and their
 * now-empty groups, and never a task it did not touch.
 */
fun gcMissingFiles(root: Path, model: WhyModel): GcResult {
    val missing = model.notesByFile.keys.filterNot { fileKeyResolves(root, it) }
    if (missing.isEmpty()) return GcResult(model, 0, emptyList())
    val dead = missing.toSet()
    val tasks = model.tasks.mapNotNull { withNotes ->
        val kept = withNotes.notes.filterNot { it.file in dead }
        when {
            kept.size == withNotes.notes.size -> withNotes
            kept.isEmpty() -> null
            else -> withNotes.copy(notes = kept)
        }
    }
    return GcResult(
        model = model.copy(tasks = tasks, notesByFile = model.notesByFile - dead),
        droppedNotes = missing.sumOf { model.notesByFile[it]?.size ?: 0 },
        missingFiles = missing,
    )
}

/** Whether the §5.3 `file` key [key] denotes an existing regular file inside [root]. */
private fun fileKeyResolves(root: Path, key: String): Boolean {
    // resolve throws InvalidPathException on a key the platform cannot express
    // (a NUL byte, a Windows-illegal character); an unusable key is a missing file.
    val path = runCatching { root.resolve(key) }.getOrNull() ?: return false
    // Reuses W-4's containment rule instead of restating it: a key that does not
    // round-trip back to a relative path is outside the root.
    return projectRelativePath(root, path) != null && Files.isRegularFile(path)
}
