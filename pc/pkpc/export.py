"""Protokoll als Word, PDF, Markdown und Text (Aufbau wie export/ExportContent.kt der App)."""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from .model import PROTOCOL_MODES, Meeting, template_of
from .paths import resource

FOOTER_NOTE = "Erstellt mit Protokoll KI (KI-gestützt, vor Weitergabe bitte prüfen) – vollständig offline auf diesem Gerät verarbeitet."
SIGNATURE_LINE = "______________________________"
SIGNATURE_PLACE_DATE = "Ort, Datum"
MINUTE_TAKER_ROLE = "Protokollführung"
TASK_HEADER = ["Nr.", "Aufgabe", "Verantwortlich", "Frist", "Erledigt"]
TASK_WEIGHTS = [0.07, 0.45, 0.2, 0.16, 0.12]
FOLLOWUP_HEADER = ["Aufgabe", "Verantwortlich", "Stand"]
FOLLOWUP_WEIGHTS = [0.58, 0.24, 0.18]


@dataclass
class Block:
    kind: str                       # title meta divider heading sub para bullet num table utter note sign
    text: str = ""
    label: str = ""
    number: int = 0
    header: list[str] = field(default_factory=list)
    rows: list[list[str]] = field(default_factory=list)
    weights: list[float] = field(default_factory=list)
    lines: list[tuple[str, str]] = field(default_factory=list)


def participants(m: Meeting) -> list[str]:
    ids = list(dict.fromkeys(s.speaker_id for s in m.segments))
    names = [m.speaker_name(i) for i in ids] or [s.name for s in m.speakers]
    return list(dict.fromkeys(n for n in names if n.strip()))


def _dt(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000)


def format_date(ms: int) -> str:
    return f"{_dt(ms):%d.%m.%Y, %H:%M} Uhr"


def format_duration(sec: float) -> str:
    total = 0 if sec != sec or sec < 0 else round(sec)
    if total < 60:
        return f"{total} Sek."
    h, mi = total // 3600, (total % 3600) // 60
    return f"{h} Std. {mi} Min." if h else f"{mi} Min."


def format_timestamp(sec: float) -> str:
    total = 0 if sec != sec or sec < 0 else int(sec)
    h, m, s = total // 3600, (total % 3600) // 60, total % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def sanitize_file_name(title: str) -> str:
    c = re.sub(r"\s+", " ", re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", title)).strip().strip("._ ")
    return (c[:80].strip() if len(c) > 80 else c) or "Protokoll"


def chair_role(template: str) -> str:
    return next((s for s in template_of(template).signatures if s != MINUTE_TAKER_ROLE), "Leitung")


def build_blocks(m: Meeting, organization: str = "") -> list[Block]:
    b: list[Block] = []
    tpl = template_of(m.template)
    formal = tpl.name != "ALLGEMEIN"
    title = m.title or "Besprechung"
    if formal:
        b.append(Block("title", title, label=tpl.document_title))
        start = _dt(m.created_at)
        rng = f"{start:%H:%M} Uhr"
        if m.duration_sec >= 60:
            rng = f"{start:%H:%M} – {start + timedelta(seconds=round(m.duration_sec)):%H:%M} Uhr"
        for label, value in [("Einrichtung", organization), ("Datum", f"{start:%d.%m.%Y}"),
                             ("Uhrzeit", rng), ("Ort", m.location), (chair_role(m.template), m.chair),
                             (MINUTE_TAKER_ROLE, m.minute_taker),
                             ("Teilnehmende", ", ".join(participants(m)))]:
            if value.strip():
                b.append(Block("meta", value.strip(), label=label))
    else:
        b.append(Block("title", title, label=PROTOCOL_MODES.get(m.mode, "Ergebnisprotokoll")))
        b.append(Block("meta", format_date(m.created_at), label="Datum"))
        b.append(Block("meta", format_duration(m.duration_sec), label="Dauer"))
        b.append(Block("meta", ", ".join(participants(m)) or "–", label="Teilnehmende"))
    b.append(Block("divider"))

    agenda = [a.strip() for a in m.agenda if a.strip()]
    if agenda:
        b.append(Block("heading", "Tagesordnung"))
        b += [Block("num", a, number=i + 1) for i, a in enumerate(agenda)]

    def tasks():
        b.append(Block("heading", "Aufgaben"))
        if not m.tasks:
            b.append(Block("para", "Keine Aufgaben erfasst."))
        else:
            rows = [[str(i + 1), t.text, t.owner or "offen", t.due or "offen", "✓" if t.done else ""]
                    for i, t in enumerate(m.tasks)]
            b.append(Block("table", header=TASK_HEADER, rows=rows, weights=TASK_WEIGHTS))
        follow = [[f.text.strip(), f.owner.strip() or "offen", "erledigt" if f.done else "offen"]
                  for f in m.follow_ups if f.text.strip()]
        if follow:
            b.append(Block("heading", "Stand offener Aufgaben aus früheren Besprechungen"))
            b.append(Block("table", header=FOLLOWUP_HEADER, rows=follow, weights=FOLLOWUP_WEIGHTS))

    if m.mode == "ERGEBNIS":
        s = m.summary
        b.append(Block("heading", "Zusammenfassung"))
        b.append(Block("para", s.overview.strip() or "Noch keine Zusammenfassung vorhanden."))
        b.append(Block("heading", "Besprochene Themen"))
        if not s.topics:
            b.append(Block("para", "Keine"))
        for t in s.topics:
            b.append(Block("sub", t.title or "Thema"))
            b += [Block("bullet", p.strip()) for p in t.points if p.strip()]
        b.append(Block("heading", "Beschlüsse"))
        dec = [d.strip() for d in s.decisions if d.strip()]
        b += [Block("num", d, number=i + 1) for i, d in enumerate(dec)] or [Block("para", "Keine")]
        b.append(Block("heading", "Offene Punkte"))
        op = [d.strip() for d in s.open_points if d.strip()]
        b += [Block("bullet", d) for d in op] or [Block("para", "Keine")]
        tasks()
    else:
        tasks()
        b.append(Block("heading", "Wortlaut"))
        if not m.segments:
            b.append(Block("para", "Kein Transkript vorhanden."))
        for seg in m.segments:
            if seg.text.strip():
                b.append(Block("utter", seg.text.strip(), label=m.speaker_name(seg.speaker_id),
                               number=int(seg.start)))
    if formal and tpl.signatures:
        chair = chair_role(m.template)
        lines = []
        for role in tpl.signatures:
            name = m.minute_taker if role == MINUTE_TAKER_ROLE else (m.chair if role == chair else "")
            lines.append((role, name.strip()))
        b.append(Block("sign", lines=lines))
    b.append(Block("note", FOOTER_NOTE))
    return b


def _sig_label(role: str, name: str) -> str:
    return f"{role} ({name})" if name else role


# ------------------------------------------------------------------ Markdown / Text

def _md(s: str) -> str:
    out = []
    for c in s:
        if c in "\\*_`[]<>#|":
            out.append("\\" + c)
        elif c in "\r\n":
            out.append(" ")
        else:
            out.append(c)
    return "".join(out)


def render_markdown(blocks: list[Block]) -> str:
    out: list[str] = []
    for bl in blocks:
        k = bl.kind
        if k == "title":
            out.append(f"# {_md(bl.label)}: {_md(bl.text)}\n\n")
        elif k == "meta":
            out.append(f"**{_md(bl.label)}:** {_md(bl.text)}  \n")
        elif k == "divider":
            out.append("\n---\n\n")
        elif k == "heading":
            out.append(f"\n## {_md(bl.text)}\n\n")
        elif k == "sub":
            out.append(f"\n### {_md(bl.text)}\n\n")
        elif k == "para":
            out.append(f"{_md(bl.text)}\n\n")
        elif k == "bullet":
            out.append(f"- {_md(bl.text)}\n")
        elif k == "num":
            out.append(f"{bl.number}. {_md(bl.text)}\n")
        elif k == "table":
            out.append("\n| " + " | ".join(_md(h) for h in bl.header) + " |\n")
            out.append("|" + "|".join("---" for _ in bl.header) + "|\n")
            out += ["| " + " | ".join(_md(c) for c in r) + " |\n" for r in bl.rows]
            out.append("\n")
        elif k == "utter":
            out.append(f"**{_md(bl.label)}** [{format_timestamp(bl.number)}]: {_md(bl.text)}\n\n")
        elif k == "sign":
            for role, name in bl.lines:
                out.append(f"\n{_md(SIGNATURE_LINE)}  \n{SIGNATURE_PLACE_DATE}  \n**{_md(role)}**"
                           + (f" ({_md(name)})" if name else "") + "\n\n")
        elif k == "note":
            out.append(f"\n---\n\n*{_md(bl.text)}*\n")
    return re.sub(r"\n{3,}", "\n\n", "".join(out))


# ------------------------------------------------------------------ Word

def write_docx(blocks: list[Block], path: Path) -> Path:
    from docx import Document
    from docx.enum.table import WD_TABLE_ALIGNMENT
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Cm, Pt, RGBColor

    accent = RGBColor(0x1F, 0x4E, 0x79)
    grey = RGBColor(0x6B, 0x72, 0x80)
    doc = Document()
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)
    sec.left_margin = sec.right_margin = Cm(2.2)
    sec.top_margin = sec.bottom_margin = Cm(2.0)
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)
    for st, size in (("Heading 1", 13.5), ("Heading 2", 11.5)):
        doc.styles[st].font.name = "Calibri"
        doc.styles[st].font.size = Pt(size)
        doc.styles[st].font.color.rgb = accent

    def shade(cell, hex_color: str):
        tcPr = cell._tc.get_or_add_tcPr()
        shd = OxmlElement("w:shd")
        shd.set(qn("w:val"), "clear")
        shd.set(qn("w:color"), "auto")
        shd.set(qn("w:fill"), hex_color)
        tcPr.append(shd)

    width = sec.page_width - sec.left_margin - sec.right_margin
    for bl in blocks:
        k = bl.kind
        if k == "title":
            p = doc.add_paragraph()
            r = p.add_run(bl.label.upper())
            r.font.size, r.font.bold, r.font.color.rgb = Pt(9.5), True, accent
            p.paragraph_format.space_after = Pt(0)
            p = doc.add_paragraph()
            r = p.add_run(bl.text)
            r.font.size, r.font.bold = Pt(20), True
        elif k == "meta":
            p = doc.add_paragraph()
            p.paragraph_format.space_after = Pt(1)
            r = p.add_run(bl.label + ": ")
            r.bold, r.font.color.rgb = True, grey
            p.add_run(bl.text)
        elif k == "divider":
            p = doc.add_paragraph()
            pPr = p._p.get_or_add_pPr()
            bdr = OxmlElement("w:pBdr")
            bottom = OxmlElement("w:bottom")
            for a, v in (("w:val", "single"), ("w:sz", "6"), ("w:space", "1"), ("w:color", "C7CDD6")):
                bottom.set(qn(a), v)
            bdr.append(bottom)
            pPr.append(bdr)
        elif k == "heading":
            doc.add_heading(bl.text, level=1)
        elif k == "sub":
            doc.add_heading(bl.text, level=2)
        elif k == "para":
            doc.add_paragraph(bl.text)
        elif k == "bullet":
            doc.add_paragraph(bl.text, style="List Bullet")
        elif k == "num":
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.6)
            p.paragraph_format.first_line_indent = Cm(-0.6)
            p.add_run(f"{bl.number}.\t").bold = True
            p.add_run(bl.text)
        elif k == "table":
            t = doc.add_table(rows=1, cols=len(bl.header))
            t.style = "Table Grid"
            t.alignment = WD_TABLE_ALIGNMENT.CENTER
            for i, h in enumerate(bl.header):
                c = t.rows[0].cells[i]
                c.text = ""
                r = c.paragraphs[0].add_run(h)
                r.bold = True
                r.font.size = Pt(10)
                shade(c, "E8EEF5")
            for row in bl.rows:
                cells = t.add_row().cells
                for i, v in enumerate(row):
                    cells[i].text = ""
                    cells[i].paragraphs[0].add_run(v).font.size = Pt(10)
            for row in t.rows:
                for i, w in enumerate(bl.weights):
                    row.cells[i].width = int(width * w)
            doc.add_paragraph()
        elif k == "utter":
            p = doc.add_paragraph()
            r = p.add_run(bl.label + " ")
            r.bold = True
            r = p.add_run(f"[{format_timestamp(bl.number)}]  ")
            r.font.size, r.font.color.rgb = Pt(9), grey
            p.add_run(bl.text)
        elif k == "sign":
            for role, name in bl.lines:
                doc.add_paragraph()
                doc.add_paragraph()
                doc.add_paragraph(SIGNATURE_LINE).paragraph_format.space_after = Pt(0)
                p = doc.add_paragraph()
                r = p.add_run(SIGNATURE_PLACE_DATE + "\n")
                r.font.size, r.font.color.rgb = Pt(9), grey
                p.add_run(_sig_label(role, name))
        elif k == "note":
            doc.add_paragraph()
            p = doc.add_paragraph()
            r = p.add_run(bl.text)
            r.italic, r.font.size, r.font.color.rgb = True, Pt(8.5), grey
    path = Path(path)
    tmp = path.with_suffix(".tmp.docx")
    doc.save(tmp)
    tmp.replace(path)
    return path


# ------------------------------------------------------------------ PDF

def _font_files() -> tuple[str, str, str] | None:
    cands = [
        (resource("fonts/DejaVuSans.ttf"), resource("fonts/DejaVuSans-Bold.ttf"), resource("fonts/DejaVuSans-Oblique.ttf")),
        (Path("C:/Windows/Fonts/arial.ttf"), Path("C:/Windows/Fonts/arialbd.ttf"), Path("C:/Windows/Fonts/ariali.ttf")),
        (Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
         Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf")),
        (Path("/usr/share/fonts/truetype/freefont/FreeSans.ttf"), Path("/usr/share/fonts/truetype/freefont/FreeSansBold.ttf"),
         Path("/usr/share/fonts/truetype/freefont/FreeSansOblique.ttf")),
    ]
    for c in cands:
        if all(p.exists() for p in c):
            return tuple(str(p) for p in c)  # type: ignore[return-value]
    return None


def write_pdf(blocks: list[Block], path: Path, title: str) -> Path:
    from fpdf import FPDF, FontFace
    from fpdf.enums import XPos, YPos

    fonts = _font_files()

    class Doc(FPDF):
        def footer(self):
            self.set_y(-12)
            self.set_font(F, "", 8)
            self.set_text_color(120, 120, 120)
            self.cell(0, 6, f"{title} – Seite {self.page_no()}/{{nb}}", align="C")

    pdf = Doc(format="A4")
    if fonts:
        pdf.add_font("pk", "", fonts[0])
        pdf.add_font("pk", "B", fonts[1])
        pdf.add_font("pk", "I", fonts[2])
        F = "pk"
    else:
        F = "helvetica"
    check = "✓" if fonts and "dejavu" in fonts[0].lower() else "ja"

    def txt(s: str) -> str:
        if fonts:
            return s
        return s.encode("latin-1", "replace").decode("latin-1")

    pdf.set_margins(20, 18, 20)
    pdf.set_auto_page_break(True, 18)
    pdf.set_title(title)
    pdf.set_creator("Protokoll KI")
    pdf.add_page()
    W = pdf.w - pdf.l_margin - pdf.r_margin
    accent = (31, 78, 121)
    nl = {"new_x": XPos.LMARGIN, "new_y": YPos.NEXT}

    for bl in blocks:
        k = bl.kind
        pdf.set_text_color(20, 20, 20)
        if k == "title":
            pdf.set_font(F, "B", 9)
            pdf.set_text_color(*accent)
            pdf.cell(W, 5, txt(bl.label.upper()), **nl)
            pdf.set_text_color(20, 20, 20)
            pdf.set_font(F, "B", 18)
            pdf.multi_cell(W, 8, txt(bl.text), **nl)
            pdf.ln(2)
        elif k == "meta":
            pdf.set_font(F, "B", 10)
            pdf.set_text_color(107, 114, 128)
            lw = pdf.get_string_width(txt(bl.label + ": ")) + 1
            pdf.cell(lw, 5.5, txt(bl.label + ": "))
            pdf.set_font(F, "", 10)
            pdf.set_text_color(20, 20, 20)
            pdf.multi_cell(W - lw, 5.5, txt(bl.text), **nl)
        elif k == "divider":
            pdf.ln(2)
            pdf.set_draw_color(199, 205, 214)
            pdf.line(pdf.l_margin, pdf.get_y(), pdf.l_margin + W, pdf.get_y())
            pdf.ln(3)
        elif k == "heading":
            if pdf.get_y() > pdf.h - 45:
                pdf.add_page()
            pdf.ln(3)
            pdf.set_font(F, "B", 13)
            pdf.set_text_color(*accent)
            pdf.multi_cell(W, 7, txt(bl.text), **nl)
            pdf.ln(1)
        elif k == "sub":
            if pdf.get_y() > pdf.h - 35:
                pdf.add_page()
            pdf.ln(1.5)
            pdf.set_font(F, "B", 11)
            pdf.multi_cell(W, 6, txt(bl.text), **nl)
        elif k == "para":
            pdf.set_font(F, "", 10.5)
            pdf.multi_cell(W, 5.5, txt(bl.text), **nl)
            pdf.ln(1)
        elif k in ("bullet", "num"):
            pdf.set_font(F, "B" if k == "num" else "", 10.5)
            mark = f"{bl.number}." if k == "num" else "•"
            pdf.cell(6, 5.5, txt(mark))
            pdf.set_font(F, "", 10.5)
            pdf.multi_cell(W - 6, 5.5, txt(bl.text), **nl)
            pdf.ln(0.8)
        elif k == "table":
            pdf.set_font(F, "", 9.5)
            widths = tuple(w * W for w in bl.weights)
            head = FontFace(family=F, emphasis="BOLD", color=(20, 20, 20), fill_color=(232, 238, 245))
            with pdf.table(col_widths=widths, line_height=5.2, text_align="LEFT", padding=1.6,
                           headings_style=head, borders_layout="HORIZONTAL_LINES",
                           first_row_as_headings=True) as table:
                r = table.row()
                for h in bl.header:
                    r.cell(txt(h))
                for row in bl.rows:
                    r = table.row()
                    for v in row:
                        r.cell(txt(check if v == "✓" else v))
            pdf.ln(2)
        elif k == "utter":
            pdf.set_font(F, "B", 10)
            label = f"{bl.label}  "
            pdf.write(5.2, txt(label))
            pdf.set_font(F, "", 8.5)
            pdf.set_text_color(107, 114, 128)
            pdf.write(5.2, txt(f"[{format_timestamp(bl.number)}]  "))
            pdf.set_text_color(20, 20, 20)
            pdf.set_font(F, "", 10)
            pdf.write(5.2, txt(bl.text))
            pdf.ln(7)
        elif k == "sign":
            if pdf.get_y() > pdf.h - 70:
                pdf.add_page()
            for role, name in bl.lines:
                pdf.ln(14)
                pdf.set_font(F, "", 10)
                pdf.cell(W, 5, SIGNATURE_LINE, **nl)
                pdf.set_font(F, "", 8.5)
                pdf.set_text_color(107, 114, 128)
                pdf.cell(W, 4.5, SIGNATURE_PLACE_DATE, **nl)
                pdf.set_text_color(20, 20, 20)
                pdf.set_font(F, "", 10)
                pdf.cell(W, 5, txt(_sig_label(role, name)), **nl)
        elif k == "note":
            pdf.ln(5)
            pdf.set_font(F, "I", 8.5)
            pdf.set_text_color(107, 114, 128)
            pdf.multi_cell(W, 4.5, txt(bl.text), **nl)
    path = Path(path)
    tmp = path.with_suffix(".tmp.pdf")
    pdf.output(str(tmp))
    tmp.replace(path)
    return path


def export_all(m: Meeting, folder: Path, organization: str = "") -> dict[str, Path]:
    folder.mkdir(parents=True, exist_ok=True)
    blocks = build_blocks(m, organization)
    base = sanitize_file_name(m.title)
    out = {
        "docx": write_docx(blocks, folder / f"{base}.docx"),
        "pdf": write_pdf(blocks, folder / f"{base}.pdf", m.title or "Protokoll"),
    }
    md = folder / f"{base}.md"
    md.write_text(render_markdown(blocks), encoding="utf-8")
    out["md"] = md
    return out


if __name__ == "__main__" and len(sys.argv) > 1:  # pragma: no cover
    print(_font_files())
