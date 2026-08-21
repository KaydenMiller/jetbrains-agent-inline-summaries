"""Run reference_hash.py over every vector in vectors.json. Exit non-zero on any mismatch.

Usage: python3 skills/why-notes/writer/verify_vectors.py [path/to/vectors.json]

Both implementations (W-6 Kotlin, W-11 Python writer) must reproduce this output.
"""

import json
import os
import sys

from reference_hash import HashRangeError, hash_text, split_lines

HERE = os.path.dirname(os.path.abspath(__file__))


def build_text(vector):
    """Reconstruct the file text a vector describes. See HASHING.md section 9."""
    lines = vector["input_lines"]
    ending = vector["line_ending"]
    trailing = vector.get("trailing_newline", True)
    if not lines:
        return ""
    return ending.join(lines) + (ending if trailing else "")


def check(vector):
    """Return None if the vector passes, else a failure message."""
    text = build_text(vector)
    try:
        actual = hash_text(text, vector["start"], vector["end"])
    except HashRangeError as exc:
        actual, code = None, exc.code
    else:
        code = None

    if vector["expected_hash"] is None:
        want = vector.get("expected_error")
        if code != want:
            return "expected error %s, got %s" % (want, code or "hash " + actual)
        return None
    if code is not None:
        return "expected hash %s, got error %s" % (vector["expected_hash"], code)
    if actual != vector["expected_hash"]:
        return "expected hash %s, got %s" % (vector["expected_hash"], actual)
    if len(actual) != 6 or actual != actual.lower() or any(c not in "0123456789abcdef" for c in actual):
        return "hash %r is not 6 lowercase hex characters" % actual
    return None


def check_line_splitting():
    """Section 4's splitting table. No hash vector can catch a violation of it: a phantom
    trailing line normalises to empty and is discarded by section 7, so every hash stays
    identical while the line COUNT diverges. Both implementations must run these."""
    path = os.path.join(HERE, "line_split_cases.json")
    with open(path, encoding="utf-8") as fh:
        cases = json.load(fh)
    failures = []
    for case in cases:
        actual = split_lines(case["text"])
        ok = actual == case["lines"]
        print("%s %-38s %d line(s) %s" % ("ok  " if ok else "FAIL", case["name"], len(actual),
                                          "" if ok else "expected %r, got %r" % (case["lines"], actual)))
        if not ok:
            failures.append(case["name"])
    print("\n%d line-split cases, %d failed" % (len(cases), len(failures)))
    return failures


def main(path):
    with open(path, encoding="utf-8") as fh:
        vectors = json.load(fh)
    by_name = {v["name"]: v for v in vectors}
    if len(by_name) != len(vectors):
        print("FAIL duplicate vector names")
        return 1

    failures = []
    for vector in vectors:
        problem = check(vector)
        # Relational claims: these are the whole point of the paired vectors.
        for key, want_equal in (("same_as", True), ("differs_from", False)):
            other = vector.get(key)
            if other is None:
                continue
            if other not in by_name:
                problem = problem or "%s names unknown vector %s" % (key, other)
                continue
            equal = vector["expected_hash"] == by_name[other]["expected_hash"]
            if equal is not want_equal:
                problem = problem or "%s %s violated" % (key, other)
        status = "FAIL" if problem else "ok  "
        print("%s %-38s %s %s" % (status, vector["name"], vector["expected_hash"] or vector.get("expected_error"),
                                  problem or ""))
        if problem:
            failures.append(vector["name"])

    split_failures = check_line_splitting()
    failures += split_failures

    print("\n%d vectors, %d failed" % (len(vectors), len(failures)))
    if failures:
        print("failed: " + ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "vectors.json")))
