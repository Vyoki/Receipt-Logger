package com.kitchenreceipts.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class ReportDocument(
    val documentId: Long,
    val sellerName: String,
    val date: LocalDate?,
    val documentNumber: String?,
    val totalCents: Long?,
    val lineItemCount: Int,
)

data class MonthlySellerRow(
    /** null = documents without a date. */
    val month: YearMonth?,
    val sellerName: String,
    val documentCount: Int,
    /** Sum of document totals (as printed, normally VAT-inclusive). */
    val totalCents: Long,
    /** Documents in this group whose total is missing, so not in [totalCents]. */
    val documentsMissingTotal: Int,
    val lineItemCount: Int,
)

data class MonthTotal(val month: YearMonth?, val totalCents: Long, val documentCount: Int, val documentsMissingTotal: Int)

object Reports {

    fun monthlyBySeller(docs: List<ReportDocument>): List<MonthlySellerRow> =
        docs.groupBy { (it.date?.let(YearMonth::from)) to it.sellerName }
            .map { (key, group) ->
                MonthlySellerRow(
                    month = key.first,
                    sellerName = key.second,
                    documentCount = group.size,
                    totalCents = group.sumOf { it.totalCents ?: 0L },
                    documentsMissingTotal = group.count { it.totalCents == null },
                    lineItemCount = group.sumOf { it.lineItemCount },
                )
            }
            .sortedWith(
                compareByDescending<MonthlySellerRow> { it.month ?: YearMonth.of(1, 1) }
                    .thenByDescending { it.totalCents }
                    .thenBy { it.sellerName.lowercase() },
            )

    fun monthTotals(rows: List<MonthlySellerRow>): List<MonthTotal> =
        rows.groupBy { it.month }.map { (m, r) ->
            MonthTotal(m, r.sumOf { it.totalCents }, r.sumOf { it.documentCount }, r.sumOf { it.documentsMissingTotal })
        }.sortedByDescending { it.month ?: YearMonth.of(1, 1) }
}

/** CSV output. The Italian variant opens correctly in Italian Excel / LibreOffice (";" and decimal comma). */
enum class CsvFormat(val separator: Char, val decimalComma: Boolean) {
    ITALIAN_EXCEL(';', true),
    STANDARD(',', false),
}

class CsvWriter(private val format: CsvFormat) {
    private val sb = StringBuilder()

    fun row(vararg cells: String?): CsvWriter {
        sb.append(cells.joinToString(format.separator.toString()) { escape(it ?: "") })
        sb.append("\r\n")
        return this
    }

    fun money(cents: Long?): String? = cents?.let { decimal(ItalianNumbers.centsToDecimal(it)) }

    fun decimal(v: BigDecimal?): String? {
        if (v == null) return null
        val plain = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return if (format.decimalComma) plain.replace('.', ',') else plain
    }

    private fun escape(cell: String): String {
        // Neutralise spreadsheet formula injection from OCR text.
        val safe = if (cell.isNotEmpty() && cell[0] in "=+-@" && ItalianNumbers.parse(cell) == null) "'$cell" else cell
        val needsQuotes = safe.any { it == format.separator || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** UTF-8 BOM so Excel detects the encoding (accents in product names). */
    fun build(withBom: Boolean = true): String = (if (withBom) "﻿" else "") + sb.toString()
}

data class PurchaseExportRow(
    val date: LocalDate?,
    val sellerName: String,
    val documentNumber: String?,
    val documentId: Long,
    val originalDescription: String,
    val productName: String?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
    val lotNumber: String?,
)

object ReportCsv {

    fun monthlySeller(rows: List<MonthlySellerRow>, format: CsvFormat): String {
        val w = CsvWriter(format)
        w.row("mese", "fornitore", "documenti", "totale_eur", "documenti_senza_totale", "righe")
        for (r in rows) {
            w.row(
                r.month?.toString() ?: "senza data",
                r.sellerName,
                r.documentCount.toString(),
                w.money(r.totalCents),
                r.documentsMissingTotal.toString(),
                r.lineItemCount.toString(),
            )
        }
        return w.build()
    }

    fun purchases(rows: List<PurchaseExportRow>, format: CsvFormat): String {
        val w = CsvWriter(format)
        w.row(
            "data", "mese", "fornitore", "numero_documento", "id_documento", "descrizione_originale", "prodotto",
            "quantita", "unita", "prezzo_unitario", "totale_riga_eur", "base_iva", "lotto",
        )
        for (r in rows) {
            w.row(
                r.date?.toString(),
                r.date?.let { YearMonth.from(it).toString() },
                r.sellerName,
                r.documentNumber,
                r.documentId.toString(),
                r.originalDescription,
                r.productName,
                w.decimal(r.quantity),
                r.unit,
                w.decimal(r.unitPrice),
                w.money(r.lineTotalCents),
                when (r.vatBasis) {
                    VatBasis.INCLUSIVE -> "IVA inclusa"
                    VatBasis.EXCLUSIVE -> "IVA esclusa"
                    VatBasis.UNKNOWN -> "non indicata"
                },
                r.lotNumber,
            )
        }
        return w.build()
    }
}
