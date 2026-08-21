# `.why` — agent rationale, in the gutter

Two halves, one repository, one version.

| Half | What it is | Installed as |
|---|---|---|
| Viewer | JetBrains IDE plugin, id `com.kaydenmiller.agent-inline-summaries`. Read-only: it never writes to `.why/`. | Rider/IntelliJ plugin from a custom plugin repository |
| Writer | `skills/why-notes/` — the `why` command-line tool plus the instructions that tell an agent when and how to call it | Claude Code plugin |

**The plugin alone does nothing.** It is a viewer. Nothing in it creates a note, so a
developer who installs only the plugin sees an empty gutter forever, in every project. The
skill is what writes `.why/tasks/*.jsonl`; the plugin only displays what is already there.
Install both, or install neither.

Notes live in the project's `.why/` directory and are per-developer — nothing here asks you
to commit them.

## Install — Rider (the viewer), once

1. Settings → Plugins → ⚙ → **Manage Plugin Repositories** → **+**
2. Add:
   `https://github.com/KaydenMiller/jetbrains-agent-inline-summaries/releases/latest/download/updatePlugins.xml`
3. Marketplace tab → search **Why** → Install → restart if asked.

That URL always resolves to the newest release's manifest, and the manifest points at that
release's zip by exact version. Updates then arrive through the normal plugin-update
notification; the URL is added once and never revisited.

Requires build 261 (2026.1) or newer. No upper bound.

## Install — Claude Code (the writer), once

    claude plugin marketplace add KaydenMiller/jetbrains-agent-inline-summaries
    claude plugin install why-notes@why-notes

Or the same two steps through `/plugin` in a session. The marketplace source is the git
repository itself, so the `why` executable and its instructions arrive together and nothing
lands in your project. The marketplace and the plugin are both named `why-notes`, which is
why the install target repeats it.

## Versioning

`.github/workflows/release.yml` runs on every push to `master`. It resolves
`VERSION=0.1.<github.run_number>`, builds and tests and verifies at that version, writes
the same string into `.claude-plugin/plugin.json`, commits that one file, tags `v$VERSION`,
and publishes the zip plus `updatePlugins.xml` to the release. Both halves therefore carry
the same version string, from the same commit.

Version numbers are not signed, and the plugin is not signed. Signing is not required for
a custom plugin repository. It *is* required to publish to the JetBrains Marketplace, which
this repository does not currently do.

The plugin id `com.kaydenmiller.agent-inline-summaries` is now fixed. Changing it after
anyone has installed the plugin does not update them — the IDE would treat the new id as a
different plugin, so every installed user would have to remove the old one and install the
new one by hand.

## Known open question — hash-contract divergence

The anchor hash in `skills/why-notes/writer/HASHING.md` is implemented twice: Kotlin in the
plugin (`src/main/kotlin/why/resolve/Anchoring.kt`) and Python in the writer
(`skills/why-notes/writer/why`). Requirements §5.4 specifies no schema versioning in v1. If
the two disagree on one byte, every note renders as drifted, with no error and nothing
visible to explain it.

What is in place:

- Both implementations run against the same `skills/why-notes/writer/vectors.json` in CI —
  the Kotlin side in `AnchoringTest`, the Python side in `verify_vectors.py` — so a
  divergence fails the build that would have shipped it.
- Both halves carry the same version from the same commit, so a developer can compare the
  plugin version in Settings → Plugins against the skill's `plugin.json` version and see
  whether they match.

What is not in place: nothing stops a developer updating one half and leaving the other
behind, and neither half detects the mismatch at runtime. See `PLAN.md` §10.7 for the two
mechanisms still under consideration.
