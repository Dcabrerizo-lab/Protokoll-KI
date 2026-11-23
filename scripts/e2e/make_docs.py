"""Erzeugt ein altes Protokoll als PDF, Word (.docx) und Text (Windows-1252) für den Import-Test."""
import os, sys, zipfile

OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
LINES = [
    "Protokoll der Teamsitzung",
    "vom 12.03.2025",
    "",
    "Teilnehmende: Frau Becker, Herr Wagner, Frau Kaiser",
    "",
    "TOP 1: Umzug",
    "Der Mietvertrag für das neue Büro ist unterschrieben. Für die Möbel werden Angebote eingeholt.",
    "",
    "TOP 2: Tourenplanung",
    "Die Tour Nord ist zu lang. Beschluss: Ab April wird die Tour Nord geteilt.",
    "",
    "Aufgaben:",
    "- Frau Becker holt bis 20.03.2025 drei Angebote für Möbel ein.",
    "- Herr Wagner klärt bis Ende März die Telefonanlage.",
    "- Frau Kaiser erstellt den neuen Dienstplan bis zur nächsten Sitzung.",
]

# --- Text (Windows-1252, wie aus altem Editor)
open(f"{OUT}/protokoll.txt", "wb").write("\r\n".join(LINES).encode("cp1252"))

# --- Word
def esc(s): return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
body = "".join(f'<w:p><w:r><w:t xml:space="preserve">{esc(l)}</w:t></w:r></w:p>' if l else "<w:p/>" for l in LINES)
doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
       '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
       + body + "</w:body></w:document>")
with zipfile.ZipFile(f"{OUT}/protokoll.docx", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    z.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    z.writestr("word/document.xml", doc)

# --- PDF (Helvetica, WinAnsi für Umlaute), von Hand geschrieben
def pdfstr(s): return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
ops = ["BT", "/F1 11 Tf", "50 800 Td", "14 TL"]
for l in LINES:
    ops.append(pdfstr(l) + " '")
ops.append("ET")
stream = "\n".join(ops).encode("cp1252")
objs = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
]
pdf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"); offs = []
for i, o in enumerate(objs, 1):
    offs.append(len(pdf)); pdf += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
x = len(pdf)
pdf += f"xref\n0 {len(objs)+1}\n0000000000 65535 f \n".encode()
for o in offs: pdf += f"{o:010d} 00000 n \n".encode()
pdf += f"trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n".encode()
open(f"{OUT}/protokoll.pdf", "wb").write(pdf)
print("Dokumente erzeugt:", os.listdir(OUT))
