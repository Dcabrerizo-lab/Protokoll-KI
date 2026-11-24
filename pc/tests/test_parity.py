"""Prompts und Grammatiken des PC-Programms müssen wortgleich mit der Android-App sein."""
import re
from pathlib import Path

from pkpc import generator as g

KT = (Path(__file__).resolve().parents[2] / "app/src/main/java/de/protokollki/engine/ProtocolGenerator.kt").read_text("utf-8")


def _unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\":
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"', "$": "$", "'": "'"}[n])
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _expr(src: str) -> str:
    """Wertet einen Kotlin-Ausdruck aus String-Literalen, + und .trimIndent() aus."""
    parts, i = [], 0
    while i < len(src):
        if src.startswith('"""', i):
            j = src.index('"""', i + 3)
            raw = src[i + 3:j]
            i = j + 3
            if src.startswith(".trimIndent()", i):
                lines = raw.split("\n")
                if lines and not lines[0].strip():
                    lines = lines[1:]
                if lines and not lines[-1].strip():
                    lines = lines[:-1]
                raw = "\n".join(lines)
                i += len(".trimIndent()")
            parts.append(raw)
        elif src[i] == '"':
            j = i + 1
            while src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            parts.append(_unescape(src[i + 1:j]))
            i = j + 1
        elif src.startswith("JSON_STRING", i):
            parts.append(_const("JSON_STRING"))
            i += len("JSON_STRING")
        else:
            i += 1
    return "".join(parts)


def _const(name: str) -> str:
    m = re.search(rf"val {name} =(.*?)(?=\n\s*(?:private |internal )?(?:const )?(?:val|fun) |\n\s*private val ADDRESS)", KT, re.S)
    assert m, name
    return _expr(m.group(1))


def test_prompts_identical():
    for name in ["SYSTEM", "NOTES_PROMPT", "CITE_RULE", "TASK_RULES", "AGENDA_RULE", "FOLLOWUP_RULE",
                 "SUMMARY_PROMPT", "TASKS_PROMPT"]:
        assert getattr(g, name) == _const(name), name


def test_grammars_identical():
    assert g.JSON_STRING == _const("JSON_STRING")
    assert g.SUMMARY_GRAMMAR == _const("SUMMARY_GRAMMAR")
    assert g.TASKS_GRAMMAR == _const("TASKS_GRAMMAR")


def test_template_rules_identical():
    for name, rule in g.TEMPLATE_RULES.items():
        m = re.search(rf"ProtocolTemplate\.{name} -> (\"\"\".*?\"\"\")", KT, re.S)
        assert m, name
        assert rule == _expr(m.group(1)), name
