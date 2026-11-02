package com.toroid.inspector

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.*
import com.itextpdf.layout.properties.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object PdfExporter {

    private val GREEN  = DeviceRgb(0, 180, 80)
    private val RED    = DeviceRgb(220, 53, 69)
    private val AMBER  = DeviceRgb(255, 160, 0)
    private val DARK   = DeviceRgb(30, 30, 30)
    private val GRAY_L = DeviceRgb(245, 245, 245)
    private val BLUE   = DeviceRgb(0, 100, 200)

    /**
     * Build a PDF for a list of records and return a share-ready Intent.
     */
    fun exportAndShare(context: Context, records: List<MeasurementRecord>, settings: AppSettings): Intent? {
        return try {
            val file = buildPdf(context, records, settings)
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type     = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Toroid Inspection Report — ${settings.partNumber}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun buildPdf(context: Context, records: List<MeasurementRecord>, settings: AppSettings): File {
        val dir  = File(context.getExternalFilesDir(null), "reports").also { it.mkdirs() }
        val sdf  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val file = File(dir, "toroid_${settings.partNumber}_${sdf.format(Date())}.pdf")

        val writer = PdfWriter(file)
        val pdf    = PdfDocument(writer)
        val doc    = Document(pdf, PageSize.A4)
        doc.setMargins(36f, 36f, 36f, 36f)

        // ── Header ──────────────────────────────────────────────────────────────
        val title = Paragraph("TOROID INSPECTION REPORT")
            .setFontSize(18f).setBold().setFontColor(BLUE)
        doc.add(title)

        val sdfFull = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        doc.add(Paragraph("Generated: ${sdfFull.format(Date())}").setFontSize(9f).setFontColor(ColorConstants.GRAY))

        // ── Job info table ───────────────────────────────────────────────────────
        val infoTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f, 1f, 1f)))
            .useAllAvailableWidth()
        infoTable.addHeaderCell(infoHeader("Part No."))
        infoTable.addHeaderCell(infoHeader("Operator"))
        infoTable.addHeaderCell(infoHeader("Batch ID"))
        infoTable.addHeaderCell(infoHeader("Total / Pass / Fail"))
        val batchId  = records.firstOrNull()?.batchId ?: "–"
        val total    = records.size
        val passed   = records.count { it.overall == "PASS" }
        val failed   = records.count { it.overall == "FAIL" }
        infoTable.addCell(infoCell(settings.partNumber))
        infoTable.addCell(infoCell(settings.operator.ifEmpty { "–" }))
        infoTable.addCell(infoCell(batchId.take(8)))
        infoTable.addCell(infoCell("$total / $passed / $failed"))
        doc.add(infoTable)

        // ── Tolerance table ──────────────────────────────────────────────────────
        doc.add(Paragraph("\nTolerances").setFontSize(11f).setBold())
        val tolTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f, 1f)))
            .useAllAvailableWidth()
        tolTable.addHeaderCell(infoHeader("Dimension"))
        tolTable.addHeaderCell(infoHeader("Min (mm)"))
        tolTable.addHeaderCell(infoHeader("Max (mm)"))
        tolTable.addCell(infoCell("OD"))
        tolTable.addCell(infoCell("${settings.odMinMm}"))
        tolTable.addCell(infoCell("${settings.odMaxMm}"))
        tolTable.addCell(infoCell("ID"))
        tolTable.addCell(infoCell("${settings.idMinMm}"))
        tolTable.addCell(infoCell("${settings.idMaxMm}"))
        tolTable.addCell(infoCell("HT"))
        tolTable.addCell(infoCell("${settings.htMinMm}"))
        tolTable.addCell(infoCell("${settings.htMaxMm}"))
        doc.add(tolTable)

        // ── Measurement records table ────────────────────────────────────────────
        doc.add(Paragraph("\nMeasurement Records").setFontSize(11f).setBold())
        val cols = UnitValue.createPercentArray(floatArrayOf(0.5f, 1.2f, 0.9f, 0.9f, 0.9f, 0.7f, 0.7f, 0.7f, 0.8f))
        val dataTable = Table(cols).useAllAvailableWidth()

        listOf("#", "Date/Time", "OD (mm)", "ID (mm)", "HT (mm)", "OD", "ID", "HT", "RESULT")
            .forEach { dataTable.addHeaderCell(dataHeader(it)) }

        val sdfRow = SimpleDateFormat("dd/MM/yy\nHH:mm", Locale.getDefault())
        records.forEachIndexed { idx, rec ->
            val bg = if (idx % 2 == 0) GRAY_L else DeviceRgb(255, 255, 255)
            dataTable.addCell(dataCell("${idx + 1}", bg))
            dataTable.addCell(dataCell(sdfRow.format(Date(rec.timestamp)), bg))
            dataTable.addCell(dataCell(rec.odMm?.let { String.format("%.2f", it) } ?: "–", bg))
            dataTable.addCell(dataCell(rec.idMm?.let { String.format("%.2f", it) } ?: "–", bg))
            dataTable.addCell(dataCell(rec.htMm?.let { String.format("%.2f", it) } ?: "–", bg))
            dataTable.addCell(statusCell(rec.odStatus, bg))
            dataTable.addCell(statusCell(rec.idStatus, bg))
            dataTable.addCell(statusCell(rec.htStatus, bg))
            dataTable.addCell(resultCell(rec.overall))
        }
        doc.add(dataTable)

        // ── Summary ──────────────────────────────────────────────────────────────
        doc.add(Paragraph("\nSummary").setFontSize(11f).setBold())
        val pct = if (total > 0) String.format("%.1f", passed * 100.0 / total) else "0.0"
        doc.add(Paragraph("Total: $total  |  Pass: $passed ($pct%)  |  Fail: $failed  |  Warn: ${records.count { it.overall == "WARN" }}")
            .setFontSize(10f))

        doc.add(Paragraph("\n\n— END OF REPORT —").setFontSize(8f).setFontColor(ColorConstants.GRAY)
            .setTextAlignment(TextAlignment.CENTER))

        doc.close()
        return file
    }

    // ── Cell helpers ─────────────────────────────────────────────────────────────
    private fun infoHeader(text: String) = Cell().add(
        Paragraph(text).setBold().setFontSize(9f).setFontColor(ColorConstants.WHITE)
    ).setBackgroundColor(DARK).setPadding(4f)

    private fun infoCell(text: String) = Cell().add(
        Paragraph(text).setFontSize(9f)
    ).setPadding(4f)

    private fun dataHeader(text: String) = Cell().add(
        Paragraph(text).setBold().setFontSize(8f).setFontColor(ColorConstants.WHITE)
    ).setBackgroundColor(DARK).setPadding(3f).setTextAlignment(TextAlignment.CENTER)

    private fun dataCell(text: String, bg: DeviceRgb) = Cell().add(
        Paragraph(text).setFontSize(8f)
    ).setBackgroundColor(bg).setPadding(3f).setTextAlignment(TextAlignment.CENTER)

    private fun statusCell(status: String, bg: DeviceRgb): Cell {
        val col = when (status.lowercase()) {
            "pass" -> GREEN
            "fail" -> RED
            "warn" -> AMBER
            else   -> DeviceRgb(150, 150, 150)
        }
        return Cell().add(
            Paragraph(status.uppercase()).setFontSize(7f).setFontColor(col).setBold()
        ).setBackgroundColor(bg).setPadding(3f).setTextAlignment(TextAlignment.CENTER)
    }

    private fun resultCell(overall: String): Cell {
        val (col, bg) = when (overall) {
            "PASS" -> Pair(ColorConstants.WHITE, GREEN)
            "FAIL" -> Pair(ColorConstants.WHITE, RED)
            "WARN" -> Pair(DARK, AMBER)
            else   -> Pair(DARK, DeviceRgb(200, 200, 200))
        }
        return Cell().add(
            Paragraph(overall).setFontSize(8f).setBold().setFontColor(col)
        ).setBackgroundColor(bg).setPadding(3f).setTextAlignment(TextAlignment.CENTER)
    }
}
