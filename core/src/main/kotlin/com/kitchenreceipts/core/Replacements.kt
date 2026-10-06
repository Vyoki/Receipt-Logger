package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * A replacement for every value the app could not settle: under a doubtful or missing field, the best other readings
 * the app has, for the operator to take with one tap. Never applied by itself.
 *
 * Where they come from, most trusted first:
 * 1. the document's own arithmetic, from values that are sure (quantity × price = amount; taxable + VAT = total;
 *    the lines' sum);
 * 2. what the AI read where it differs from the reading;
 * 3. suppliers already saved (or in the knowledge pack) whose name is close to the misread one;
 * 4. the same day and month in a plausible year, for a date read in the future or years ago;
 * 5. the VAT rate every other line of the document has;
 * 6. the arithmetic from values that are themselves doubtful (last: weaker).
 */
object Replacements {

    /** Key of a header field ("seller", "date", "number", "subtotal", "vat", "total"). */
    fun header(field: String) = field

    /** Key of a line's field ("description", "quantity", "unitPrice", "lineTotal", "vatRate"). */
    fun item(key: Long, field: String) = "item:$key:$field"

    private const val MAX = 3

    /**
     * Replacements for the doubtful and missing fields of [d], by field key. [knownSellers]: names of suppliers saved on
     * the phone or known from the knowledge pack. [today]: for dates read in the future or long ago (null: no check).
     */
    fun compute(d: DocumentDraft, knownSellers: Collection<String> = emptyList(), today: LocalDate? = null): Map<String, List<String>> {
        val out = linkedMapOf<String, List<String>>()
        fun offer(key: String, field: DraftField, sure: List<String?>, ai: String?, weak: List<String?> = emptyList(), same: (String, String) -> Boolean = ::sameText) {
            if (!field.uncertain && !field.isMissing) return
            val list = mutableListOf<String>()
            for (v in sure + listOf(ai) + weak) {
                val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (same(t, field.text) || list.any { same(it, t) }) continue
                list += t
            }
            if (list.isNotEmpty()) out[key] = list.take(MAX)
        }
        val ai = d.aiCheck?.header.orEmpty()

        // ---- header
        offer(header("seller"), d.seller, similarSellers(d.seller.text, knownSellers), ai["seller"], same = ::sameSeller)
        offer(header("number"), d.number, emptyList(), ai["number"])
        offer(header("date"), d.date, listOfNotNull(plausibleYear(d.date.text, today)), ai["date"], same = ::sameDate)

        val sub = money(d.subtotal)
        val vat = money(d.vat)
        val tot = money(d.total)
        val lines = d.items.mapNotNull { money(it.lineTotal) }
        val linesSure = d.items.isNotEmpty() && d.items.all { !it.lineTotal.uncertain && !it.lineTotal.isMissing }
        val linesSum = if (lines.size == d.items.size && lines.isNotEmpty()) lines.sum() else null
        fun sureMoney(f: DraftField) = !f.uncertain && !f.isMissing
        fun cents(c: Long?) = c?.takeIf { it >= 0 }?.let { ItalianNumbers.centsToEditText(it) }

        // total = taxable + VAT; or the lines' sum when prices include VAT.
        offer(
            header("total"), d.total,
            sure = listOf(
                if (sub != null && vat != null && sureMoney(d.subtotal) && sureMoney(d.vat)) cents(sub + vat) else null,
                if (linesSure && d.vatBasis == VatBasis.INCLUSIVE) cents(linesSum) else null,
            ),
            ai = ai["total"],
            weak = listOf(if (sub != null && vat != null) cents(sub + vat) else null),
            same = ::sameMoney,
        )
        // taxable = total − VAT; or the lines' sum when prices exclude VAT.
        offer(
            header("subtotal"), d.subtotal,
            sure = listOf(
                if (tot != null && vat != null && sureMoney(d.total) && sureMoney(d.vat)) cents(tot - vat) else null,
                if (linesSure && d.vatBasis == VatBasis.EXCLUSIVE) cents(linesSum) else null,
            ),
            ai = ai["subtotal"],
            weak = listOf(if (tot != null && vat != null) cents(tot - vat) else null),
            same = ::sameMoney,
        )
        // VAT = total − taxable.
        offer(
            header("vat"), d.vat,
            sure = listOf(if (tot != null && sub != null && sureMoney(d.total) && sureMoney(d.subtotal)) cents(tot - sub) else null),
            ai = ai["vat"],
            weak = listOf(if (tot != null && sub != null) cents(tot - sub) else null),
            same = ::sameMoney,
        )

        // ---- lines
        val sureRates = d.items.filter { !it.vatRate.uncertain && !it.vatRate.isMissing }.mapNotNull { ItalianNumbers.parse(it.vatRate.text)?.stripTrailingZeros() }.distinct()
        for (it in d.items) {
            val q = ItalianNumbers.parse(it.quantity.text)
            val p = ItalianNumbers.parse(it.unitPrice.text)
            val t = money(it.lineTotal)
            val qSure = q != null && !it.quantity.uncertain
            val pSure = p != null && !it.unitPrice.uncertain
            val tSure = t != null && !it.lineTotal.uncertain
            val amount = if (q != null && p != null) cents(ItalianNumbers.toCents(q.multiply(p))) else null
            val quantity = if (t != null && p != null) quantityFor(t, p) else null
            val price = if (t != null && q != null) priceFor(t, q) else null

            offer(item(it.key, "description"), it.description, emptyList(), it.aiRead["description"])
            offer(item(it.key, "lineTotal"), it.lineTotal, listOf(if (qSure && pSure) amount else null), it.aiRead["amount"], listOf(amount), ::sameMoney)
            offer(item(it.key, "quantity"), it.quantity, listOf(if (tSure && pSure) quantity else null), it.aiRead["quantity"], listOf(quantity), ::sameNumber)
            offer(item(it.key, "unitPrice"), it.unitPrice, listOf(if (tSure && qSure) price else null), null, listOf(price), ::sameNumber)
            // Every other line has the same VAT rate: this one very likely too.
            offer(item(it.key, "vatRate"), it.vatRate, listOf(sureRates.singleOrNull()?.let { r -> ItalianNumbers.toEditText(r) }), null, same = ::sameNumber)
        }
        return out
    }

    // ------------------------------------------------------------------ sources

    /** Saved suppliers whose name is the misread one, give or take a letter or a legal form ("ABG" -> "ABC S.r.l."). */
    fun similarSellers(read: String, known: Collection<String>): List<String> {
        val r = DuplicateDetector.normalizeSeller(read) ?: return emptyList()
        if (r.replace(" ", "").length < 3) return emptyList()
        return known.mapNotNull { name ->
            val n = DuplicateDetector.normalizeSeller(name) ?: return@mapNotNull null
            if (n == r) return@mapNotNull null
            val score = when {
                SellerProfiles.sameCompany(read, name) -> 0
                else -> {
                    // The main word misread by a letter or two: "ABG" for "ABC", "CASEIFICI0" for "CASEIFICIO".
                    val a = r.split(' ').maxBy { it.length }
                    val b = n.split(' ').maxBy { it.length }
                    val limit = if (minOf(a.length, b.length) >= 8) 2 else 1
                    val dist = SmartMatcher.damerau(a, b, limit)
                    if (dist <= limit && a.length >= 3 && b.length >= 3) dist else return@mapNotNull null
                }
            }
            name to score
        }.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }.distinct().take(MAX)
    }

    /** "12/03/2078" read on a document of this year: the same day and month in the year that makes sense. */
    fun plausibleYear(text: String, today: LocalDate?): String? {
        if (today == null) return null
        val date = ItalianDates.parse(text) ?: return null
        if (!date.isAfter(today) && !date.isBefore(today.minusYears(2))) return null
        val fixed = listOf(today.year, today.year - 1).mapNotNull { y -> runCatching { date.withYear(y) }.getOrNull() }
            .firstOrNull { !it.isAfter(today) && !it.isBefore(today.minusYears(2)) } ?: return null
        return ItalianDates.format(fixed)
    }

    /** The quantity that makes [cents] at [price], when it is a plain number (at most 3 decimals). */
    private fun quantityFor(cents: Long, price: BigDecimal): String? {
        if (price.signum() <= 0) return null
        val q = ItalianNumbers.centsToDecimal(cents).divide(price, 3, RoundingMode.HALF_UP).stripTrailingZeros()
        if (q.signum() <= 0 || !ReceiptParser.matches(q, price, cents)) return null
        return ItalianNumbers.toEditText(q)
    }

    /** The unit price that makes [cents] for [quantity] (the shortest that fits, up to 4 decimals). */
    private fun priceFor(cents: Long, quantity: BigDecimal): String? {
        if (quantity.signum() <= 0) return null
        for (scale in 2..4) {
            val p = ItalianNumbers.centsToDecimal(cents).divide(quantity, scale, RoundingMode.HALF_UP)
            if (p.signum() > 0 && ReceiptParser.matches(quantity, p, cents)) return ItalianNumbers.toEditText(p)
        }
        return null
    }

    // ------------------------------------------------------------------ comparing

    private fun money(f: DraftField): Long? = if (f.isMissing) null else ItalianNumbers.parseCents(f.text)

    private fun sameText(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    private fun sameSeller(a: String, b: String) = DuplicateDetector.normalizeSeller(a) == DuplicateDetector.normalizeSeller(b) && b.isNotBlank()
    private fun sameMoney(a: String, b: String) = ItalianNumbers.parseCents(a)?.let { it == ItalianNumbers.parseCents(b) } ?: sameText(a, b)
    private fun sameNumber(a: String, b: String) =
        ItalianNumbers.parse(a)?.let { x -> ItalianNumbers.parse(b)?.let { x.compareTo(it) == 0 } } ?: sameText(a, b)
    private fun sameDate(a: String, b: String) = ItalianDates.parse(a)?.let { it == ItalianDates.parse(b) } ?: sameText(a, b)
}
