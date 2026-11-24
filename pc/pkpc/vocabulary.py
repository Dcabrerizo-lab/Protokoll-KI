"""Eigenes Wörterbuch: phonetische Korrektur des Transkripts (Port von engine/Vocabulary.kt)."""
from __future__ import annotations

import re
from dataclasses import dataclass

WORD = re.compile(r"[^\W_][\w'’-]*")

PROTECTED = {
    "aber", "alle", "also", "auch", "dann", "dass", "denn", "dies", "diese", "dieser", "doch",
    "eine", "einen", "einer", "haben", "heute", "hier", "jetzt", "kann", "können", "machen",
    "mehr", "noch", "oder", "schon", "sein", "sind", "unser", "wenn", "werden", "wieder", "wird",
    "woche", "morgen", "gerne", "genau", "danke", "bitte", "termin", "frage", "fragen", "gut",
    "mache", "habe", "hatte", "würde", "sollte", "müssen", "nicht", "nichts", "etwas", "ganz",
}

NO, PLAIN, GENITIVE = 0, 1, 2


@dataclass
class _Entry:
    term: str
    words: int
    norm: str
    code: str


def clean_terms(terms: list[str]) -> list[str]:
    seen, out = set(), []
    for t in terms:
        t = t.strip()
        if len(t) >= 2 and t.lower() not in seen:
            seen.add(t.lower())
            out.append(t)
    return out


def norm(s: str) -> str:
    return "".join(c for c in s.lower().replace("ß", "ss") if c.isalnum())


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(b)]


def cologne_phonetic(inp: str) -> str:
    s = inp.lower().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "s")
    s = "".join(c for c in s if "a" <= c <= "z")
    if not s:
        return ""
    codes = []
    for i, c in enumerate(s):
        prev = s[i - 1] if i > 0 else " "
        nxt = s[i + 1] if i < len(s) - 1 else " "
        if c in "aeijouy":
            code = "0"
        elif c == "h":
            code = ""
        elif c == "b":
            code = "1"
        elif c == "p":
            code = "3" if nxt == "h" else "1"
        elif c in "dt":
            code = "8" if nxt in "csz" else "2"
        elif c in "fvw":
            code = "3"
        elif c in "gkq":
            code = "4"
        elif c == "c":
            if i == 0:
                code = "4" if nxt in "ahkloqrux" else "8"
            elif prev in "sz":
                code = "8"
            elif nxt in "ahkoqux":
                code = "4"
            else:
                code = "8"
        elif c == "x":
            code = "8" if prev in "ckq" else "48"
        elif c == "l":
            code = "5"
        elif c in "mn":
            code = "6"
        elif c == "r":
            code = "7"
        elif c in "sz":
            code = "8"
        else:
            code = ""
        codes.append(code)
    dedup = []
    for ch in "".join(codes):
        if not dedup or dedup[-1] != ch:
            dedup.append(ch)
    if not dedup:
        return ""
    return dedup[0] + "".join(ch for ch in dedup[1:] if ch != "0")


def _fuzzy(span: str, n: str, e: _Entry) -> bool:
    dist = levenshtein(n, e.norm)
    ratio = dist / max(len(n), len(e.norm), 1)
    if dist > 4:
        return False
    if cologne_phonetic(span) == e.code:
        return dist <= 1 if len(e.norm) < 5 else ratio <= 0.4
    return len(e.norm) >= 8 and ratio <= 0.15


def _match(span: str, e: _Entry) -> int:
    n = norm(span)
    if n == e.norm:
        return PLAIN if span != e.term else NO
    if e.words == 1 and n in PROTECTED:
        return NO
    if _fuzzy(span, n, e):
        return PLAIN
    if n.endswith("s") and not e.norm.endswith("s") and len(n) > 4:
        base = n[:-1]
        if base == e.norm:
            return GENITIVE if span[:-1] != e.term else NO
        if _fuzzy(span[:-1], base, e):
            return GENITIVE
    return NO


def correct(text: str, terms: list[str]) -> str:
    if not text.strip() or not terms:
        return text
    entries = []
    for t in terms:
        words = WORD.findall(t)
        if words and len(t) >= 4:
            entries.append(_Entry(t, len(words), norm(t), cologne_phonetic(t)))
    entries.sort(key=lambda e: -e.words)
    if not entries:
        return text
    tokens = list(WORD.finditer(text))
    out, pos, i = [], 0, 0
    while i < len(tokens):
        replaced = False
        for e in entries:
            if i + e.words > len(tokens):
                continue
            first, last = tokens[i], tokens[i + e.words - 1]
            span = text[first.start():last.end()]
            if e.words > 1 and any(c in ".,;:!?" for c in span):
                continue
            m = _match(span, e)
            if m != NO:
                out.append(text[pos:first.start()])
                out.append(e.term)
                if m == GENITIVE:
                    out.append("s")
                pos = last.end()
                i += e.words
                replaced = True
                break
        if not replaced:
            i += 1
    out.append(text[pos:])
    return "".join(out)
