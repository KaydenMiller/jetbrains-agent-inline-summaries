package why.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * One `.why/tasks/T-NN.jsonl` file, folded into the model (§5.2, §5.3).
 *
 * [warnings] are returned rather than logged so the parser stays free of
 * platform services and testable without a fixture. The caller decides what to
 * do with them (R5.4.2).
 */
data class ParsedTaskFile(
    val task: Task,
    val notes: List<Note>,
    val warnings: List<String>,
)

/**
 * Parse one task file. [taskId] comes from the filename stem and is authoritative
 * (R5.4.3); [text] is the whole file, taken as a String so the caller can hand
 * over an in-memory platform Document without touching disk.
 *
 * Tolerant by contract: unknown fields are ignored (R5.4.1), a line that fails to
 * parse is skipped with a warning while every other line still loads (R5.4.2), and
 * a missing header record yields a [Task] built from [taskId] alone (R5.4.3).
 */
fun parseTaskFile(taskId: String, text: String): ParsedTaskFile {
    val warnings = mutableListOf<String>()
    val notes = mutableListOf<Note>()
    var header: Task? = null

    val lines = text.lineSequence().toList()

    // §5.2: the writer appends and never rewrites, so a final line with no line
    // terminator is a record caught half-written — the normal state of a file
    // while an agent is working, not a fault. It is skipped silently. An
    // unparseable line anywhere else is a real fault and warns (R5.4.2).
    val fragment =
        if (text.isEmpty() || text.last() == '\n' || text.last() == '\r') -1 else lines.lastIndex

    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEachIndexed // blank lines are not a fault
        val lineNumber = index + 1

        val record = readObject(line)
        if (record == null) {
            if (index != fragment) warnings += "line $lineNumber: unparseable, skipped"
            return@forEachIndexed
        }

        when (val kind = record.str("kind")) {
            "task" ->
                if (header != null) {
                    warnings += "line $lineNumber: second header record, ignored"
                } else {
                    header = readTask(taskId, record, lineNumber, warnings)
                }

            "note" -> {
                val missing = mutableListOf<String>()
                val note = readNote(taskId, record, missing)
                if (note == null) {
                    warnings += "line $lineNumber: note record missing required " +
                        "field(s) ${missing.joinToString(", ")}, skipped"
                } else {
                    notes += note
                }
            }

            // Forward compatibility: a record kind v1 does not know about is not an error.
            else -> warnings += "line $lineNumber: unrecognised kind " +
                (kind?.let { "'$it'" } ?: "(absent)") + ", skipped"
        }
    }

    return ParsedTaskFile(
        task = header ?: Task(id = taskId, ts = null, base = null, prompt = null),
        notes = notes,
        warnings = warnings,
    )
}

/** Null for anything that is not a JSON object, malformed or otherwise. */
private fun readObject(line: String): JsonObject? =
    try {
        JsonParser.parseString(line) as? JsonObject
    } catch (_: RuntimeException) {
        null
    }

/**
 * Header fields are all optional here even though §5.3 marks `ts` and `base`
 * required, because R5.4.3 already requires tolerating the whole record's absence
 * — a half-written header is no worse than none. [Task] models them as nullable.
 */
private fun readTask(
    taskId: String,
    record: JsonObject,
    lineNumber: Int,
    warnings: MutableList<String>,
): Task {
    val declared = record.str("id")
    if (declared != null && declared != taskId) {
        warnings += "line $lineNumber: header id '$declared' does not match filename " +
            "'$taskId'; the filename wins (R5.4.3)"
    }
    return Task(
        id = taskId,
        ts = record.str("ts"),
        base = record.str("base"),
        prompt = record.str("prompt"),
    )
}

/** The §5.3 note fields marked required, all of them strings. */
private val REQUIRED_NOTE_STRINGS = listOf("id", "ts", "file", "base", "what", "why")

/**
 * Null when any required field (§5.3) is absent or of the wrong type, with the
 * names collected into [missing]. Never returns a half-populated [Note].
 */
private fun readNote(taskId: String, record: JsonObject, missing: MutableList<String>): Note? {
    missing += REQUIRED_NOTE_STRINGS.filter { record.str(it) == null }

    val anchor = record["anchor"] as? JsonObject
    if (anchor == null) {
        missing += "anchor"
    } else {
        if (anchor.int("start") == null) missing += "anchor.start"
        if (anchor.int("end") == null) missing += "anchor.end"
        if (anchor.str("hash") == null) missing += "anchor.hash"
    }
    if (missing.isNotEmpty()) return null

    // Every read below was proved present and correctly typed above.
    return Note(
        id = record.str("id")!!,
        taskId = taskId,
        ts = record.str("ts")!!,
        file = record.str("file")!!,
        base = record.str("base")!!,
        anchor = Anchor(
            symbol = anchor!!.str("symbol"), // optional (§5.3)
            start = anchor.int("start")!!,
            end = anchor.int("end")!!,
            hash = anchor.str("hash")!!,
        ),
        what = record.str("what")!!,
        why = record.str("why")!!,
        flags = record.flags(),
    )
}

/** Absent `flags` is an empty list, not null. Order is preserved. */
private fun JsonObject.flags(): List<String> =
    (this["flags"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.asString

private fun JsonObject.str(name: String): String? = this[name]?.stringOrNull()

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
