package com.kitchenreceipts.app.reports

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextUtils
import android.text.TextPaint
import com.kitchenreceipts.app.R
import com.kitchenreceipts.core.BossReport
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.PeriodKind
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.SpendTotal
import com.kitchenreceipts.core.VatBasis
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields

/**
 * Draws the owner's report as an A4 PDF, on the phone. It opens natively on an iPhone (Mail, WhatsApp, Files)
 * with nothing to install. [ctx] must already be in the report's language.
 */
class ReportPdf(private val ctx: Context) {

    private val pageW = 595
    private val pageH = 842
    private val margin = 40f
    private val contentW get() = pageW - 2 * margin

    private val ink = Color.rgb(0x1F, 0x29, 0x33)
    private val muted = Color.rgb(0x5F, 0x6B, 0x76)
    private val accent = Color.rgb(0x0B, 0x6E, 0x69)
    private val stripe = Color.rgb(0xF2, 0xF5, 0xF5)
    private val up = Color.rgb(0xB3, 0x26, 0x1E)
    private val down = Color.rgb(0x1E, 0x7B, 0x34)

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0
    private var title = ""

    fun write(r: BossReport, businessName: String, out: File) {
        doc = PdfDocument()
        title = businessName.ifBlank { ctx.getString(R.string.rep_title) }
        newPage()
        header(r)
        kpis(r)
        suppliers(r)
        categories(r)
        priceChanges(r)
        inventory(r)
        notes(r)
        finishPage()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    // ------------------------------------------------------------------ layout

    private fun newPage() {
        page?.let { finishPage() }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        canvas = page!!.canvas
        y = margin
        if (pageNo > 1) {
            canvas.drawText(title, margin, y + 10, paint(9f, muted))
            y += 26
        }
    }

    private fun finishPage() {
        val p = page ?: return
        val footer = paint(8f, muted)
        canvas.drawText(ctx.getString(R.string.rep_footer), margin, pageH - 24f, footer)
        val n = ctx.getString(R.string.rep_page, pageNo)
        canvas.drawText(n, pageW - margin - footer.measureText(n), pageH - 24f, footer)
        doc.finishPage(p)
        page = null
    }

    private fun need(h: Float) { if (y + h > pageH - 50) newPage() }

    private fun text(s: String, x: Float, p: TextPaint, maxW: Float = contentW) {
        canvas.drawText(TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString(), x, y, p)
    }

    private fun right(s: String, xRight: Float, p: TextPaint) = canvas.drawText(s, xRight - p.measureText(s), y, p)

    private fun section(label: String) {
        need(60f)
        y += 18
        canvas.drawText(label.uppercase(), margin, y, paint(11f, accent, bold = true))
        y += 6
        canvas.drawLine(margin, y, pageW - margin, y, Paint().apply { color = accent; strokeWidth = 1f })
        y += 14
    }

    private fun header(r: BossReport) {
        val band = Paint().apply { color = accent }
        canvas.drawRect(0f, 0f, pageW.toFloat(), 96f, band)
        y = 38f
        canvas.drawText(title, margin, y, paint(20f, Color.WHITE, bold = true))
        y += 22
        canvas.drawText(ctx.getString(R.string.rep_subtitle, periodLabel(r.period)), margin, y, paint(12f, Color.WHITE))
        y += 16
        canvas.drawText(
            ctx.getString(R.string.rep_generated, ItalianDates.format(LocalDate.now()), periodLabel(r.previousPeriod)),
            margin, y, paint(9f, Color.rgb(0xD8, 0xEE, 0xEC)),
        )
        y = 120f
    }

    private fun kpis(r: BossReport) {
        val boxW = (contentW - 24) / 3
        val boxes = listOf(
            Triple(ctx.getString(R.string.rep_kpi_spend), money(r.totalCents), change(r.changePercent)),
            Triple(ctx.getString(R.string.rep_kpi_docs), r.documentCount.toString(), ctx.getString(R.string.rep_kpi_suppliers, r.suppliers.size)),
            Triple(ctx.getString(R.string.rep_kpi_prices), "▲ ${r.increases}  ▼ ${r.decreases}", ctx.getString(R.string.rep_kpi_prices_hint)),
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stripe }
        boxes.forEachIndexed { i, (label, value, sub) ->
            val x = margin + i * (boxW + 12)
            canvas.drawRoundRect(RectF(x, y, x + boxW, y + 70), 8f, 8f, bg)
            canvas.drawText(label, x + 10, y + 18, paint(9f, muted))
            canvas.drawText(value, x + 10, y + 44, paint(if (i == 0) 18f else 16f, ink, bold = true))
            val subPaint = paint(9f, if (i == 0) changeColor(r.changePercent) else muted)
            canvas.drawText(TextUtils.ellipsize(sub, subPaint, boxW - 20, TextUtils.TruncateAt.END).toString(), x + 10, y + 60, subPaint)
        }
        y += 84
    }

    private fun suppliers(r: BossReport) {
        section(ctx.getString(R.string.rep_suppliers))
        if (r.suppliers.isEmpty()) { text(ctx.getString(R.string.rep_nothing), margin, paint(10f, muted)); y += 14; return }
        val max = r.suppliers.maxOf { it.totalCents }.coerceAtLeast(1)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9C, 0xCF, 0xCB) }
        val colTotal = pageW - margin - 70
        tableHead(listOf(ctx.getString(R.string.rep_col_supplier) to margin, ctx.getString(R.string.rep_col_docs) to margin + 250), listOf(
            ctx.getString(R.string.rep_col_total) to colTotal, ctx.getString(R.string.rep_col_vs_prev) to pageW - margin,
        ))
        r.suppliers.forEachIndexed { i, s ->
            need(18f)
            row(i)
            val barW = 150f * s.totalCents.coerceAtLeast(0) / max
            canvas.drawRect(margin + 280, y - 8, margin + 280 + barW, y + 1, bar)
            text(s.sellerName, margin, paint(10f), 240f)
            canvas.drawText(s.documentCount.toString() + if (s.documentsMissingTotal > 0) "*" else "", margin + 250, y, paint(10f, muted))
            right(money(s.totalCents), colTotal, paint(10f, bold = true))
            right(change(s.changePercent), pageW - margin, paint(10f, changeColor(s.changePercent)))
            y += 16
        }
    }

    private fun categories(r: BossReport) {
        section(ctx.getString(R.string.rep_categories))
        if (r.categories.isEmpty()) { text(ctx.getString(R.string.rep_nothing), margin, paint(10f, muted)); y += 14; return }
        tableHead(listOf(ctx.getString(R.string.rep_col_category) to margin), listOf(
            ctx.getString(R.string.rep_col_this_period) to pageW - margin - 170, ctx.getString(R.string.rep_col_previous) to pageW - margin,
        ))
        r.categories.forEachIndexed { i, c ->
            need(18f)
            row(i)
            text(categoryName(c.category) + "  ·  " + ctx.resources.getQuantityString(R.plurals.rep_products, c.productCount, c.productCount), margin, paint(10f), 250f)
            right(spend(c.spend), pageW - margin - 170, paint(10f, bold = true))
            right(spend(c.previous), pageW - margin, paint(10f, muted))
            y += 16
        }
        y += 2
        text(ctx.getString(R.string.rep_vat_note), margin, paint(8f, muted))
        y += 12
    }

    private fun priceChanges(r: BossReport) {
        section(ctx.getString(R.string.rep_prices))
        if (r.priceChanges.isEmpty()) { text(ctx.getString(R.string.rep_no_prices), margin, paint(10f, muted)); y += 14; return }
        tableHead(listOf(ctx.getString(R.string.rep_col_product) to margin, ctx.getString(R.string.rep_col_supplier) to margin + 200), listOf(
            ctx.getString(R.string.rep_col_price) to pageW - margin - 60, "%" to pageW - margin,
        ))
        r.priceChanges.forEachIndexed { i, c ->
            need(18f)
            row(i)
            text(c.productName, margin, paint(10f), 190f)
            text(c.newSeller + if (!c.sameSeller) " *" else "", margin + 200, paint(9f, muted), 130f)
            right("${dec(c.oldPrice)} → ${dec(c.newPrice)} €/${c.unit}", pageW - margin - 60, paint(10f))
            val pct = (if (c.isIncrease) "▲ +" else "▼ ") + ItalianNumbers.formatDecimal(c.percent, minScale = 1, maxScale = 1) + "%"
            right(pct, pageW - margin, paint(10f, if (c.isIncrease) up else down, bold = true))
            y += 16
        }
        if (r.priceChanges.any { !it.sameSeller }) {
            text(ctx.getString(R.string.rep_other_supplier_note), margin, paint(8f, muted)); y += 12
        }
    }

    private fun inventory(r: BossReport) {
        section(ctx.getString(R.string.rep_inventory))
        if (r.inventory.isEmpty()) { text(ctx.getString(R.string.rep_nothing), margin, paint(10f, muted)); y += 14; return }
        var current: Category? = null
        var i = 0
        for (line in r.inventory) {
            if (line.category != current) {
                need(40f)
                current = line.category
                y += 4
                canvas.drawText(categoryName(line.category), margin, y, paint(10f, accent, bold = true))
                y += 4
                tableHead(listOf(ctx.getString(R.string.rep_col_product) to margin), listOf(
                    ctx.getString(R.string.rep_col_bought) to pageW - margin - 190,
                    ctx.getString(R.string.rep_col_usual) to pageW - margin - 95,
                    ctx.getString(R.string.rep_col_spend) to pageW - margin,
                ))
                i = 0
            }
            need(18f)
            row(i++)
            text(line.name, margin, paint(10f), 220f)
            right(qty(line.quantities).ifEmpty { "—" }, pageW - margin - 190, paint(10f, bold = true))
            right(qty(line.usual).ifEmpty { "—" }, pageW - margin - 95, paint(10f, muted))
            right(spend(line.spend), pageW - margin, paint(10f))
            y += 16
        }
    }

    private fun notes(r: BossReport) {
        val notes = buildList {
            if (r.documentsMissingTotal > 0) add(ctx.getString(R.string.rep_note_missing_total, r.documentsMissingTotal))
            if (r.documentsWithoutDate > 0) add(ctx.getString(R.string.rep_note_no_date, r.documentsWithoutDate))
        }
        if (notes.isEmpty()) return
        need(20f + 12 * notes.size)
        y += 12
        notes.forEach { text(it, margin, paint(8f, muted)); y += 12 }
    }

    private fun tableHead(leftCols: List<Pair<String, Float>>, rightCols: List<Pair<String, Float>>) {
        need(20f)
        val p = paint(8f, muted, bold = true)
        leftCols.forEach { (label, x) -> canvas.drawText(label.uppercase(), x, y, p) }
        rightCols.forEach { (label, x) -> right(label.uppercase(), x, p) }
        y += 14
    }

    private fun row(i: Int) {
        if (i % 2 == 0) canvas.drawRect(margin - 4, y - 11, pageW - margin + 4, y + 5, Paint().apply { color = stripe })
    }

    // ------------------------------------------------------------------ formatting

    private fun money(c: Long) = ItalianNumbers.formatMoney(c, "EUR")
    private fun dec(v: BigDecimal) = ItalianNumbers.formatDecimal(v, minScale = 2, maxScale = 3)

    private fun change(p: BigDecimal?): String = when {
        p == null -> ctx.getString(R.string.rep_new)
        p.signum() > 0 -> "▲ +" + ItalianNumbers.formatDecimal(p, minScale = 1, maxScale = 1) + "%"
        p.signum() < 0 -> "▼ " + ItalianNumbers.formatDecimal(p, minScale = 1, maxScale = 1) + "%"
        else -> "= 0%"
    }

    private fun changeColor(p: BigDecimal?) = when {
        p == null -> muted
        p.signum() > 0 -> up
        p.signum() < 0 -> down
        else -> muted
    }

    private fun spend(s: List<SpendTotal>): String =
        if (s.isEmpty()) "—" else s.joinToString(" + ") { money(it.cents) + " " + basis(it.vatBasis) }

    private fun basis(b: VatBasis) = ctx.getString(
        when (b) {
            VatBasis.EXCLUSIVE -> R.string.rep_basis_excl
            VatBasis.INCLUSIVE -> R.string.rep_basis_incl
            VatBasis.UNKNOWN -> R.string.rep_basis_unknown
        },
    )

    private fun qty(q: List<com.kitchenreceipts.core.QuantityTotal>) =
        q.joinToString(" + ") { ItalianNumbers.formatDecimal(it.amount, maxScale = 2) + " " + it.unit }

    private fun categoryName(c: Category) = ctx.getString(
        when (c) {
            Category.FRUIT_VEG -> R.string.cat_fruit_veg
            Category.MEAT -> R.string.cat_meat
            Category.FISH -> R.string.cat_fish
            Category.CURED_MEATS -> R.string.cat_cured_meats
            Category.DAIRY_EGGS -> R.string.cat_dairy_eggs
            Category.BAKERY -> R.string.cat_bakery
            Category.DRY_GOODS -> R.string.cat_dry_goods
            Category.FROZEN -> R.string.cat_frozen
            Category.BEVERAGES -> R.string.cat_beverages
            Category.CLEANING -> R.string.cat_cleaning
            Category.DISPOSABLES -> R.string.cat_disposables
            Category.OTHER -> R.string.cat_other
        },
    )

    fun periodLabel(p: Period): String = when (p.kind) {
        PeriodKind.WEEK -> ctx.getString(R.string.week_label, p.start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), ItalianDates.format(p.start), ItalianDates.format(p.end))
        PeriodKind.MONTH -> monthName(YearMonth.from(p.start))
        PeriodKind.QUARTER -> ctx.getString(R.string.quarter_label, p.start.get(IsoFields.QUARTER_OF_YEAR), p.start.year)
        PeriodKind.YEAR -> p.start.year.toString()
    }

    private fun monthName(m: YearMonth): String {
        val names = ctx.resources.getStringArray(R.array.month_names)
        return "${names[m.monthValue - 1]} ${m.year}"
    }
}
