"""Reference implementation of HASHING.md, in this directory — the `.why` anchor hash.

Standard library only. This is the seed for W-11's production writer and the
oracle for W-6's Kotlin port; it deliberately has no command-line interface.

Read HASHING.md for the normative rules. This file is the executable copy.
"""

import hashlib
import re

# The whitespace set: the six ASCII whitespace code points, listed explicitly.
# Never use str.isspace() / Char.isWhitespace() / bare str.split() here -- the two
# languages disagree on Unicode (see HASHING.md section 3).
_WS_RUN = re.compile("[\t\n\v\f\r ]+")

BOM = "\ufeff"  # U+FEFF, written as an escape so it stays visible


class HashRangeError(ValueError):
    """Raised for a range the caller must not have asked for."""

    def __init__(self, code, message):
        super().__init__("%s: %s" % (code, message))
        self.code = code


def split_lines(text):
    """Split file text into logical lines (no terminators, no final empty line)."""
    if text.startswith(BOM):
        text = text[1:]
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if text == "":
        return []
    if text.endswith("\n"):
        text = text[:-1]
    return text.split("\n")


def normalise_line(line):
    """Trim ends, collapse internal whitespace runs to one space."""
    return " ".join(part for part in _WS_RUN.split(line) if part)


def normalise(lines, start, end):
    """Normalised content of 1-based inclusive range [start, end]."""
    if not isinstance(start, int) or not isinstance(end, int) or isinstance(start, bool) or isinstance(end, bool):
        raise HashRangeError("INVALID_RANGE", "start and end must be integers")
    if start < 1:
        raise HashRangeError("START_BELOW_ONE", "start=%d is less than 1" % start)
    if start > end:
        raise HashRangeError("INVALID_RANGE", "start=%d is greater than end=%d" % (start, end))
    kept = []
    for line in lines[start - 1:end]:  # slice clamps end to the file length
        norm = normalise_line(line)
        if norm:
            kept.append(norm)
    return "\n".join(kept)


def hash_text(text, start, end):
    """Anchor hash of [start, end] in already-decoded file text."""
    digest = hashlib.sha256(normalise(split_lines(text), start, end).encode("utf-8"))
    return digest.hexdigest()[:6]


def hash_bytes(data, start, end):
    """Anchor hash of [start, end] in raw file bytes. Strict UTF-8."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise HashRangeError("INVALID_ENCODING", "file is not valid UTF-8: %s" % exc)
    return hash_text(text, start, end)


EMPTY_HASH = hashlib.sha256(b"").hexdigest()[:6]


def _selfcheck():
    # line splitting
    assert split_lines("") == []
    assert split_lines("\n") == [""]
    assert split_lines("a") == ["a"]
    assert split_lines("a\n") == ["a"]
    assert split_lines("a\n\n") == ["a", ""]
    assert split_lines("a\r\nb\r\n") == ["a", "b"]
    assert split_lines("a\rb\r") == ["a", "b"]          # lone CR is a terminator
    assert split_lines("\ufeffa\n") == ["a"]            # BOM stripped

    # normalisation
    assert normalise_line("\tif (x) {  ") == "if (x) {"
    assert normalise_line("a\t\t \fb") == "a b"         # form feed is whitespace
    assert normalise_line("\f") == ""                    # form-feed-only line dropped
    assert normalise_line("a\u00a0b") == "a\u00a0b"      # NBSP is content
    assert normalise(["", "\t", "  ", "x"], 1, 4) == "x"

    # indentation-insensitive, identifier-sensitive
    tabs = "\tint n = 0;\n\treturn n;\n"
    spaces = "    int n = 0;\n    return n;\n"
    assert hash_text(tabs, 1, 2) == hash_text(spaces, 1, 2)
    assert hash_text(tabs, 1, 2) != hash_text(spaces.replace("n", "m"), 1, 2)

    # line-ending insensitive
    assert hash_text("a\nb\n", 1, 2) == hash_text("a\r\nb\r\n", 1, 2) == hash_text("a\rb\r", 1, 2)

    # trailing newline does not create a final line; end clamps
    assert hash_text("a\nb\n", 1, 2) == hash_text("a\nb", 1, 2) == hash_text("a\nb\n", 1, 99)

    # empty selections
    assert hash_text("a\n", 5, 6) == EMPTY_HASH
    assert hash_text("", 1, 1) == EMPTY_HASH
    assert hash_text("\n\t\n", 1, 2) == EMPTY_HASH

    # errors
    for start, end, code in ((2, 1, "INVALID_RANGE"), (0, 3, "START_BELOW_ONE"), (-1, -1, "START_BELOW_ONE")):
        try:
            hash_text("a\nb\n", start, end)
        except HashRangeError as exc:
            assert exc.code == code, (start, end, exc.code)
        else:
            raise AssertionError("expected %s for (%d, %d)" % (code, start, end))
    try:
        hash_bytes(b"\xff\xfe", 1, 1)
    except HashRangeError as exc:
        assert exc.code == "INVALID_ENCODING"
    else:
        raise AssertionError("expected INVALID_ENCODING")

    # the worked example in HASHING.md
    example = "public int Total(int[] xs)\n{\n\tint sum = 0;\n  \t  \n\tforeach (var x in xs)\n\t\tsum  +=  x;\n\treturn sum;\n}\n"
    assert normalise(split_lines(example), 3, 6) == "int sum = 0;\nforeach (var x in xs)\nsum += x;"
    print("worked example [3,6] ->", hash_text(example, 3, 6))
    print("empty-content hash ->", EMPTY_HASH)
    print("reference_hash.py self-check OK")


if __name__ == "__main__":
    _selfcheck()
