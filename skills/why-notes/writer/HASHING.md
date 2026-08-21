# `.why` anchor hash — normative specification

**Task:** W-2. **Supersedes:** `PLAN.md` §4 (that section is the starting draft; where the two differ, this file wins).
**Implemented twice:** `src/main/kotlin/why/resolve/Anchoring.kt` (W-6, Kotlin) and `why` in this directory (W-11, Python).
**Executable copy of this file:** `reference_hash.py`, in this directory. **Contract tests:** `vectors.json`, run by `verify_vectors.py`, both in this directory.

If the two implementations disagree on one byte for one input, every note in the product renders as drifted. Nothing below is implementation-defined.

## Table of contents

1. [Inputs and outputs](#1-inputs-and-outputs)
2. [Step 0 — bytes to text](#2-step-0--bytes-to-text)
3. [Step 1 — the whitespace set](#3-step-1--the-whitespace-set)
4. [Step 2 — splitting text into lines](#4-step-2--splitting-text-into-lines)
5. [Step 3 — selecting the range](#5-step-3--selecting-the-range)
6. [Step 4 — normalising each line](#6-step-4--normalising-each-line)
7. [Step 5 — dropping empty lines and joining](#7-step-5--dropping-empty-lines-and-joining)
8. [Step 6 — hashing](#8-step-6--hashing)
9. [Worked example](#9-worked-example)
10. [Edge-case table](#10-edge-case-table)
11. [Consequences](#11-consequences)
12. [Vector file format](#12-vector-file-format)
13. [Rules that bind the callers, not the hash](#13-rules-that-bind-the-callers-not-the-hash)

---

## 1. Inputs and outputs

| | |
|---|---|
| Inputs | the file's bytes (or its already-decoded text), plus `start` and `end`, integers, 1-based, inclusive |
| Output | 6 lowercase hexadecimal characters, or one of the errors in §5 and §2 |

The function is pure: it depends on nothing except those three inputs. No file path, no locale, no encoding declaration, no editor setting, no `.editorconfig`.

## 2. Step 0 — bytes to text

1. Decode the bytes as UTF-8, **strict**. Invalid UTF-8 is an error, `INVALID_ENCODING`. No fallback to Latin-1, no replacement characters, no charset sniffing — a silent fallback would make the two implementations disagree exactly where the failure is hardest to see.
2. If the decoded text begins with U+FEFF (a UTF-8 byte-order mark, `EF BB BF`, which Visual Studio writes on C# files), remove that one character. A U+FEFF anywhere else in the file is content.
3. Apply no Unicode normalisation. Text is treated as a sequence of code points exactly as decoded; NFC and NFD forms of the same glyph hash differently.

In the plugin, the text comes from the platform `Document` rather than from disk, so step 0 is already done by the platform. The plugin still strips a leading U+FEFF, because the platform may hand back a document that retains it.

## 3. Step 1 — the whitespace set

Whitespace is **exactly these six code points**, and nothing else:

| Code point | Name | Escape |
|---|---|---|
| U+0009 | CHARACTER TABULATION | `\t` |
| U+000A | LINE FEED | `\n` |
| U+000B | LINE TABULATION | `\u000B` (Kotlin has no `\v` escape), `\v` (Python) |
| U+000C | FORM FEED | `\u000C` (Kotlin has no `\f` escape), `\f` (Python) |
| U+000D | CARRIAGE RETURN | `\r` |
| U+0020 | SPACE | ` ` |

U+000A and U+000D cannot survive §4 and are listed only so the set is closed and copy-pasteable.

**Do not call `Char.isWhitespace()` (Kotlin) or `str.isspace()` / `str.split()` with no argument (Python).** They disagree: Kotlin's `Char.isWhitespace` follows `Character.isWhitespace`, which excludes U+00A0 NO-BREAK SPACE and U+2007 FIGURE SPACE but includes U+001C–U+001F; Python's `str.isspace` includes U+00A0, U+001C–U+001F and U+0085. A range containing a non-breaking space would then hash differently on the two sides, and the note would read as permanently drifted with no visible cause. The six-code-point set above needs no Unicode tables, is expressible as a literal in both languages, and is trivially identical.

Everything outside the set is content, explicitly including U+00A0 NO-BREAK SPACE, U+2000–U+200A, U+3000 IDEOGRAPHIC SPACE, U+200B ZERO WIDTH SPACE and U+0085 NEXT LINE. Consequence: replacing a space with a non-breaking space drifts the note. Vectors `nbsp_is_content_differs` and `form_feed_is_whitespace` pin both sides of this boundary.

## 4. Step 2 — splitting text into lines

Applied to the whole file text, in this order:

1. Replace every `\r\n` with `\n`.
2. Replace every remaining `\r` with `\n`. A lone `\r` is therefore a line terminator (classic Mac endings), not content.
3. If the text is now the empty string, the file has **zero** lines. Stop.
4. If the text ends with `\n`, remove that one final `\n`. Remove only one.
5. Split on `\n`. The result is the line list; lines carry no terminator.

Therefore:

| File text | Lines | Count |
|---|---|---|
| `""` | — | 0 |
| `"\n"` | `[""]` | 1 |
| `"a"` | `["a"]` | 1 |
| `"a\n"` | `["a"]` | 1 |
| `"a\n\n"` | `["a", ""]` | 2 |
| `"a\r\nb\r\n"` | `["a", "b"]` | 2 |
| `"a\rb"` | `["a", "b"]` | 2 |

**A trailing newline at end of file does not create a final empty line.** Two files that differ only in whether the last line is terminated have the same line list and the same hash for every range (vector `no_trailing_newline_same_hash`).

## 5. Step 3 — selecting the range

Let `n` be the number of lines from §4. Validate against the **requested** values first, then clamp:

| Condition | Behaviour | Error code | Why |
|---|---|---|---|
| `start` or `end` not an integer | error | `INVALID_RANGE` | caller bug |
| `start < 1` | error | `START_BELOW_ONE` | the resolver only ever builds ranges with `start >= 1`, so rejecting this costs it nothing and turns a writer bug into a loud failure instead of a silent off-by-one |
| `start > end` | error | `INVALID_RANGE` | same: unreachable from the resolver, so the check is free there and catches a caller that swapped its arguments |
| `end > n` | **clamp** `end` to `n` | — | the resolver's step-3 sweep hashes a `K±3`-line window at every line offset, so windows routinely run past end of file; clamping keeps the bounds guard out of every call site |
| `start > n` | **no error**: the selection is empty | — | same sweep argument, and it is the normal state after a file shrinks |

Selection = lines `start` through `min(end, n)` inclusive, in file order; empty when `start > min(end, n)`. An empty selection is not an error; it produces the empty-content hash from §8.

`end` is never rejected for being large. `end = 2^31 - 1` on a 5-line file is the same as `end = 5`.

## 6. Step 4 — normalising each line

For each selected line, in order:

1. Remove leading characters that are in the §3 set, and trailing characters that are in the §3 set.
2. Replace every remaining maximal run of one or more §3 characters with a single U+0020 SPACE.

No other transformation. Case is preserved. Comment markers, string quotes and punctuation are preserved. Nothing is parsed — this is a text operation with no knowledge of any language.

Reference form, with the §3 set written out as an explicit character class:

```
normalised_line = split(line, /[\t\n\u000B\u000C\r ]+/) filtered to non-empty parts, joined with " "
```

Splitting on the run and rejoining with a single space performs steps 1 and 2 together: the leading and trailing runs produce empty parts, which the filter drops.

## 7. Step 5 — dropping empty lines and joining

1. Discard every line whose normalised form from §6 is the empty string. This covers the truly empty line, the line of only spaces, the line of only a tab, the line of only a form feed, and any mix of them — all four are indistinguishable after §6 and all four are discarded.
2. Join the survivors with a single `\n` (U+000A). No terminator is appended to the last survivor. No terminator is prepended.

If no line survives, the normalised content is the empty string.

## 8. Step 6 — hashing

1. Encode the joined string as UTF-8. No byte-order mark. Because §6 leaves no `\r`, the encoded bytes never contain `0D`.
2. `SHA-256` of those bytes.
3. Render the digest as lowercase hexadecimal, 64 characters.
4. Take the **first 6** characters. That string is `anchor.hash`.

The empty-content hash is `e3b0c4` (`SHA-256` of zero bytes, `e3b0c44298fc1c14…`). Any range whose normalised content is empty hashes to it. §13 says what the callers do about that.

6 hexadecimal characters is 24 bits. Over the birthday bound on 2^24 values, a 50% chance of one collision arrives at about 4,800 distinct windows compared, and W-6 step 3 compares at most `7 x lines_in_file` windows per unresolved note — 700 comparisons for a 100-line file, 7,000 for a 1,000-line file. The truncation length is therefore load-bearing, not free: 6 characters is what `PLAN.md` §4 and R5.3 specify, and the numbers above are what a future change to it would have to argue against.

## 9. Worked example

File (`Total.cs`, LF endings, trailing newline present, `·` marks a space and `→` a tab, shown for the reader only):

```
1  public int Total(int[] xs)
2  {
3  →int·sum·=·0;
4  ··→··
5  →foreach·(var·x·in·xs)
6  →→sum··+=··x;
7  →return·sum;
8  }
```

Note anchored to `start = 3`, `end = 6`.

| Line | As written | After §6 | Kept? |
|---|---|---|---|
| 3 | `\tint sum = 0;` | `int sum = 0;` | yes |
| 4 | `  \t  ` | `` | no — §7.1 |
| 5 | `\tforeach (var x in xs)` | `foreach (var x in xs)` | yes |
| 6 | `\t\tsum  +=  x;` | `sum += x;` | yes |

Normalised content, with terminators written out:

```
int sum = 0;\nforeach (var x in xs)\nsum += x;
```

That is 44 bytes of UTF-8. `SHA-256` = `55046e...`, so **`anchor.hash` = `55046e`**. Reproduce with `python3 reference_hash.py` from this directory, which prints this example.

Same file re-indented to four spaces, with line 4 deleted and line 6 re-spaced to `sum += x;`, produces the same 44 bytes and the same `55046e`. Renaming `sum` to `total` does not.

## 10. Edge-case table

Every row is pinned by a vector in `vectors.json`.

| Case | Behaviour | Vector |
|---|---|---|
| Single line | ordinary | `single_line` |
| Blank line first / last / middle in range | discarded, no effect on hash | `blank_lines_leading`, `blank_lines_trailing`, `blank_lines_middle` |
| Line of only spaces, mid-range | discarded | `middle_whitespace_only_line` |
| Line of only a tab; line of only a form feed | discarded, same as an empty line | `only_whitespace_lines` |
| Tabs vs spaces indentation | same hash | `tabs_indent`, `spaces_indent_same_hash` |
| Internal run of tabs and spaces | collapses to one space | `collapse_internal_run`, `single_space_internal` |
| U+00A0 instead of a space | different hash (content, not whitespace) | `nbsp_is_content_differs` |
| Renamed identifier | different hash | `renamed_identifier_differs` |
| Statement reflowed across two lines | different hash | `statement_reflowed_differs` |
| `\r\n` endings | same hash as `\n` | `crlf_multi_line_same_hash` |
| Lone `\r` endings | same hash as `\n` | `lone_cr_multi_line_same_hash`, `lone_cr_non_ascii_same_hash` |
| No trailing newline at end of file | same hash | `no_trailing_newline_same_hash` |
| `end` past end of file | clamped, no error | `end_past_eof_clamped`, `end_one_past_eof_is_not_a_blank_line` |
| `start` past end of file | empty selection, hash `e3b0c4`, no error | `start_past_eof_empty` |
| Empty file | zero lines, hash `e3b0c4` | `empty_file` |
| Range of only blank lines | hash `e3b0c4` | `only_blank_lines`, `single_empty_line_range` |
| `start > end` | error `INVALID_RANGE` | `error_start_greater_than_end` |
| `start < 1` | error `START_BELOW_ONE` | `error_start_below_one` |
| Non-ASCII in a string literal | hashed as UTF-8 bytes, no normalisation | `non_ascii_string_literal`, `non_ascii_crlf_same_hash` |
| Invalid UTF-8 bytes | error `INVALID_ENCODING` | `reference_hash.py` self-check (`vectors.json` holds text, not bytes) |
| Leading UTF-8 byte-order mark | stripped, no effect on hash | `reference_hash.py` self-check |

## 11. Consequences

Stated so they are not discovered during W-13 review:

1. Re-indenting, converting tabs to spaces, and re-aligning trailing comments do not drift a note.
2. Inserting, deleting or blanking whole lines inside the range does not drift a note, as long as those lines are whitespace-only.
3. Renaming an identifier, changing a literal, and changing case do drift a note.
4. Reflowing one statement across a line boundary drifts a note, because §7 joins with `\n` and never with a space. This is the one behaviour a reader might expect to be whitespace-insensitive and is not; making it insensitive would require joining with a space, which would then make an unrelated two-line region collide with a one-line region carrying the same tokens.
5. Moving a region within a file does not change its hash, which is what makes W-6 step 3 (whole-document window sweep) able to re-anchor it.
6. Deleting the last line of a range without updating the note leaves a hash over fewer lines, which drifts. The clamp in §5 means it does not error.

## 12. Vector file format

`vectors.json` is a JSON array. Keys per object:

| Key | Type | Meaning |
|---|---|---|
| `name` | string | unique |
| `input_lines` | array of strings | one per line, **without** terminators |
| `line_ending` | `"\n"`, `"\r\n"` or `"\r"` | terminator used to build the file text |
| `trailing_newline` | boolean, optional, default `true` | whether the final line is terminated |
| `start`, `end` | integer | as requested, before any clamp |
| `expected_hash` | string or `null` | 6 lowercase hex characters; `null` for error cases |
| `expected_error` | string, present when `expected_hash` is `null` | error code from §2 or §5 |
| `same_as` / `differs_from` | string, optional | name of another vector whose hash must be equal / must differ |
| `note` | string | what the case pins down |

All non-ASCII and control characters in the file are written as JSON `\uXXXX` or short escapes, so no vector depends on an invisible character surviving a copy-paste.

File text is reconstructed as: empty string when `input_lines` is empty, otherwise `line_ending.join(input_lines)` plus one `line_ending` when `trailing_newline` is true. Both implementations must build it that way, then hash `[start, end]`.

`line_ending` is one of `"\n"`, `"\r\n"` or `"\r"`; all three are exercised, so the terminator rules in §4 are
machine-checked on both implementations rather than hand-ported.

One case still cannot be expressed in this format and lives only in `reference_hash.py`'s self-check: invalid UTF-8,
because `input_lines` holds already-decoded text. W-6 and W-11 must each assert the `INVALID_ENCODING` path by hand.
A leading byte-order mark is likewise self-check-only for the same reason.

## 13. Rules that bind the callers, not the hash

The hash function is total and never inspects intent. Three rules belong to its callers:

1. **The writer (W-11) must refuse to create a note whose normalised content is empty** — i.e. whose computed hash is `e3b0c4`. Such a note matches every empty window in every file, so W-6 step 3 would re-anchor it to the first blank line it sweeps past. The check is one comparison in the writer; the alternative is a note that resolves Solid at a meaningless location.
2. **The resolver (W-6) must skip any candidate window whose normalised content is empty** before comparing hashes, for the same reason from the other direction. Cheaper than special-casing `e3b0c4`, and it does not hard-code a constant.

3. **Neither caller may accept a normalised content with no alphanumeric character in it** — a lone `}`, `{`, `});`, `],`. Such content is non-empty, so rule 1 does not catch it, but it hashes identically at every occurrence: in a file with two methods, both closing braces hash to `d10b36` and both opening braces to `021fb5` (measured). A match therefore carries no information about location, and the resolver's sweep re-anchors the note **Solid** at the first such line it reaches — a confidently wrong location, which is a worse outcome than an orphan. The writer refuses to create the note; the resolver skips the candidate window. Found while writing the agent-side instructions (W-12): the instructions could ask an agent not to do this but could not make it hard, which is the signal that it belonged in the two implementations instead.

Neither rule changes any value in `vectors.json`; `only_blank_lines` and its siblings exist to pin the function's behaviour, not to describe a note the product should ever store.
