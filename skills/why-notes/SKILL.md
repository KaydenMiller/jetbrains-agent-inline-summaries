---
name: why-notes
description: Record the rationale for code changes as .why notes. Use whenever you edit code in a project that has a .why/ directory, or when the developer asks for why notes.
---

# Recording why — `writer/why`, two calls per request (`--help` for arguments)

`writer/why` is Python 3 and ships with this skill; that path is relative to **this
skill's own directory**, not to the project. Keep the project as the working directory —
that is where `.why/` is created and where `--file` paths are resolved.

## Once, before you start editing

    python3 writer/why start "<the user's request, close to verbatim>"

It prints a task id such as `T-4`. **Keep that id** and pass `--task T-4` to every note
in this request: `--task` is required as soon as a second task file exists, and one will.

## Once per changed region, after that edit is on disk

    python3 writer/why note --task T-4 --file <path> --start <n> --end <n> --symbol <Type.Member> --what "..." --why "..." --flag <f>

`--start`/`--end` are 1-based inclusive line numbers **in the file as it now stands after
your edit** — re-read it and count; numbers taken from a diff anchor the note to the wrong
code. A range of only blank lines is refused outright; a range of only `}` is accepted but
re-anchors to some other brace. `--symbol` is optional, but it survives the code moving.

A region is one change a reader wants explained in one sentence: a method you rewrote, a
field you added with its default, a guard you inserted. Three hunks in one method serving
one purpose are **one** note; a changed method plus an unrelated new field gets **two**.

## `what` versus `why`

`what` = the behaviour the code has now, readable by someone who never saw the old
version. `why` = the reason it changed: what was wrong, or what forced it. Never ship
"fixed the bug", "refactored", "as requested", or a bare ticket id.

Bad — names neither the behaviour nor the cause:

    --what "Added a buffer to fix the bug" --why "Jumps were being dropped"

Good:

    --what "Buffers a jump press for 120ms and consumes it on the next ground contact."
    --why  "Input was polled once per FixedUpdate and discarded when not grounded, so presses during landing frames vanished."

## Flags: state what you guessed at — this is what earns the note a careful reader

- `--flag needs-review` — you are not sure: you guessed at intent, you could not run
  the code, or you changed behaviour whose callers you did not all read.
- `--flag tunable:<Name>` — you invented a value someone is expected to adjust, e.g.
  `--flag tunable:JumpBufferMs`. One flag per invented value.

**Every note carries at least one flag.** A note with neither of the above must carry
`--flag verified:<how>` naming what you actually did to be sure — `verified:tests`,
`verified:ran-it`, `verified:read-all-callers`, `verified:trivial`. Do not write
`verified:tests` if you did not run tests — that is a false statement, not an omission.

Before reporting back, prove no note is silent. This must print nothing; whatever it
prints is a note you have to go back and flag:

    grep '"kind":"note"' .why/tasks/T-4.jsonl | grep -v 'needs-review\|tunable:\|verified:'
