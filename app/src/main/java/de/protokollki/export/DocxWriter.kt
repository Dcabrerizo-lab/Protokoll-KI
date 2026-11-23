package de.protokollki.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Minimales WordprocessingML (DOCX) – von Hand geschrieben, ohne Bibliotheken.
 * Keine android.*-Abhängigkeiten (per JVM-Unit-Test prüfbar).
 */

/** XML-Escaping inkl. Entfernen von in XML 1.0 unzulässigen Steuerzeichen. */
internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\'' -> sb.append("&apos;")
            c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
            c < ' ' -> {} // unzulässig
            c == '￾' || c == '￿' -> {}
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

internal object DocxWriter {
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val ACCENT = "4F46E5"
    private const val TEXT_WIDTH = 9638 // A4 (11906) minus 2 × 1134 Twips Rand

    fun write(blocks: List<Block>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        put(zip, "[Content_Types].xml", contentTypes())
        put(zip, "_rels/.rels", rootRels())
        put(zip, "word/_rels/document.xml.rels", documentRels())
        put(zip, "word/document.xml", document(blocks))
        put(zip, "word/styles.xml", styles())
        zip.finish()
        zip.flush()
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private fun contentTypes() = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "</Types>"

    private fun rootRels() = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private fun documentRels() = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    // ------------------------------------------------------------------ document.xml

    internal fun document(blocks: List<Block>): String {
        val sb = StringBuilder()
        sb.append(XML_HEAD)
        sb.append("<w:document xmlns:w=\"").append(W_NS).append("\"><w:body>")
        for (bl in blocks) {
            when (bl) {
                is Block.Title -> {
                    para(sb, "Subtitle", listOf(Run(bl.kind)))
                    para(sb, "Title", listOf(Run(bl.title)))
                }
                is Block.Meta ->
                    para(sb, "Meta", listOf(Run(bl.label + ": ", bold = true), Run(bl.value)))
                Block.Divider -> sb.append(
                    "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"D1D5DB\"/></w:pBdr>" +
                        "<w:spacing w:after=\"120\"/></w:pPr></w:p>",
                )
                is Block.Heading -> para(sb, "Heading1", listOf(Run(bl.text)))
                is Block.SubHeading -> para(sb, "Heading2", listOf(Run(bl.text)))
                is Block.Paragraph -> para(sb, null, listOf(Run(bl.text)))
                is Block.Bullet -> para(sb, "ListBullet", listOf(Run("•\t"), Run(bl.text)))
                is Block.Numbered -> para(sb, "ListBullet", listOf(Run("${bl.number}.\t"), Run(bl.text)))
                is Block.Table -> table(sb, bl)
                is Block.Utterance -> para(
                    sb, null,
                    listOf(
                        Run(bl.speaker, bold = true, color = ACCENT),
                        Run(" [${bl.time}]: ", color = "6B7280"),
                        Run(bl.text),
                    ),
                )
                is Block.Note -> para(sb, "Note", listOf(Run(bl.text)))
                is Block.Signatures -> signatures(sb, bl)
            }
        }
        sb.append("<w:sectPr>")
        sb.append("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        sb.append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/>")
        sb.append("</w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private class Run(val text: String, val bold: Boolean = false, val color: String? = null)

    private fun para(sb: StringBuilder, style: String?, runs: List<Run>) {
        sb.append("<w:p>")
        if (style != null) sb.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        runs.forEach { run(sb, it) }
        sb.append("</w:p>")
    }

    private fun run(sb: StringBuilder, r: Run) {
        val lines = r.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        sb.append("<w:r>")
        if (r.bold || r.color != null) {
            sb.append("<w:rPr>")
            if (r.bold) sb.append("<w:b/><w:bCs/>")
            if (r.color != null) sb.append("<w:color w:val=\"").append(r.color).append("\"/>")
            sb.append("</w:rPr>")
        }
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append("<w:br/>")
            // Tabulatoren als eigenes Element
            val parts = line.split('\t')
            parts.forEachIndexed { j, part ->
                if (j > 0) sb.append("<w:tab/>")
                if (part.isNotEmpty()) {
                    sb.append("<w:t xml:space=\"preserve\">").append(xmlEscape(part)).append("</w:t>")
                }
            }
        }
        sb.append("</w:r>")
    }

    private fun table(sb: StringBuilder, t: Block.Table) {
        val cols = t.header.size
        val widths = IntArray(cols) { i ->
            (TEXT_WIDTH * (t.weights.getOrElse(i) { 1f / cols })).toInt()
        }
        val border = "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\""
        sb.append("<w:tbl><w:tblPr>")
        sb.append("<w:tblW w:w=\"").append(widths.sum()).append("\" w:type=\"dxa\"/>")
        sb.append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(side).append(' ').append(border).append("/>")
        }
        sb.append("</w:tblBorders>")
        sb.append("<w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/>")
        sb.append("<w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar>")
        sb.append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/>")
        sb.append("</w:tblPr>")
        sb.append("<w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"").append(it).append("\"/>") }
        sb.append("</w:tblGrid>")

        fun row(cells: List<String>, header: Boolean) {
            sb.append("<w:tr>")
            if (header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (i in 0 until cols) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                if (header) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEF2FF\"/>")
                sb.append("</w:tcPr>")
                para(sb, "TableText", listOf(Run(cells.getOrElse(i) { "" }, bold = header)))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        row(t.header, true)
        t.rows.forEach { row(it, false) }
        sb.append("</w:tbl>")
        // Word verlangt nach einer Tabelle einen Absatz vor dem nächsten Element/Ende
        sb.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>")
    }

    /**
     * Unterschriftsfeld: rahmenlose Tabelle mit zwei Spalten; je Feld Platz zum Unterschreiben,
     * eine Linie (oberer Absatzrahmen), darunter „Ort, Datum“ und die Rolle.
     */
    private fun signatures(sb: StringBuilder, s: Block.Signatures) {
        if (s.lines.isEmpty()) return
        val gap = 567 // 1 cm
        val col = (TEXT_WIDTH - gap) / 2
        val widths = intArrayOf(col, gap, col)
        sb.append("<w:p><w:pPr><w:spacing w:before=\"360\" w:after=\"0\"/></w:pPr></w:p>")
        sb.append("<w:tbl><w:tblPr>")
        sb.append("<w:tblW w:w=\"").append(widths.sum()).append("\" w:type=\"dxa\"/>")
        sb.append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(side).append(" w:val=\"nil\"/>")
        }
        sb.append("</w:tblBorders>")
        sb.append("<w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblCellMar><w:left w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar>")
        sb.append("</w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"").append(it).append("\"/>") }
        sb.append("</w:tblGrid>")
        s.lines.chunked(2).forEach { pair ->
            sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            for (i in 0 until 3) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/></w:tcPr>")
                val line = when (i) {
                    0 -> pair.getOrNull(0)
                    2 -> pair.getOrNull(1)
                    else -> null
                }
                if (line == null) {
                    sb.append("<w:p/>")
                } else {
                    // Platz zum Unterschreiben
                    sb.append("<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"720\" w:after=\"0\"/></w:pPr></w:p>")
                    sb.append("<w:p><w:pPr><w:keepNext/><w:pBdr><w:top w:val=\"single\" w:sz=\"6\" w:space=\"2\" w:color=\"374151\"/></w:pBdr>")
                    sb.append("<w:spacing w:after=\"0\"/></w:pPr>")
                    sb.append("<w:r><w:rPr><w:color w:val=\"6B7280\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr>")
                    sb.append("<w:t xml:space=\"preserve\">").append(xmlEscape(SIGNATURE_PLACE_DATE)).append("</w:t></w:r></w:p>")
                    para(sb, "TableText", listOf(Run(line.label, bold = true)))
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        sb.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>")
    }

    // ------------------------------------------------------------------ styles.xml

    private fun styles(): String {
        fun pStyle(
            id: String, name: String, pPr: String = "", rPr: String = "", builtIn: Boolean = true,
        ) = "<w:style w:type=\"paragraph\"" + (if (!builtIn) " w:customStyle=\"1\"" else "") +
            " w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:next w:val=\"Normal\"/><w:qFormat/>" +
            (if (pPr.isNotEmpty()) "<w:pPr>$pPr</w:pPr>" else "") +
            (if (rPr.isNotEmpty()) "<w:rPr>$rPr</w:rPr>" else "") +
            "</w:style>"

        return XML_HEAD +
            "<w:styles xmlns:w=\"$W_NS\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"de-DE\" w:eastAsia=\"de-DE\" w:bidi=\"ar-SA\"/>" +
            "</w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
            "</w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            pStyle(
                "Title", "Title",
                pPr = "<w:spacing w:after=\"160\"/>",
                rPr = "<w:b/><w:bCs/><w:color w:val=\"111827\"/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/>",
            ) +
            pStyle(
                "Subtitle", "Subtitle",
                pPr = "<w:spacing w:after=\"40\"/>",
                rPr = "<w:b/><w:bCs/><w:color w:val=\"$ACCENT\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>",
            ) +
            pStyle(
                "Heading1", "heading 1",
                pPr = "<w:keepNext/><w:keepLines/><w:spacing w:before=\"320\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/>",
                rPr = "<w:b/><w:bCs/><w:color w:val=\"$ACCENT\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/>",
            ) +
            pStyle(
                "Heading2", "heading 2",
                pPr = "<w:keepNext/><w:keepLines/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/>",
                rPr = "<w:b/><w:bCs/><w:color w:val=\"1F2937\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>",
            ) +
            pStyle(
                "ListBullet", "List Bullet",
                pPr = "<w:tabs><w:tab w:val=\"left\" w:pos=\"360\"/></w:tabs>" +
                    "<w:spacing w:after=\"60\"/><w:ind w:left=\"360\" w:hanging=\"360\"/>",
            ) +
            pStyle(
                "Meta", "Meta", builtIn = false,
                pPr = "<w:spacing w:after=\"20\"/>",
                rPr = "<w:color w:val=\"374151\"/>",
            ) +
            pStyle("TableText", "Table Text", builtIn = false, pPr = "<w:spacing w:after=\"0\"/>") +
            pStyle(
                "Note", "Note", builtIn = false,
                pPr = "<w:spacing w:before=\"480\"/>",
                rPr = "<w:i/><w:iCs/><w:color w:val=\"6B7280\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>",
            ) +
            "</w:styles>"
    }
}
