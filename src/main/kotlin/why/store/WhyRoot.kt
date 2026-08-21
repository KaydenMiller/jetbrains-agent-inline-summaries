package why.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locating `.why/` (requirements §8) and converting absolute paths into the
 * project-relative `file` key of §5.3.
 *
 * No IntelliJ platform types. Callers holding a `VirtualFile` pass
 * `Path.of(virtualFile.path)`; nothing here needs an IDE fixture to test.
 *
 * Git is not consulted. R6.3.2 gives git a single v1 role — the origin of the
 * `base` field, written by the writer — and this file honours that by never
 * reading `.git`, never shelling out, and never linking a git library. `.git`
 * is not used as a walk boundary either: the walk stops at the first ancestor
 * that has a `.why/` directory, or at the filesystem root, which is a
 * sufficient stop condition on its own. That also makes R8.2 (a `.git` *file*
 * in a linked worktree) and an absent `.git` non-cases rather than branches —
 * there is no code path that inspects `.git` at all.
 */

/** The directory name that marks a project root for this plugin. */
const val WHY_DIR_NAME: String = ".why"

/** Subdirectory of [WHY_DIR_NAME] holding one `<taskId>.jsonl` per task (§5.1). */
const val TASKS_DIR_NAME: String = "tasks"

/**
 * R8.1: the nearest ancestor of [from] that contains a `.why/` directory, or
 * null when there is none up to the filesystem root.
 *
 * Returned is the *parent* of `.why/` — the project root — because that is the
 * directory note paths are relative to (§5.3). Search starts at [from] itself
 * when [from] is a directory, and at its parent otherwise, so a file directly
 * inside a project root resolves to that root.
 *
 * Deliberately per-path rather than per-project: two worktrees opened as
 * separate content roots in one window each resolve to their own `.why/`, and
 * a nested root shadows its ancestor.
 */
fun findWhyRoot(from: Path): Path? {
    val start = from.toAbsolutePath().normalize()
    val firstDir = if (Files.isDirectory(start)) start else start.parent
    // Terminates: Path.parent is null at the filesystem root.
    return generateSequence(firstDir) { it.parent }
        .firstOrNull { Files.isDirectory(it.resolve(WHY_DIR_NAME)) }
}

/** `<root>/.why/tasks`. Not guaranteed to exist; [TaskStore.loadAll] treats absence as empty. */
fun tasksDir(root: Path): Path = root.resolve(WHY_DIR_NAME).resolve(TASKS_DIR_NAME)

/**
 * [file] as the project-relative, forward-slash-separated `file` key of §5.3,
 * or null when [file] is not under [root].
 *
 * Forward slashes on every platform, including Windows, so a note written on
 * one operating system reads on another. The inverse is `root.resolve(key)`,
 * which accepts forward slashes on Windows too, so it needs no helper.
 */
fun projectRelativePath(root: Path, file: Path): String? {
    val relative = runCatching {
        // Throws IllegalArgumentException across filesystem providers or Windows drives.
        root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
    }.getOrNull() ?: return null
    // Empty means file == root; ".." means outside it. Neither is a note's file key.
    if (relative.toString().isEmpty() || relative.first().toString() == "..") return null
    return relative.joinToString("/")
}
