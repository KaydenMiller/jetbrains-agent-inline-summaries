# `.why` — human verification runbook

Plugin: `com.kaydenmiller.agent-inline-summaries` ("Why"). Fixture: `fixture/WhyFixtureProject`.
Written for someone who has not read this repository. Budget about 40 minutes.

Fill in the pass/fail box on every step. An agent must not fill them in; the whole point of
this document is that a person ran the thing.

---

## Table of contents

- [Why these steps and not others](#why-these-steps-and-not-others)
- [Before you start](#before-you-start)
- [What is in the fixture, and which states were manufactured](#what-is-in-the-fixture-and-which-states-were-manufactured)
- [Steps 1–4 — launch and the gutter (R7.1)](#steps-14--launch-and-the-gutter-r71)
- [Steps 5–10 — popup and reveal (R7.2)](#steps-510--popup-and-reveal-r72)
- [Steps 11–12 — copy reference (R7.3)](#steps-1112--copy-reference-r73)
- [Steps 13–17 — tool window (R7.4)](#steps-1317--tool-window-r74)
- [Step 18 — garbage collection (R7.6)](#step-18--garbage-collection-r76)
- [Step 19 — file watcher (R7.5)](#step-19--file-watcher-r75)
- [Step 20 — perceived latency](#step-20--perceived-latency)
- [Step 21 — Unity `.meta` files (R5.1.3)](#step-21--unity-meta-files-r513)
- [Step 22 — the question this whole project rests on (§1.1)](#step-22--the-question-this-whole-project-rests-on-11)
- [Command reference](#command-reference)
- [Sign-off](#sign-off)

---

## Why these steps and not others

The plugin has 122 automated tests and they pass. Three defects on this project were still
found only by running the real thing, and all three sat in the same blind spot: the test
constructed the world so that the property under test could not fail.

| Defect | Coverage that missed it | What the test had fabricated |
|---|---|---|
| The file watcher never fired for a write from another process | 13 headless tests passed | the fixture created its files *through* the virtual file system, so the directory was already known |
| Clicking a gutter icon opened the popup at the caret, not at the icon | gutter tests passed | the `DataContext` the test built never contained a mouse event |
| The reveal highlight could not be dismissed | reveal tests passed | tests call `navigate` directly, so no real click was ever routed and "editor not focused" never happened |

So the steps below are weighted two ways:

1. **Inputs a harness has to fabricate** — a real mouse click, a real keystroke, window
   focus moving, a write from a separate process. Steps 6, 9, 12, 19.
2. **Judgements only a person can make** — is a 12x12 icon legible, does 70% opacity read
   as "slightly dimmed" or as broken rendering, does a highlighted region read as "this
   region" or as an error, is a text-only tool-window stripe legible. Steps 3, 4, 8, 10,
   17, 20.

Logic that already has coverage is not re-checked here. Resolution states (Solid, Drifted,
Orphaned), hash normalisation, JSONL tolerance and the archive filter are all asserted on
data in the test suite; the steps that mention them do so because a state has to be *on
screen* for the visual check, not to re-test the state machine.

---

## Before you start

**Prerequisites**

- Rider is installed at `/Users/kaydenmiller/Applications/Rider.app` (build `261.23567.144`).
  This is the `riderLocalPath` value in `gradle.properties`; a different location means
  editing that property, not this runbook.
- Java Development Kit 21 at `/opt/homebrew/opt/openjdk@21` (`org.gradle.java.home`).
- `python3` on the path — steps 19 and 21 use it.

**Rebuild the fixture's git history if it is missing.** The fixture is its own one-commit
git repository. That commit holds the code as it was *before* the agent edits, so the
agent's work shows up as uncommitted modifications — which is what Rider draws as VCS
change bars in the gutter, right next to the plugin's own icons. Several steps below read
those bars.

```
ls fixture/WhyFixtureProject/.git >/dev/null 2>&1 || ./fixture/setup-fixture-git.sh
```

The script prints `HEAD c98974a` and five modified/deleted paths. `c98974a` is the `base`
SHA recorded in every note, so that hash matching is not a coincidence.

**Launch the sandbox** from the repository root:

```
./gradlew runRider -PsandboxProject=/Users/kaydenmiller/Documents/workspaces/why-plugin/fixture/WhyFixtureProject
```

The `-PsandboxProject` path must be absolute; that argument is what makes Rider open the
fixture instead of the welcome screen. Leave this Gradle command running for the whole
runbook — closing Rider ends it.

**Where the log is.** Several steps ask you to read `idea.log`. Find it with:

```
find build -name idea.log
```

Tail it in a second terminal while you work:

```
tail -f "$(find build -name idea.log | head -1)"
```

---

## What is in the fixture, and which states were manufactured

Nine notes across three tasks. Seven resolve against files that exist; one is an orphan;
one points at a file that was deleted.

**Read this before recording any failure.** Six of the nine states were produced by
editing the code *after* the note was written, which is what drift and orphaning genuinely
are, but it means the fixture is not a straight recording of one agent session. Every
manufactured state is named in the last column. An icon sitting on a line that nothing
changed is expected for the notes marked "no code change" — that is the anchor still
matching, not a bug.

| Note | Task | File | Stored anchor | Flags | Expected state | Icon lands on | How that state was produced |
|---|---|---|---|---|---|---|---|
| `W-NE44` | T-1 | `PlayerController.cs` | 31–31 | `needs-review` | **Solid** | line 31 | genuine: the field the agent added is untouched |
| `W-A05D` | T-1 | `PlayerController.cs` | 31–33 | `tunable:CoyoteTimeMs` | **Solid** | line 31 | manufactured: a second note deliberately anchored to a range starting on the same line as `W-NE44`, so two marks share one gutter line (step 7) |
| `W-EA82` | T-1 | `PlayerController.cs` | 110–137 | `changes-feel`, `tunable:JumpBufferMs` | **Solid, re-anchored to 127–155** | line 127 | manufactured: after the note was written, `HandleJump` and its doc comment were moved below `GroundCheck`. Content unchanged, so the hash still matches at the new position |
| `W-EK86` | T-1 | `PlayerController.cs` | 67–75 | `verified:read-all-callers` | **Drifted** | line 67 | manufactured: after the note was written, line 71 was widened to `if (Input.GetButtonDown("Jump") \|\| Input.GetButtonDown("Submit"))` |
| `W-KXWT` | T-2 | `CameraFollow.cs` | 41–58 | `verified:read-all-callers` | **Solid** | line 41 | genuine: no code change since the note |
| `W-SEB3` | T-2 | `CameraFollow.cs` | 74–91 | `needs-review` | **Orphaned** (no icon) | — | manufactured: `OnDrawGizmosSelected` was deleted after the note was written, so the stored range ends past the end of a 74-line file and no line mentions the symbol |
| `W-QRBA` | T-2 | `Legacy/LegacyCameraShake.cs` | 16–19 | `verified:read-all-callers` | **dropped at startup** (R7.6) | — | manufactured: the file was deleted after the note was written. It is absent from the working tree and shows as ` D` in `git status` |
| `W-RCY8` | T-3 | `Generated/TerrainTileTable.cs` | 1225–1231 | `needs-review` | **Drifted** | line 1225 | manufactured: after the note was written, the row clamp changed from `0, 23` to `0, 24` |
| `W-KT87` | T-3 | `Generated/TerrainTileTable.cs` | 200–205 | `verified:read-all-callers` | **Solid** | line 200 | genuine: no code change, and this region is *committed*, so it has no VCS change bar (used by step 10) |

That is seven gutter icons on six lines in three files. Every expected state in the table
was recomputed against the files as they stand, using
`skills/why-notes/writer/reference_hash.py` and the same five resolution steps the plugin
uses.

The three task prompts, which the tool window shows as group headers:

- **T-1** — "Jumps feel dropped when landing" (the requirements document's own §5.2 example)
- **T-2** — "Camera dips and clips through the ground when the player lands"
- **T-3** — "Regenerate the terrain tile table from the new heightmap export"

---

## Steps 1–4 — launch and the gutter (R7.1)

### 1. The sandbox opens the fixture and logs a clean load

**Requirement:** setup, plus the R7.5 startup path.

**Do:** run the launch command above. When Rider has opened, search `idea.log` for the
plugin's own lines:

```
grep -n "^.*why:" "$(find build -name idea.log | head -1)" | tail -20
grep -ni "exception\|error" "$(find build -name idea.log | head -1)" | grep -i "why\|kaydenmiller"
```

**Expect:** a line reading `why: initial load under .../fixture/WhyFixtureProject/.why -> 3 task(s), 8 note(s) across 3 file(s)`
or similar (8, not 9 — see step 18), and the second grep prints nothing.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 2. Icons appear on the anchored ranges, and only there

**Requirement:** R7.1.

**Do:** open all three files and look at the left gutter:
`Assets/Scripts/PlayerController.cs`, `Assets/Scripts/CameraFollow.cs`,
`Assets/Scripts/Generated/TerrainTileTable.cs`.

**Expect:** exactly seven `.why` icons, on these lines:

- `PlayerController.cs` — line 31 (**two** icons side by side), line 67, line 127
- `CameraFollow.cs` — line 41, and **nothing** near line 74
- `TerrainTileTable.cs` — line 200 and line 1225

No icon anywhere else. The absence at line 74 of `CameraFollow.cs` is the orphan
(`W-SEB3`): §6.2 renders no gutter icon for an orphan, and step 13 finds it in the tool
window instead.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 3. The icon is legible at 12x12, in both themes

**Requirement:** R7.1. Cannot be checked headlessly — no test renders an icon.

**Do:** look at the icon on line 31 of `PlayerController.cs`. Then switch theme
(`Settings | Appearance & Behavior | Appearance | Theme`, or search "theme" in
`Shift+Shift`) between a light and a dark theme and look again. The plugin ships two
files, `whyNote.svg` and `whyNote_dark.svg`, and the platform picks by theme.

**Expect:** at both themes the mark is distinguishable at a glance from Rider's own gutter
furniture — breakpoints, run arrows, fold arrows, VCS change bars — without leaning in.

**Result:** `[ ] pass  [ ] fail` — which themes did you try, and did either look wrong?

______________________________________________________________________________

### 4. 70% opacity reads as "slightly dimmed", not as broken rendering

**Requirement:** R7.1, `WhyIcons.DIMMED_ALPHA = 0.7f`. Cannot be checked headlessly.

Opacity carries **reveal** state, not drift: every icon rests at 70% and the one note whose
region is currently revealed (step 9) goes to full opacity. 0.4 was rejected in an earlier
sandbox pass as looking like a rendering fault and 0.65 was accepted; 0.7 is the value that
shipped and it has not been looked at since.

**Do:** with no note revealed, compare a `.why` icon against a Rider icon at full strength
in the same gutter — set a breakpoint on line 30 of `PlayerController.cs` for a neighbour,
then remove it afterwards.

**Expect:** the icon reads as deliberately quiet rather than as failing to draw.

Also record a judgement the tests cannot make: **drifted notes are not visually
distinguishable from solid ones in the gutter.** Both draw the same icon at the same
opacity; §6.2's "visually de-emphasised" for drift now lives in the popup text and the
tool-window row instead. Compare line 67 (drifted) with line 31 (solid) and say whether
that is a gap worth a ticket.

**Result:** `[ ] pass  [ ] fail` — is 0.7 right, and does drift need its own treatment?

______________________________________________________________________________

---

## Steps 5–10 — popup and reveal (R7.2)

### 5. Hover shows what, why, flags and the identifiers

**Requirement:** R7.2.

**Do:** hover the icon on line 31 of `PlayerController.cs` (the upper of the two marks is
`W-NE44`; if you get the other one, that is `W-A05D` — both are correct notes for that
line).

**Expect:** for `W-NE44`, a hint containing:

- bold: "New serialized field, default 120 ms, the window a jump press stays live for."
- then: "Tunable in the inspector without a rebuild. 120 is a guess copied from the feel of other platformers, not measured against this capsule."
- then: `flags: needs-review`
- then, small: `W-NE44 · task T-1 · anchored code unchanged`

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 6. Clicking the icon opens the popup at the icon, with the caret somewhere else

**Requirement:** R7.2. **This is a regression check for a shipped defect** — the popup used
to open at the caret because the gutter tests build a `DataContext` with no mouse event in
it.

**Do:** in `TerrainTileTable.cs`, click into the code at line 1 to park the caret there.
Scroll to line 200 without touching the caret (mouse wheel, or the scrollbar — not the
keyboard). Now click the gutter icon on line 200.

**Expect:** the popup appears next to the icon on line 200, not up at line 1 and not
off-screen. Its text is `W-KT87 · task T-3 · anchored code unchanged`.

**Result:** `[ ] pass  [ ] fail` — where did the popup actually appear?

______________________________________________________________________________

### 7. Two notes on one line lay out side by side and stay distinct

**Requirement:** R7.1, R7.2. Manufactured deliberately: `W-NE44` is anchored to line 31
alone and `W-A05D` to lines 31–33, so both resolve with a start line of 31.

**Do:** look at line 31 of `PlayerController.cs`, then hover each of the two marks in turn.

**Expect:** two marks are visible and separately hoverable; each popup names one note only
(`W-NE44` with `flags: needs-review`, `W-A05D` with `flags: tunable:CoyoteTimeMs`). There
is no merged "2 notes here" mark.

Record whether the gutter is wide enough here to hit each mark with the mouse without
several attempts.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 8. A drifted note says so, and says it without alarm

**Requirement:** R7.2 and R6.2.1 (drift is informational — no modal, no warning, no
prompt to reconcile).

**Do:** click the icon on line 67 of `PlayerController.cs` (`W-EK86`). Line 71 inside that
range was edited after the note was written, so this note is drifted. Compare with the
solid popup from step 5.

**Expect:** the popup's last line reads `W-EK86 · task T-1 · anchored code changed since
this note was written`. No dialog, no notification balloon, no yellow banner, nothing to
dismiss, no suggested action.

Judgement to record: does that sentence read as information, or does it read as an error
the reader is being asked to fix?

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 9. The reveal shows the region, and a second click dismisses it — including after focus moved away

**Requirement:** R7.2 plus the W-14 reveal. **Regression check for a shipped defect:** the
reveal could not be dismissed, because tests drive `navigate` directly and never route a
real click, so "the editor is not focused" was never exercised.

**Do:** in `PlayerController.cs`:

1. Click the icon on line 127 (`W-EA82`, the note whose code moved). Lines 127–155 should
   be highlighted and that icon should go to full opacity.
2. Click somewhere outside the editor so the editor loses focus — the Project tool window,
   or the Why tool window from step 13. Come back and click the same icon again.
3. Repeat, and this time press `Escape` instead.

**Expect:** the region 127–155 highlights on the first click; the second click on the same
icon clears it, whether or not focus went elsewhere in between; `Escape` also clears it.
Typing in the editor, moving the caret out of the region, and clicking elsewhere in the
code must **not** clear it — that behaviour was removed on purpose because it fired while
the reader was still reading.

**Result:** `[ ] pass  [ ] fail` — did anything else dismiss it?

______________________________________________________________________________

### 10. The reveal reads as "this region", both with and without VCS change bars

**Requirement:** R7.2, plus R6.3.1's interaction with the platform's line status tracker.
Cannot be checked headlessly.

The fixture's single commit holds the pre-agent code, so `PlayerController.cs` and
`CameraFollow.cs` carry Rider's change bars on the agent-edited lines, while
`TerrainTileTable.cs` line 200 is committed and unmarked. The reveal exists for the second
case: once work is committed, the bars are gone and the note's extent is invisible.

**Do:** reveal `W-EA82` (line 127 of `PlayerController.cs`, alongside change bars), then
reveal `W-KT87` (line 200 of `TerrainTileTable.cs`, no bars).

**Expect:** in both cases the highlight reads as "the note is about this span of code".

Record the judgements: does the highlight read as a selection or as an error state (a red
squiggle, a search hit, a failed inspection)? Where change bars are present, do the two
decorations compete or complement?

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

---

## Steps 11–12 — copy reference (R7.3)

### 11. Copy Reference from the gutter icon's context menu

**Requirement:** R7.3 — copy the identifier, paste it into an agent chat. This is the
entire conversation loop in v1.

**Do:** right-click the icon on line 41 of `CameraFollow.cs`, choose **Copy Why Note
Reference**, then in a terminal:

```
pbpaste
```

**Expect:** `pbpaste` prints `W-KXWT` and nothing else — the bare identifier, no filename
prefix, no trailing newline expected.

**Result:** `[ ] pass  [ ] fail` — what did `pbpaste` print? ____________________________

### 12. The keyboard shortcut, from inside a range and from outside every range

**Requirement:** R7.3. A real keystroke through the real keymap is not what the tests
exercise, and the shortcut has to survive Rider's own bindings.

**Do:**

1. Click into the code at line 130 of `PlayerController.cs` — inside `W-EA82`'s resolved
   range of 127–155 — and press **`Ctrl+Alt+Shift+O`** (on this keyboard: control, not
   command). Run `pbpaste`.
2. Copy something unrelated first (`echo keep-me | pbcopy`), click into line 5 of the same
   file, which is inside no note, and press the shortcut again. Run `pbpaste`.

**Expect:** step 1 prints `W-EA82`. Step 2 leaves the clipboard as `keep-me` and writes
`No why note at the caret` in the status bar at the bottom of the window — deliberately
quiet, no dialog. Nothing in Rider should intercept the shortcut first: no other action
should fire, and no "shortcut conflict" popup should appear.

Judgement to record: is a status-bar message discoverable enough, or did you miss it?

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

---

## Steps 13–17 — tool window (R7.4)

### 13. Every note, grouped by task, with orphans separated

**Requirement:** R7.4.

**Do:** open the **Why** tool window from the right-hand stripe.

**Expect:** three task groups, each headed by its prompt text ("Jumps feel dropped when
landing", "Camera dips and clips through the ground when the player lands", "Regenerate the
terrain tile table from the new heightmap export"), and a separate **Orphaned** group
holding `W-SEB3`. Drifted rows (`W-EK86`, `W-RCY8`) are marked ` · drifted`. `W-QRBA` is
absent — that is step 18.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 14. Clicking a row navigates to where the code is *now*

**Requirement:** R7.4.

**Do:** click three rows in turn and watch where the editor lands:

- `W-EA82` — the note whose code moved. Stored anchor 110–137, current position 127–155.
- `W-RCY8` — drifted, in the 1233-line generated file.
- `W-SEB3` — the orphan.

**Expect:** `W-EA82` opens `PlayerController.cs` at line 127 and reveals 127–155, i.e. at
the moved `HandleJump`, not at the stored line 110. `W-RCY8` opens `TerrainTileTable.cs` at
its stale range around line 1225 — a drifted note points at where the code used to be,
which is the navigable answer. `W-SEB3` has no range; record what clicking it does (nothing
happening is the expected behaviour, an exception is not).

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 15. The needs-review filter

**Requirement:** R7.4.

**Do:** toggle **Only Notes Needing Review** in the tool window toolbar.

**Expect:** three notes survive the filter — `W-NE44`, `W-SEB3`, `W-RCY8`. `W-A05D`
(`tunable:CoyoteTimeMs`) and the `verified:` notes disappear. Toggle it back off and
confirm everything returns.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

### 16. Archive hides a task without touching the disk

**Requirement:** R7.4, plus §2's "the plugin never writes".

**Do:** before archiving, record a checksum of the corpus:

```
shasum -a 256 fixture/WhyFixtureProject/.why/tasks/*.jsonl
```

Select the T-2 group, invoke **Archive Task** (toolbar or right-click), then re-run the
checksum.

**Expect:** the T-2 group disappears from the window. The three checksums are unchanged —
archive is view-only for this session. Restart the IDE later and T-2 comes back.

**Result:** `[ ] pass  [ ] fail` — did the checksums match? ____________________________

### 17. The stripe label is legible with no icon

**Requirement:** R7.4. Cannot be checked headlessly. The tool window declares no icon on
purpose — the gutter icon is 12x12 and a stripe icon is 13x13 — so the stripe falls back to
the text "Why".

**Do:** collapse the tool window and look at the right-hand stripe among Rider's own
entries.

**Expect:** the label is readable and findable by someone who does not already know it is
there.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

---

## Step 18 — garbage collection (R7.6)

### 18. A note whose file is gone is dropped at startup, and the file on disk is not touched

**Requirement:** R7.6 — on startup, drop notes whose `file` no longer exists. Drop from the
model only; the plugin never rewrites `.why/`.

`W-QRBA` points at `Assets/Scripts/Legacy/LegacyCameraShake.cs`, which was deleted after
the note was written. The note record is still in `T-2.jsonl` on disk.

**Do:**

```
grep -c W-QRBA fixture/WhyFixtureProject/.why/tasks/T-2.jsonl
grep "why: dropped" "$(find build -name idea.log | head -1)"
shasum -a 256 fixture/WhyFixtureProject/.why/tasks/T-2.jsonl
```

and look for `W-QRBA` in the tool window.

**Expect:** the record is present on disk (`grep -c` prints `1`), the log carries
`why: dropped 1 note(s) for 1 missing file(s)`, and the tool window does not list it. T-2
shows one note plus the orphan.

**Known behaviour, not a defect:** after step 19 reloads a task file, the watcher re-folds
the corpus and `W-QRBA` can reappear in the window until the next restart. That is
documented in `WhyWatcher.initialLoad`. Do this step before step 19.

**Result:** `[ ] pass  [ ] fail` — notes: ______________________________________________

---

## Step 19 — file watcher (R7.5)

### 19. A write from a separate process updates the model with no restart and no refresh

**Requirement:** R7.5. **Regression check for a shipped defect:** the watcher passed 13
headless tests and still never fired for an external write, because those tests created
their files *through* the virtual file system. Nothing but a real second process exercises
this.

**Do:** leave Rider open and in the foreground. In a terminal, from the fixture directory,
create a fourth task and then append a second note to it:

```
cd fixture/WhyFixtureProject
python3 ../../skills/why-notes/writer/why start "Runbook step: watcher check, external write"
```

That prints `T-4`. Then, watching the editor as you press return:

```
python3 ../../skills/why-notes/writer/why note --task T-4 --file Assets/Scripts/CameraFollow.cs --start 30 --end 39 --symbol CameraFollow.LateUpdate --what "Bails out when no target is assigned, then runs the follow and aim passes in that order." --why "Written from the runbook to prove the plugin notices a task file created by another process." --flag verified:trivial
```

and then, to exercise appending to a file the plugin already holds:

```
python3 ../../skills/why-notes/writer/why note --task T-4 --file Assets/Scripts/PlayerController.cs --start 114 --end 126 --symbol PlayerController.GroundCheck --what "Sphere-casts down from the capsule base and reports whether anything in groundLayers is within groundProbeDistance." --why "Written from the runbook to prove the plugin notices a note appended to a task file it already holds." --flag verified:trivial
```

Each command prints the identifier it generated (`W-....`) — the identifiers are random, so
use what it prints.

**Expect:** without touching Rider — no restart, no `File | Reload`, no click in the editor
— a new icon appears on line 30 of `CameraFollow.cs`, then another on line 114 of
`PlayerController.cs`, and a fourth group headed "Runbook step: watcher check, external
write" appears in the tool window. The log gains `why: reloaded 1 task file(s) ... (T-4.jsonl)`
lines.

Record how long it took to appear. Slow is a finding; never is a defect.

**Then clean up** so the fixture is back to nine notes:

```
rm fixture/WhyFixtureProject/.why/tasks/T-4.jsonl
```

The icons and the group should disappear on their own; if they do not, that is a finding
for deletion handling.

**Result:** `[ ] pass  [ ] fail` — how long, and did deletion clear it?

______________________________________________________________________________

---

## Step 20 — perceived latency

### 20. Opening a 1233-line file with a drifted note in it

**Requirement:** performance, unmeasured. Cannot be checked headlessly in a way that means
anything.

`TerrainTileTable.cs` is 1233 lines and holds `W-RCY8`, which is drifted. A drifted note is
the resolver's worst case: steps 1 and 2 fail, so it sweeps every line offset against seven
window lengths before giving up — on the order of 8,600 hash computations for that one note,
on top of the solid note in the same file.

**Do:** close all editors, then open `Assets/Scripts/Generated/TerrainTileTable.cs`. Watch
the moment between the text appearing and the icons appearing. Then scroll the file quickly
end to end, and type a character somewhere in the middle and delete it.

**Expect:** no perceptible freeze on open, no lag while scrolling, no stutter while typing.

**Result:** `[ ] pass  [ ] fail` — estimate the delay before icons appeared: ____________

---

## Step 21 — Unity `.meta` files (R5.1.3)

### 21. Unity generates no `.meta` files under `.why/` — UNVERIFIED

**Requirement:** R5.1.3.

The requirements document treats the leading dot as sufficient: Unity's asset importer skips
dot-prefixed directories, so nothing under `.why/` is imported and no `.meta` files are
generated for it. That is a **reason to expect the requirement to hold, not evidence that it
does.**

State of the check on this machine, as of 2026-08-21:

- Unity Editor 6000.3.10f1 is installed at `/Applications/Unity/Hub/Editor/6000.3.10f1`,
  and the fixture's `ProjectSettings/ProjectVersion.txt` names that same version, so it can
  be opened without a version upgrade.
- A batch-mode import was attempted on a *copy* of the fixture:
  `Unity -batchmode -quit -nographics -projectPath <copy> -logFile <log>`. It exited 198
  with `No valid Unity Editor license found. Please activate your license.` No `Library/`
  directory was produced, so the importer never ran and the check proved nothing.

**This step is therefore recorded as unverified, not as passed.**

**To verify it** (needs a licensed editor — the fixture is not a runnable Unity project, so
work on a copy and expect Unity to add `Library/`, `Packages/` and more `ProjectSettings/`):

```
cp -R fixture/WhyFixtureProject /tmp/WhyFixtureUnity && rm -rf /tmp/WhyFixtureUnity/.git
open -a "/Applications/Unity/Hub/Editor/6000.3.10f1/Unity.app" --args -projectPath /tmp/WhyFixtureUnity
```

Wait for the import to finish, close Unity, then:

```
find /tmp/WhyFixtureUnity/.why -name "*.meta"
```

**Expect:** no output, and `.why/` still holds only `tasks/*.jsonl`. For contrast,
`find /tmp/WhyFixtureUnity/Assets -name "*.meta"` should list a `.meta` per script and per
folder.

**Result:** `[ ] verified pass  [ ] verified fail  [ ] left unverified` — notes:

______________________________________________________________________________

---

## Step 22 — the question this whole project rests on (§1.1)

### 22. Is reading rationale inline actually better than reading the transcript?

**Requirement:** §1.1, the core assumption. Everything above is machinery in service of
this, and **nobody has tested it, including Kayden.** It is an open question, and "no" is a
real answer that would be worth more than a "yes" recorded out of politeness.

The comparison is deliberately unequal in the transcript's favour on one axis: the
transcript is longer but complete, whereas notes are compressed and can be wrong.

**Do:**

1. Pick one of the two tasks that has both code and rationale in front of you — T-1 ("Jumps
   feel dropped when landing") or T-2 ("Camera dips and clips through the ground when the
   player lands").
2. **Inline route.** Open the file, read the code, and pull up the notes from the gutter as
   you go. Time yourself from opening the file to being able to answer: *what changed, why,
   and what would I have to decide next?* Note down the answer you arrive at.
3. **Transcript route.** Take the equivalent agent transcript for a change of the same shape
   — the session that produced this fixture, or any recent agent session of yours that
   touched a few regions of one file — and read it to answer the same three questions for
   that change. Time that too.
4. Answer below in your own words, not as a score.

Questions to answer:

- Which route answered "what changed and why" faster? By roughly how much?

  ______________________________________________________________________________

- Which route made the **next decision** easier to make — the tunable to adjust, the
  `needs-review` note to resolve, the guess to check?

  ______________________________________________________________________________

- What did the transcript give you that the inline notes did not?

  ______________________________________________________________________________

- What did the inline notes give you that the transcript did not?

  ______________________________________________________________________________

- Where the two disagreed, or where a note was too compressed to act on, what happened?

  ______________________________________________________________________________

- On this evidence, what should happen to the assumption in §1.1: hold it, qualify it, or
  drop it?

  ______________________________________________________________________________

**Result:** `[ ] inline was faster  [ ] transcript was faster  [ ] no clear difference`

---

## Command reference

General use of the commands this runbook asks you to type. `pbpaste`, `pbcopy`, `shasum`,
`open` and `find` are macOS/Unix commands; the rest are project tooling.

| Command | When you'd use it |
|---|---|
| `./gradlew runRider -PsandboxProject=<abs path>` | Launch a sandbox IDE with the plugin installed and a project already open, when you need to see the plugin in the real target IDE rather than in a test fixture. |
| `find <dir> -name <pattern>` | Locate files by name anywhere beneath a directory, when you know what a file is called but not where a build put it. |
| `tail -f <file>` | Watch a file as it grows, for following a log while you drive an application. |
| `grep -n <pattern> <file>` | Find which lines of a file contain something, with line numbers. `-c` counts matches instead of printing them; `-i` ignores case. |
| `pbcopy` / `pbpaste` | Put stdin on the macOS clipboard / print the clipboard to stdout. Use them to check what an application actually copied, rather than pasting somewhere and eyeballing it. |
| `shasum -a 256 <file>` | Fingerprint a file's contents, to prove later that it did or did not change. |
| `cp -R <src> <dst>` | Copy a directory and everything in it, when you want a throwaway copy to run a destructive tool against. |
| `rm <file>` / `rm -rf <dir>` | Delete a file / delete a directory and its contents without prompting. `-rf` does not ask and does not go to a trash can. |
| `open -a <app> --args …` | Launch a macOS application from the terminal and pass it arguments. |
| `python3 skills/why-notes/writer/why note …` | Record one rationale note for a code region. The only component that writes to `.why/`; the plugin never does. |
| `git status --short` | List which tracked files differ from the last commit, in one line each — `M` modified, `D` deleted. |

## Sign-off

- Run by: ____________________  Date: ____________
- Rider build: ____________________  Plugin version: `0.1.0`
- Steps passed: ____ / 22, with step 21 recorded as `[ ] unverified`
- Findings worth a ticket:

  ______________________________________________________________________________

  ______________________________________________________________________________

  ______________________________________________________________________________
