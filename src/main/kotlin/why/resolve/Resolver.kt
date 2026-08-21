package why.resolve

import why.model.Note
import why.model.Resolution
import why.model.Resolved

/**
 * Resolves one note against the current text of its file: the five-step order in
 * `PLAN.md` section 4, hashing per `skills/why-notes/writer/HASHING.md`.
 *
 * | Step | Attempt | Result |
 * |---|---|---|
 * | 1 | hash the stored window `[start, end]` | match -> SOLID at the stored range |
 * | 2 | text-search the last dot-separated segment of `anchor.symbol`, hash `K +/- 3` windows at each hit | match -> SOLID, re-anchored |
 * | 3 | sweep every line offset with the same window set | match -> SOLID, re-anchored |
 * | 4 | symbol found, or the stored range still in bounds, but no match | DRIFTED at that range |
 * | 5 | neither | ORPHANED, no range |
 *
 * `anchor.symbol` is a text-search hint and nothing more: no Program Structure
 * Interface, no `LineMarkerProvider`, no language-specific handling. Input is a
 * `String`, not a `Document`, so this is testable without an IDE fixture.
 *
 * R6.2.2: an orphaned note is returned as [Resolution.ORPHANED], never dropped.
 */
object Resolver {

    /** `PLAN.md` section 4: windows of K, K+/-1, K+/-2, K+/-3 lines. */
    private const val SLACK = 3

    fun resolve(note: Note, text: String): Resolved {
        val raw = Anchoring.splitLines(text)
        // Normalise once per document; steps 1 to 3 hash overlapping windows out of this.
        val norm = raw.map { Anchoring.normaliseLine(it) }
        val anchor = note.anchor
        val lineCount = norm.size

        // A stored anchor could be malformed (start < 1, start > end); the hash function
        // rejects those, so screen them here rather than throwing out of the resolver.
        val storedRangeIsSane = anchor.start >= 1 && anchor.start <= anchor.end

        // Step 1 -- the window as stored.
        if (storedRangeIsSane) {
            matchAt(norm, anchor.start, anchor.end, anchor.hash)?.let { return solid(note, it) }
        }

        val windowLengths = windowLengths(if (storedRangeIsSane) anchor.end - anchor.start + 1 else 1)

        // Step 2 -- lines mentioning the symbol's last segment, cheapest re-anchor.
        val hits = symbolHitLines(raw, anchor.symbol)
        for (line in hits) {
            for (len in windowLengths) {
                matchAt(norm, line, line + len - 1, anchor.hash)?.let { return solid(note, it) }
            }
        }

        // Step 3 -- whole-document sweep, which is what makes a moved-and-renamed region
        // still resolve, since moving a region does not change its hash.
        // ponytail: ceiling is 7 x lines hash computations per unresolved note (7,000 for a
        // 1,000-line file), and it runs only when steps 1 and 2 both fail. Upgrade path when
        // that shows up in a profile: prefilter offsets whose normalised first line differs
        // from the note's, or hoist this sweep to once per document so M notes in one file
        // cost one pass instead of M.
        for (line in 1..lineCount) {
            for (len in windowLengths) {
                matchAt(norm, line, line + len - 1, anchor.hash)?.let { return solid(note, it) }
            }
        }

        // Step 4 -- located but changed. The stored range wins when it is still in bounds;
        // otherwise the note is anchored to the first line mentioning the symbol.
        //
        // "still within the file" is read as the WHOLE stored range existing, `end <= lines`,
        // not merely overlapping it. PLAN.md section 4 does not say which; this reading is
        // what makes a file truncated past the range report ORPHANED ("cannot be located",
        // Records.kt) instead of DRIFTED pointing at a clamped line that no longer relates
        // to the note. A region deleted out of the middle of a longer file still reports
        // DRIFTED at its old range, which is the navigable answer.
        val storedRangeInBounds = storedRangeIsSane && anchor.end <= lineCount
        if (storedRangeInBounds) {
            return Resolved(note, Resolution.DRIFTED, anchor.start, minOf(anchor.end, lineCount))
        }
        if (hits.isNotEmpty()) {
            val start = hits.first()
            return Resolved(note, Resolution.DRIFTED, start, minOf(start + windowLengths.first() - 1, lineCount))
        }

        // Step 5.
        return Resolved(note, Resolution.ORPHANED, null, null)
    }

    private fun solid(note: Note, range: IntRange) =
        Resolved(note, Resolution.SOLID, range.first, range.last)

    /** K first, then the nearest slack values, so an exact-length match wins over a padded one. */
    private fun windowLengths(k: Int): List<Int> {
        val base = maxOf(1, k)
        val lengths = ArrayList<Int>(2 * SLACK + 1)
        lengths.add(base)
        for (d in 1..SLACK) {
            if (base - d >= 1) lengths.add(base - d)
            lengths.add(base + d)
        }
        return lengths
    }

    /**
     * The clamped range if this candidate window matches, else null.
     *
     * HASHING.md section 13 rule 2: a window whose normalised content is empty is skipped
     * before any hash comparison. Without this, a note whose own content normalises to
     * empty re-anchors SOLID onto the first blank line the sweep reaches. Tested by
     * emptiness of the content, not by comparing against the constant `e3b0c4`.
     */
    private fun matchAt(norm: List<String>, start: Int, end: Int, hash: String): IntRange? {
        if (start < 1 || start > norm.size) return null
        val content = Anchoring.joinNormalised(norm, start, end) // end past end of file clamps
        // Rule 2: empty content matches every blank window.
        if (content.isEmpty()) return null
        // Rule 3: content with no identifier in it -- a lone `}`, `{`, `});` -- hashes
        // identically everywhere it occurs, so a match carries no information about
        // location. Skipping makes such a note Drifted or Orphaned rather than
        // confidently anchored to an arbitrary brace.
        if (content.none { it.isLetterOrDigit() }) return null
        if (Anchoring.hashContent(content) != hash) return null
        return start..minOf(end, norm.size)
    }

    /** 1-based lines containing the last dot-separated segment of [symbol]. Plain substring search. */
    private fun symbolHitLines(raw: List<String>, symbol: String?): List<Int> {
        val needle = symbol?.substringAfterLast('.')
        if (needle.isNullOrEmpty()) return emptyList()
        return raw.indices.filter { raw[it].contains(needle) }.map { it + 1 }
    }
}
