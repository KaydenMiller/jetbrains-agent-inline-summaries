package why.store

import why.model.Note
import why.model.ParsedTaskFile
import why.model.Task
import why.model.parseTaskFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Folding the `.jsonl` files under `.why/tasks/` into the two views the plugin reads
 * (R5.1.5): tasks with their notes, and `file key -> notes` for the editor.
 *
 * `index.json` is never read. R5.1.4 permits reading it as a cache and requires
 * rebuilding from `tasks/` whenever it is missing, stale or corrupt; folding the
 * whole corpus measures 8-11 ms for 50 task files of 20 notes each — a worst case
 * an order of magnitude past anything expected, measured by
 * `TaskStoreTest.worstCaseCorpusFoldsFastEnoughToSkipIndexJson` — so the cache
 * would save nothing and could be wrong. Nothing here writes, per §2.
 *
 * No IntelliJ platform types: `java.nio.file` and strings only.
 */

/**
 * `(taskId, fileText) -> ParsedTaskFile`. Injected rather than called directly so
 * the fold is testable without W-3's parser; [why.model.parseTaskFile] is the
 * production value and the default.
 */
typealias TaskFileParser = (String, String) -> ParsedTaskFile

/** One task file's worth of model. */
data class TaskWithNotes(val task: Task, val notes: List<Note>)

/**
 * The whole corpus under one `.why/`.
 *
 * Notes whose [Note.file] does not exist on disk are **present** here. Dropping
 * them is W-9's separate pass, which needs to run over a complete model to
 * report a count.
 */
data class WhyModel(
    /** Ordered by task file name. */
    val tasks: List<TaskWithNotes>,
    /** Key is the project-relative forward-slash path of §5.3. */
    val notesByFile: Map<String, List<Note>>,
    /** Parser warnings plus unreadable-file warnings, for a later task to surface. */
    val warnings: List<String>,
) {
    companion object {
        val EMPTY = WhyModel(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * Holds the parsed task files for one `.why/` root so that a single changed
 * file can be re-parsed without re-reading the corpus (W-5).
 *
 * Not thread-safe. Callers on the platform side confine it to one thread.
 */
class TaskStore(private val root: Path, private val parse: TaskFileParser = ::parseTaskFile) {

    /** taskId -> parsed file, sorted by task id so the model order is stable. */
    private val parsed = sortedMapOf<String, ParsedTaskFile>()

    /** Re-reads every `*.jsonl` under `.why/tasks/`, replacing what is held. */
    fun loadAll(): WhyModel {
        parsed.clear()
        val dir = tasksDir(root)
        val warnings = mutableListOf<String>()
        val files = try {
            Files.newDirectoryStream(dir).use { stream ->
                // Skips subdirectories and any file that is not *.jsonl, so a stray
                // README or an editor's backup directory is ignored, not an error.
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
            }
        } catch (e: IOException) {
            // Missing `.why/tasks/` is the ordinary "no notes yet" case, not a warning.
            if (Files.exists(dir)) warnings += "${dir.fileName}: cannot list: ${e.message}"
            return WhyModel(emptyList(), emptyMap(), warnings)
        }
        files.forEach { warnings += readInto(it) }
        return model(warnings)
    }

    /**
     * Re-parses one task file, or forgets the task if the file is gone. Returns
     * the whole folded model, since both views change.
     */
    fun reloadTask(file: Path): WhyModel {
        val extra = if (Files.isRegularFile(file)) readInto(file) else {
            parsed.remove(taskIdOf(file)); emptyList()
        }
        return model(extra)
    }

    /** Parses [file] into [parsed]; returns warnings raised while doing so. */
    private fun readInto(file: Path): List<String> {
        val text = try {
            // Not Files.readString: that throws on malformed UTF-8 where the decoder
            // here substitutes, and a tolerant reader should still see the good lines.
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return listOf("${file.fileName}: cannot read: ${e.message}")
        }
        // Keyed by filename stem, not by the header's id: §5.3 says they match, and
        // the stem is what a later reload of the same file can be looked up by.
        val taskId = taskIdOf(file)
        parsed[taskId] = parse(taskId, text)
        return emptyList()
    }

    private fun taskIdOf(file: Path) = file.fileName.toString().removeSuffix(".jsonl")

    private fun model(warnings: List<String>): WhyModel {
        val notes = parsed.values.flatMap { it.notes }
        return WhyModel(
            tasks = parsed.values.map { TaskWithNotes(it.task, it.notes) },
            notesByFile = notes.groupBy { it.file },
            // R5.4.2 asks for file *and* line; the parser knows only the line, so the
            // file name is prefixed here, where it is known.
            warnings = parsed.entries.flatMap { (id, p) -> p.warnings.map { "$id.jsonl: $it" } } + warnings,
        )
    }
}
