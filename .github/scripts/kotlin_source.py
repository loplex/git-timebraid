"""Where the comments are in a Kotlin file, for the checkers that read them or read around them.

Three checkers need this and they need it two ways round: check-dashes.py and check-links.py read
what a comment says, check-kdoc-links.py reads everything a comment is not. Both answers come from
one question -- where does each comment start and end -- so they are answered in one place.

Kotlin is scanned rather than searched, because the four openers it reads -- a string, a raw
string, a `//` and a `/*` -- each hide the other three, and the one that comes first
wins. Searching for them one at a time is how a ` -- ` inside a user-facing message gets read as
the comment beside it, how a `//` inside a URL literal gets read as a comment and its path reported
as a broken pointer, and how a `/*` written inside a `//` comment turns the rest of the file into
one long comment.

A raw string's body is skipped like an ordinary string's: what triple quotes hold is data, not
prose. Read as code instead, a `//` in one would be reported as a comment and a `/*` in one would
open a block comment running to the end of the file -- the same confusions as above, reached
through the fourth opener rather than the first three.

An ordinary string ends at the newline if nothing closes it, because Kotlin has no line
continuation in one; a block comment or a raw string that nothing closes runs to the end of the
file, which is what the compiler does with it too.

It is not a Kotlin lexer, and two of the places where it falls short are worth knowing. A
character literal is not read as one, so `'"'` opens a string that hides the rest of its line.
And a block comment ends at its first `*/`, where Kotlin nests them: `/* a /* b */ c */` hands
` c */` back as code.
"""


def comment_spans(body: str) -> list[tuple[int, int, int, int]]:
    """Every comment as `(start, end, text_start, text_end)`, in source order.

    The first pair spans the comment as written, delimiters included, which is what has to be cut
    away to leave code. The second spans what it says: a `//` comment carries its marker, because
    that is how the text reads to the checkers that quote it, and a block comment does not.
    """
    spans: list[tuple[int, int, int, int]] = []
    i, n = 0, len(body)
    while i < n:
        two, three = body[i : i + 2], body[i : i + 3]
        if three == '"""':
            end = body.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif body[i] == '"':
            i += 1
            while i < n and body[i] != "\n":
                if body[i] == "\\":
                    i += 2
                elif body[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
        elif two == "//":
            end = body.find("\n", i)
            end = n if end < 0 else end
            spans.append((i, end, i, end))
            i = end
        elif two == "/*":
            end = body.find("*/", i + 2)
            if end < 0:
                spans.append((i, n, i + 2, n))
                i = n
            else:
                spans.append((i, end + 2, i + 2, end))
                i = end + 2
        else:
            i += 1
    return spans


def comments(body: str):
    """Comment text per line, as `(line number, text)`, several on one line joined by a space."""
    starts = [0]
    for i, ch in enumerate(body):
        if ch == "\n":
            starts.append(i + 1)

    per_line: dict[int, list[str]] = {}
    for _, _, text_start, text_end in comment_spans(body):
        line = _line_of(starts, text_start)
        at = text_start
        while at < text_end:
            ends_at = starts[line] if line < len(starts) else len(body) + 1
            cut = min(text_end, ends_at - 1) if line < len(starts) else text_end
            per_line.setdefault(line, []).append(body[at:cut])
            at, line = cut + 1, line + 1
    for line in sorted(per_line):
        yield line, " ".join(per_line[line])


def code(body: str) -> str:
    """The source with every comment blanked out, keeping every offset and every line number."""
    out = list(body)
    for start, end, _, _ in comment_spans(body):
        for i in range(start, end):
            if out[i] != "\n":
                out[i] = " "
    return "".join(out)


def _line_of(starts: list[int], offset: int) -> int:
    lo, hi = 0, len(starts) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if starts[mid] <= offset:
            lo = mid
        else:
            hi = mid - 1
    return lo + 1
