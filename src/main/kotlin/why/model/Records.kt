package why.model

/**
 * On-disk record shapes, transcribed from the requirements document §5.3.
 *
 * This file is the shared contract between the parser (W-3), the store (W-4),
 * the resolver (W-6) and the user interface (W-7, W-10). It deliberately holds
 * no logic beyond two flag accessors, so that the string literals defined by
 * §5.4 exist in exactly one place.
 *
 * Timestamps stay as the strings they were written as. They are ISO 8601 in
 * UTC (§5.3), which sorts correctly lexicographically, and nothing in v1 does
 * date arithmetic on them.
 */

/**
 * Where a note applies. See `skills/why-notes/writer/HASHING.md` for how [hash] is computed and
 * `PLAN.md` §4 for how the three fields are used to re-anchor.
 */
data class Anchor(
    /**
     * Fully qualified where the language permits, absent otherwise. A plain
     * text-search hint that narrows the hash scan — never resolved as a symbol,
     * because the plugin does not use the Program Structure Interface.
     */
    val symbol: String?,
    /** 1-based, inclusive, as written. */
    val start: Int,
    /** 1-based, inclusive, as written. */
    val end: Int,
    /** Six lowercase hexadecimal characters. The authority on whether code changed. */
    val hash: String,
)

/**
 * One agent request. The header record of a task file, or a synthetic stand-in
 * built from the filename when the header is missing (R5.4.3).
 */
data class Task(
    /** Matches the filename stem. */
    val id: String,
    /** Task start. Null when synthesised from a filename. */
    val ts: String?,
    /** Short SHA at task start. Null when synthesised from a filename. */
    val base: String?,
    /** The originating request, shown as the group header in the tool window (R7.4). */
    val prompt: String?,
)

/** A single unit of rationale: what changed in one region, and why. */
data class Note(
    /** `W-` plus four characters. Unique per project. The copy-paste handle (R7.3). */
    val id: String,
    /** The task whose file this note was read from. Derived, not a stored field. */
    val taskId: String,
    val ts: String,
    /** Project-relative, forward slashes. */
    val file: String,
    /** Per note, not per task — a long task can span a commit (§5.3). */
    val base: String,
    val anchor: Anchor,
    /** What the code now does. */
    val what: String,
    /** Why it was changed. */
    val why: String,
    /** Freeform. Two values carry defined meaning; see the accessors below. */
    val flags: List<String>,
)

/** §5.4: the agent flagging this note as uncertain. Filterable in the tool window (R7.4). */
const val FLAG_NEEDS_REVIEW: String = "needs-review"

/** §5.4: a value was introduced that is expected to be adjusted. Carries a `:<Name>` suffix. */
const val FLAG_TUNABLE_PREFIX: String = "tunable:"

val Note.needsReview: Boolean
    get() = FLAG_NEEDS_REVIEW in flags

/** The `<Name>` parts of any `tunable:<Name>` flags on this note. */
val Note.tunables: List<String>
    get() = flags.filter { it.startsWith(FLAG_TUNABLE_PREFIX) }
        .map { it.removePrefix(FLAG_TUNABLE_PREFIX) }
        .filter { it.isNotEmpty() }

/** §6.1 outcome for one note against the current state of its file. */
enum class Resolution {
    /** Anchored code located and unchanged. */
    SOLID,

    /** Anchored code located but changed since the note was written. */
    DRIFTED,

    /** Anchored code can no longer be located at all. */
    ORPHANED,
}

/**
 * A note plus where it currently lives. [start] and [end] are 1-based inclusive
 * and may differ from [Note.anchor] when the region moved — the tool window
 * navigates to these, not to the stored line numbers (R7.4).
 *
 * Both are null exactly when [state] is [Resolution.ORPHANED].
 */
data class Resolved(
    val note: Note,
    val state: Resolution,
    val start: Int?,
    val end: Int?,
)
