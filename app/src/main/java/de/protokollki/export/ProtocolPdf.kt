package de.protokollki.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Setzt das Protokoll als A4-PDF (595 × 842 pt) mit [PdfDocument]: Umbruch über [StaticLayout],
 * automatische Seitenumbrüche (auch innerhalb langer Absätze), Tabellen mit Linien,
 * wiederholter Tabellenkopf und „Seite X von Y“.
 */
internal object ProtocolPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 50f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val BOTTOM = PAGE_H - MARGIN
    private const val CELL_PAD = 4f
    private const val SIGN_SPACE = 42f   // Platz zum Unterschreiben über der Linie
    private const val SIGN_GAP = 36f     // Abstand zwischen zwei Unterschriftsfeldern

    private val ACCENT = Color.rgb(0x4F, 0x46, 0xE5)
    private val TEXT = Color.rgb(0x1F, 0x29, 0x37)
    private val DARK = Color.rgb(0x11, 0x18, 0x27)
    private val MUTED = Color.rgb(0x6B, 0x72, 0x80)
    private val META = Color.rgb(0x37, 0x41, 0x51)
    private val LINE = Color.rgb(0xD1, 0xD5, 0xDB)
    private val HEADER_FILL = Color.rgb(0xEE, 0xF2, 0xFF)

    // ------------------------------------------------------------------ Layout-Elemente

    private sealed class Item(val spaceBefore: Float)

    private class TextItem(
        val layout: StaticLayout,
        val x: Float,
        spaceBefore: Float,
        /** Platz, der nach der ersten Zeile noch frei sein muss (Überschriften nicht allein lassen). */
        val keepWithNext: Float = 0f,
        val prefix: String? = null,
        val prefixX: Float = 0f,
        val prefixPaint: TextPaint? = null,
    ) : Item(spaceBefore)

    private class RowItem(
        val cells: List<StaticLayout>,
        val widths: FloatArray,
        val height: Float,
        val header: Boolean,
        val headerRow: RowItem?,
        spaceBefore: Float,
    ) : Item(spaceBefore)

    private class DividerItem(val height: Float, spaceBefore: Float) : Item(spaceBefore)

    /** Bis zu zwei Unterschriftsfelder nebeneinander: Linie, darunter „Ort, Datum“ und die Rolle. */
    private class SignatureRowItem(
        val cells: List<Pair<StaticLayout, StaticLayout>>,
        val colWidth: Float,
        val height: Float,
        spaceBefore: Float,
    ) : Item(spaceBefore)

    private class Paints {
        val body = textPaint(10.5f, TEXT)
        val title = textPaint(20f, DARK, Typeface.DEFAULT_BOLD)
        val kind = textPaint(10.5f, ACCENT, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.06f }
        val meta = textPaint(10f, META)
        val h1 = textPaint(14f, ACCENT, Typeface.DEFAULT_BOLD)
        val h2 = textPaint(11.5f, DARK, Typeface.DEFAULT_BOLD)
        val cell = textPaint(9.5f, TEXT)
        val cellBold = textPaint(9.5f, DARK, Typeface.DEFAULT_BOLD)
        val note = textPaint(8.5f, MUTED, Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
        val footer = textPaint(8f, MUTED)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.6f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HEADER_FILL; style = Paint.Style.FILL }
        val signLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = META; style = Paint.Style.STROKE; strokeWidth = 0.8f
        }
        val signSmall = textPaint(8.5f, MUTED)
        val signRole = textPaint(10f, DARK, Typeface.DEFAULT_BOLD)

        private fun textPaint(size: Float, color: Int, tf: Typeface = Typeface.DEFAULT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = tf
                textLocale = Locale.GERMANY
            }
    }

    // ------------------------------------------------------------------ öffentlich

    fun write(blocks: List<Block>, docTitle: String, file: File) {
        val p = Paints()
        val items = buildItems(blocks, p)
        val pages = paginate(items, p)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, ops ->
                val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i + 1).create()
                val page = doc.startPage(info)
                val c = page.canvas
                ops.forEach { it(c) }
                drawFooter(c, i + 1, pages.size, docTitle, p)
                doc.finishPage(page)
            }
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------------ Aufbau

    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout {
        val w = width.toInt().coerceAtLeast(10)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1f, 1.12f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun buildItems(blocks: List<Block>, p: Paints): List<Item> {
        val items = mutableListOf<Item>()
        var firstMeta = true
        for (b in blocks) {
            when (b) {
                is Block.Title -> {
                    items += TextItem(layout(b.kind.uppercase(Locale.GERMANY), p.kind, CONTENT_W), MARGIN, 0f)
                    items += TextItem(layout(b.title, p.title, CONTENT_W), MARGIN, 4f)
                }
                is Block.Meta -> {
                    val s = SpannableStringBuilder()
                    s.append(b.label).append(": ")
                    s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.append(b.value)
                    items += TextItem(layout(s, p.meta, CONTENT_W), MARGIN, if (firstMeta) 12f else 2f)
                    firstMeta = false
                }
                Block.Divider -> items += DividerItem(14f, 4f)
                is Block.Heading ->
                    items += TextItem(layout(b.text, p.h1, CONTENT_W), MARGIN, 18f, keepWithNext = 40f)
                is Block.SubHeading ->
                    items += TextItem(layout(b.text, p.h2, CONTENT_W), MARGIN, 10f, keepWithNext = 30f)
                is Block.Paragraph ->
                    items += TextItem(layout(b.text, p.body, CONTENT_W), MARGIN, 4f)
                is Block.Bullet -> items += TextItem(
                    layout(b.text, p.body, CONTENT_W - 16f), MARGIN + 16f, 3f,
                    prefix = "•", prefixX = MARGIN + 4f, prefixPaint = p.body,
                )
                is Block.Numbered -> items += TextItem(
                    layout(b.text, p.body, CONTENT_W - 20f), MARGIN + 20f, 3f,
                    prefix = "${b.number}.", prefixX = MARGIN + 2f, prefixPaint = p.body,
                )
                is Block.Table -> {
                    val cols = b.header.size
                    val widths = FloatArray(cols) { CONTENT_W * b.weights.getOrElse(it) { 1f / cols } }
                    fun row(cells: List<String>, header: Boolean, headerRow: RowItem?, space: Float): RowItem {
                        val layouts = (0 until cols).map { i ->
                            layout(cells.getOrElse(i) { "" }, if (header) p.cellBold else p.cell,
                                widths[i] - 2 * CELL_PAD)
                        }
                        val h = (layouts.maxOfOrNull { it.height } ?: 0) + 2 * CELL_PAD
                        return RowItem(layouts, widths, h, header, headerRow, space)
                    }
                    val head = row(b.header, true, null, 8f)
                    items += head
                    b.rows.forEach { items += row(it, false, head, 0f) }
                }
                is Block.Utterance -> {
                    val s = SpannableStringBuilder()
                    s.append(b.speaker)
                    s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(ACCENT), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val t0 = s.length
                    s.append(" [").append(b.time).append("]: ")
                    s.setSpan(ForegroundColorSpan(MUTED), t0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.append(b.text)
                    items += TextItem(layout(s, p.body, CONTENT_W), MARGIN, 6f)
                }
                is Block.Note -> items += TextItem(layout(b.text, p.note, CONTENT_W), MARGIN, 24f)
                is Block.Signatures -> {
                    val colW = (CONTENT_W - SIGN_GAP) / 2f
                    b.lines.chunked(2).forEachIndexed { i, pair ->
                        val cells = pair.map { l ->
                            layout(SIGNATURE_PLACE_DATE, p.signSmall, colW) to layout(l.label, p.signRole, colW)
                        }
                        val h = SIGN_SPACE + 4f +
                            (cells.maxOfOrNull { it.first.height + 2 + it.second.height } ?: 0)
                        items += SignatureRowItem(cells, colW, h, if (i == 0) 28f else 18f)
                    }
                }
            }
        }
        return items
    }

    // ------------------------------------------------------------------ Seitenumbruch

    private fun paginate(items: List<Item>, p: Paints): List<List<(Canvas) -> Unit>> {
        val pages = mutableListOf<MutableList<(Canvas) -> Unit>>()
        var cur = mutableListOf<(Canvas) -> Unit>()
        pages += cur
        var y = MARGIN
        var empty = true

        fun newPage() {
            cur = mutableListOf()
            pages += cur
            y = MARGIN
            empty = true
        }

        fun placeRow(row: RowItem) {
            val yy = y
            cur.add { c -> drawRow(c, row, yy, p) }
            y += row.height
            empty = false
        }

        for (item in items) {
            if (!empty) y += item.spaceBefore
            when (item) {
                is TextItem -> {
                    val lay = item.layout
                    if (lay.lineCount == 0) continue
                    val firstLine = lay.getLineBottom(0).toFloat()
                    if (!empty && y + firstLine + item.keepWithNext > BOTTOM) newPage()
                    var line = 0
                    while (line < lay.lineCount) {
                        val top = lay.getLineTop(line)
                        var end = line
                        while (end < lay.lineCount && lay.getLineBottom(end) - top <= BOTTOM - y) end++
                        if (end == line) {
                            if (empty) end = line + 1 else {
                                newPage(); continue
                            }
                        }
                        val from = line
                        val to = end
                        val yy = y
                        cur.add { c -> drawSlice(c, item, from, to, yy) }
                        y += (lay.getLineBottom(to - 1) - top).toFloat()
                        empty = false
                        line = end
                        if (line < lay.lineCount) newPage()
                    }
                }
                is RowItem -> {
                    if (!empty && y + item.height > BOTTOM) {
                        newPage()
                        if (!item.header) item.headerRow?.let { placeRow(it) }
                    } else if (item.header && y + item.height + 30f > BOTTOM && !empty) {
                        // Tabellenkopf nicht allein am Seitenende
                        newPage()
                    }
                    placeRow(item)
                }
                is SignatureRowItem -> {
                    if (!empty && y + item.height > BOTTOM) newPage()
                    val yy = y
                    cur.add { c -> drawSignatures(c, item, yy, p) }
                    y += item.height
                    empty = false
                }
                is DividerItem -> {
                    if (!empty && y + item.height > BOTTOM) newPage()
                    val yy = y + item.height / 2f
                    cur.add { c -> c.drawLine(MARGIN, yy, MARGIN + CONTENT_W, yy, p.line) }
                    y += item.height
                    empty = false
                }
            }
        }
        return pages
    }

    // ------------------------------------------------------------------ Zeichnen

    private fun drawSlice(c: Canvas, item: TextItem, from: Int, to: Int, y: Float) {
        val lay = item.layout
        val top = lay.getLineTop(from).toFloat()
        val bottom = lay.getLineBottom(to - 1).toFloat()
        c.save()
        c.translate(item.x, y - top)
        c.clipRect(-4f, top, lay.width + 4f, bottom + 1f)
        lay.draw(c)
        c.restore()
        val pp = item.prefixPaint
        if (from == 0 && item.prefix != null && pp != null) {
            c.drawText(item.prefix, item.prefixX, y + lay.getLineBaseline(0), pp)
        }
    }

    private fun drawRow(c: Canvas, row: RowItem, y: Float, p: Paints) {
        val right = MARGIN + CONTENT_W
        if (row.header) c.drawRect(MARGIN, y, right, y + row.height, p.fill)
        c.drawRect(MARGIN, y, right, y + row.height, p.line)
        var x = MARGIN
        row.cells.forEachIndexed { i, cell ->
            if (i > 0) c.drawLine(x, y, x, y + row.height, p.line)
            c.save()
            c.translate(x + CELL_PAD, y + CELL_PAD)
            cell.draw(c)
            c.restore()
            x += row.widths[i]
        }
    }

    private fun drawSignatures(c: Canvas, row: SignatureRowItem, y: Float, p: Paints) {
        row.cells.forEachIndexed { i, (placeDate, role) ->
            val x = MARGIN + i * (row.colWidth + SIGN_GAP)
            val lineY = y + SIGN_SPACE
            c.drawLine(x, lineY, x + row.colWidth, lineY, p.signLine)
            c.save()
            c.translate(x, lineY + 4f)
            placeDate.draw(c)
            c.translate(0f, placeDate.height + 2f)
            role.draw(c)
            c.restore()
        }
    }

    private fun drawFooter(c: Canvas, page: Int, total: Int, title: String, p: Paints) {
        val y = PAGE_H - 28f
        c.drawLine(MARGIN, y - 12f, MARGIN + CONTENT_W, y - 12f, p.line)
        val left = TextUtils.ellipsize(title, p.footer, CONTENT_W * 0.65f, TextUtils.TruncateAt.END)
        c.drawText(left, 0, left.length, MARGIN, y, p.footer)
        val label = "Seite $page von $total"
        val w = p.footer.measureText(label)
        c.drawText(label, MARGIN + CONTENT_W - w, y, p.footer)
    }
}
