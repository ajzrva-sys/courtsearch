package local.courtsearch

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun renderCasePdf(context: Context, case: CaseRecord): ByteArray {
    val pdf = PdfWriter(portrait = true)
    pdf.image(context)
    pdf.gap(8f)
    pdf.line(case.caseNumber, 14f, bold = true)
    pdf.line("${case.court}  |  ${case.division}", 11f)
    pdf.fields("From the search", listOf(
        "Type / charge" to case.caseType,
        "Plaintiff" to case.plaintiff,
        "Defendant" to case.defendant,
        "Judgment" to case.judgment,
        "Hearing" to listOf(case.hearingDate, case.hearingTime).filter { it.isNotBlank() }.joinToString(" "),
        "Name searched" to case.searchedName,
    ))
    val detail = case.detail
    if (detail != null) {
        val detailFields = detail.fields.filterValues { it.isNotBlank() }.map { it.key to it.value }
        if (detailFields.isNotEmpty()) pdf.fields("Case record", detailFields)
        for (table in detail.tables) pdf.table(table)
    }
    pdf.gap(10f)
    pdf.line("Printed ${printedAt()}", 8f, color = 0xFF505050.toInt())
    return pdf.finish()
}

fun renderResultsPdf(context: Context, name: String, cases: List<CaseRecord>): ByteArray {
    val pdf = PdfWriter(portrait = false)
    pdf.image(context)
    pdf.gap(6f)
    pdf.line(name.ifBlank { "Search results" }, 12f, bold = true)
    pdf.line("${cases.size} cases  ·  Printed ${printedAt()}", 9f, color = 0xFF505050.toInt())
    pdf.gap(4f)
    val columns = listOf(
        "Court" to 42,
        "Case" to 30,
        "Division" to 28,
        "Type / charge" to 40,
        "Plaintiff" to 36,
        "Defendant" to 36,
        "Judgment" to 24,
        "Hearing" to 22,
    )
    pdf.resultTable(columns, cases)
    return pdf.finish()
}

private fun printedAt(): String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))

private class PdfWriter(portrait: Boolean) {
    private val document = PdfDocument()
    private val pageWidth = if (portrait) 612 else 792
    private val pageHeight = if (portrait) 792 else 612
    private val margin = 36f
    private var page = start()
    private var canvas = page.canvas
    private var y = margin
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    fun image(context: Context) {
        val bitmap = context.assets.open("court-header.jpg").use { BitmapFactory.decodeStream(it) } ?: return
        val width = pageWidth - margin * 2
        val height = width * bitmap.height / bitmap.width
        ensure(height + 4)
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + width, y + height), null)
        y += height
        bitmap.recycle()
    }

    fun gap(points: Float) {
        y += points
    }

    fun line(text: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
        ink.textSize = size
        ink.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        ink.color = color
        val wrapped = wrap(text, pageWidth - margin * 2)
        ensure(wrapped.size * (size + 3))
        for (row in wrapped) {
            canvas.drawText(row, margin, y + size, ink)
            y += size + 3
        }
    }

    fun fields(title: String, rows: List<Pair<String, String>>) {
        val visible = rows.filter { it.second.isNotBlank() }
        if (visible.isEmpty()) return
        gap(8f)
        line(title.uppercase(), 10f, bold = true, color = 0xFF1E3A5F.toInt())
        for ((label, value) in visible) {
            ink.textSize = 9f
            ink.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            ink.color = Color.BLACK
            val labelRows = wrap(label, 120f)
            ink.typeface = Typeface.SANS_SERIF
            val valueRows = wrap(value, pageWidth - margin * 2 - 130f)
            val height = maxOf(labelRows.size, valueRows.size) * 12f
            ensure(height + 2)
            val top = y
            labelRows.forEachIndexed { index, row -> canvas.drawText(row, margin, top + 9f + index * 12f, ink.apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }) }
            ink.typeface = Typeface.SANS_SERIF
            valueRows.forEachIndexed { index, row -> canvas.drawText(row, margin + 130f, top + 9f + index * 12f, ink) }
            y = top + height
        }
    }

    fun table(table: DetailTable) {
        if (table.columns.isEmpty() || table.rows.isEmpty()) return
        gap(8f)
        val width = (pageWidth - margin * 2) / table.columns.size
        headerRow(table.columns.map { it.take(22) }, width)
        for (row in table.rows) {
            ensure(14f)
            ink.textSize = 8f
            ink.typeface = Typeface.SANS_SERIF
            table.columns.forEachIndexed { index, column ->
                canvas.drawText(fit(row[column].orEmpty(), width), margin + index * width, y + 9f, ink)
            }
            y += 12f
        }
    }

    fun resultTable(columns: List<Pair<String, Int>>, cases: List<CaseRecord>) {
        val units = columns.sumOf { it.second }.toFloat()
        val scale = (pageWidth - margin * 2) / units
        fun drawHeader() {
            ensure(16f)
            ink.textSize = 8f
            ink.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            var x = margin
            for ((label, unit) in columns) {
                canvas.drawText(label, x, y + 9f, ink)
                x += unit * scale
            }
            y += 14f
        }
        drawHeader()
        val values = cases.map { case ->
            listOf(case.court, case.caseNumber, case.division, case.caseType, case.plaintiff, case.defendant, case.judgment, case.hearingDate)
        }
        ink.typeface = Typeface.SANS_SERIF
        for (row in values) {
            if (y > pageHeight - margin - 12f) {
                newPage()
                drawHeader()
                ink.typeface = Typeface.SANS_SERIF
            }
            var x = margin
            row.forEachIndexed { index, value ->
                val width = columns[index].second * scale
                canvas.drawText(fit(value, width), x, y + 8f, ink)
                x += width
            }
            y += 11f
        }
    }

    fun finish(): ByteArray {
        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun headerRow(labels: List<String>, width: Float) {
        ensure(16f)
        ink.textSize = 8f
        ink.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        labels.forEachIndexed { index, label ->
            canvas.drawText(fit(label, width), margin + index * width, y + 9f, ink)
        }
        y += 14f
    }

    private fun fit(value: String, width: Float): String {
        ink.textSize = 8f
        var text = value.replace(Regex("\\s+"), " ").trim()
        if (ink.measureText(text) <= width - 4) return text
        while (text.isNotEmpty() && ink.measureText("$text.") > width - 4) text = text.dropLast(1)
        return if (text.isEmpty()) "" else "$text."
    }

    private fun wrap(text: String, width: Float): List<String> {
        val words = text.replace(Regex("\\s+"), " ").trim().split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = words.first()
        for (word in words.drop(1)) {
            val candidate = "$current $word"
            if (ink.measureText(candidate) <= width) current = candidate else {
                lines.add(current)
                current = word
            }
        }
        lines.add(current)
        return lines
    }

    private fun ensure(height: Float) {
        if (y + height <= pageHeight - margin) return
        newPage()
    }

    private fun newPage() {
        document.finishPage(page)
        page = start()
        canvas = page.canvas
        y = margin
    }

    private fun start(): PdfDocument.Page {
        val number = document.pages.size + 1
        return document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number).create())
    }
}
