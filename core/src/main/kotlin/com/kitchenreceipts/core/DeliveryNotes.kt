package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What kind of document a saved document is. Read from its printed title; the operator can change it. */
enum class DocKind { INVOICE, DELIVERY_NOTE, RECEIPT, CREDIT_NOTE }

/** A delivery note an invoice says it covers ("Rif. DDT n. 88 del 03/04/2025"). */
data class DeliveryRef(val number: String, val date: LocalDate?) {
    fun encode(): String = number.replace("|", "/").replace(";", ",") + "|" + (date?.toString() ?: "")

    companion object {
        fun encodeAll(refs: List<DeliveryRef>): String? = refs.takeIf { it.isNotEmpty() }?.joinToString(";") { it.encode() }
        fun decodeAll(s: String?): List<DeliveryRef> = s.orEmpty().split(';').mapNotNull { part ->
            val n = part.substringBefore('|').trim()
            if (n.isEmpty()) null else DeliveryRef(n, part.substringAfter('|', "").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }
    }
}

/**
 * Document kind and the delivery notes an invoice refers to, read from the document's text in any of the usual
 * languages. Nothing is guessed when no title is printed: the kind stays unknown (null).
 */
object DocumentKinds {

    // OCR-tolerant spellings: 0 for O, 1 or I for T, missing dots and spaces.
    private val CREDIT = rx("(?i)\\bnota\\s+(?:di\\s+)?(?:ac)?credito\\b|\\bcredit\\s+note\\b|\\bgutschrift\\b|\\bnota\\s+de\\s+cr[eé]dito\\b|\\bavoir\\b|\\bfactura\\s+rectificativa\\b|\\(TD0[48]\\)")
    private val INVOICE = rx("(?i)\\bfattur[ae]\\b|\\bfatt\\.\\s|\\binvoice\\b|\\brechnung\\b|\\bfactura\\b|\\bfacture\\b|\\bfatura\\b")
    private val DELIVERY = rx(
        "(?i)\\bdoc(?:umento|\\.)?\\s*(?:di\\s*)?traspor[t1i][o0]\\b|\\bd\\.?\\s?d\\.?\\s?t\\b\\.?|\\bbolla\\s+(?:di\\s+)?(?:consegna|accompagnamento)\\b|" +
            "\\bdocumento\\s+di\\s+consegna\\b|\\bdelivery\\s+note\\b|\\blieferschein\\b|\\balbar[aá]n\\b|\\bbon\\s+de\\s+livraison\\b|\\bguia\\s+de\\s+remessa\\b",
    )
    private val RECEIPT = rx("(?i)\\bscontrino\\b|\\bdocumento\\s+commerciale\\b|\\bricevuta\\b|\\breceipt\\b|\\bkassenbon\\b|\\bticket\\s+de\\s+caisse\\b|\\bfactura\\s+simplificada\\b")

    /** A delivery note mentioned as a reference, not as the document's own title. */
    private val REFERENCE_CONTEXT = rx("(?i)\\b(rif\\.?|riferimento|vs\\.?|vostr[oaie]|ns\\.?|nostr[oaie]|ref\\.?|come\\s+da|di\\s+cui\\s+a[il]?|da\\s+dd?t)\\b[^\\n]{0,25}$")

    /**
     * The kind printed as the document's title: the earliest title-like mention wins, so a delivery note that says
     * "la fattura seguirà" at the bottom stays a delivery note, and an invoice listing "Rif. DDT 88" stays an invoice.
     */
    fun detect(lines: List<String>): DocKind? {
        var best: Pair<Int, DocKind>? = null
        fun offer(index: Int, kind: DocKind) {
            if (best == null || index < best!!.first) best = index to kind
        }
        var credit: Int? = null
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (credit == null && CREDIT.containsMatchIn(line)) credit = i
            // "La fattura seguirà" on a delivery note is not its title.
            if (INVOICE.containsMatchIn(line) && !INVOICE_LATER.containsMatchIn(line)) offer(i, DocKind.INVOICE)
            DELIVERY.find(line)?.let { m ->
                val before = line.substring(0, m.range.first)
                if (!REFERENCE_CONTEXT.containsMatchIn(before) && !isReferenceList(line, m.range.last + 1)) offer(i, DocKind.DELIVERY_NOTE)
            }
            if (RECEIPT.containsMatchIn(line)) offer(i, DocKind.RECEIPT)
        }
        // A credit note also prints "fattura" (the invoice it corrects): its own title decides.
        credit?.let { c -> if (best == null || c <= best!!.first + 3) return DocKind.CREDIT_NOTE }
        return best?.second
    }

    fun detect(text: String?): DocKind? = text?.let { detect(it.lines()) }

    /** "DDT: 123 del 01/10/2026" (the e-invoice's list of delivery notes) is a reference, not a title. */
    private fun isReferenceList(line: String, after: Int): Boolean =
        REF_LIST.containsMatchIn(line.substring(after))

    private val REF_LIST = rx("^\\s*:\\s*[A-Za-z]{0,3}[\\s/\\-]?\\d")
    private val INVOICE_LATER = rx("(?i)(segu|emess|riceve|invi)\\w*\\s+(?:la\\s+)?fattura|fattura\\s+(?:seguir|a\\s+parte)")
    private val SEPARATOR = rx("^\\s*(?:,|;|\\be\\b|\\band\\b)\\s*")

    private val REF_START = rx(
        "(?i)(?:\\bd\\.?\\s?d\\.?\\s?t\\b\\.?|\\bdoc(?:umento|\\.)?\\s*(?:di\\s*)?traspor[t1i][o0]\\b|\\bbolla\\b|\\bdelivery\\s+note\\b|\\blieferschein\\b|\\balbar[aá]n\\b)",
    )
    private val REF_ITEM = rx(
        "(?i)^\\s*(?:[:.]\\s*)?(?:n(?:r|um)?\\s*[.°º:]?\\s*|numero\\s+)?([A-Za-z]{0,3}[\\s/\\-]?\\d[A-Za-z0-9/\\-]{0,14})" +
            "(?:\\s*(?:del|dd\\.?|data|dt\\.?|vom|of|du|de)\\s*:?\\s*([0-9]{1,2}[./\\-][0-9]{1,2}[./\\-][0-9]{2,4}|[0-9]{1,2}\\s+[a-zA-Z]+\\s+[0-9]{4}))?",
    )

    /**
     * The delivery notes a document refers to. Each "DDT n. 123 del 01/10/2026" (or a list after "DDT:") gives one
     * reference; a number must contain a digit. On a delivery note its own number is not a reference (pass [ownNumber]).
     */
    fun references(text: String?, ownNumber: String? = null): List<DeliveryRef> {
        if (text.isNullOrBlank()) return emptyList()
        val own = DuplicateDetector.normalizeNumber(ownNumber)
        val out = LinkedHashMap<String, DeliveryRef>()
        for (line in text.lines()) {
            var from = 0
            while (true) {
                val m = REF_START.find(line, from) ?: break
                var rest = line.substring(m.range.last + 1)
                var consumed = m.range.last + 1
                // One or more "number [del date]" separated by commas or "e".
                while (true) {
                    val item = REF_ITEM.find(rest) ?: break
                    val raw = item.groupValues[1].trim().trimEnd('-', '/')
                    if (raw.isEmpty() || !raw.any(Char::isDigit) || raw.lowercase() in setOf("del")) break
                    val date = item.groupValues[2].takeIf { it.isNotBlank() }?.let { ItalianDates.parse(it) }
                    val key = DuplicateDetector.normalizeNumber(raw)
                    if (key != null && key != own && raw.length <= 16) out.putIfAbsent(key + "|" + date, DeliveryRef(raw, date))
                    consumed += item.range.last + 1
                    rest = rest.substring(item.range.last + 1)
                    // Skip a list separator before the next number.
                    val sep = SEPARATOR.find(rest) ?: break
                    rest = rest.substring(sep.range.last + 1)
                    consumed += sep.range.last + 1
                }
                from = consumed.coerceAtLeast(m.range.last + 1)
                if (from >= line.length) break
            }
        }
        // The same number with and without a date: keep the one with the date.
        val dated = out.values.filter { it.date != null }.map { DuplicateDetector.normalizeNumber(it.number) }.toSet()
        return out.values.filter { it.date != null || DuplicateDetector.normalizeNumber(it.number) !in dated }
    }
}

/** A saved document as needed for matching delivery notes to invoices. */
data class MatchDoc(
    val id: Long,
    val sellerId: Long,
    val kind: DocKind?,
    val number: String?,
    val date: LocalDate?,
    val refs: List<DeliveryRef>,
)

/** One line, as needed to compare a delivery note with its invoice. */
data class CompareLine(
    val documentId: Long,
    val lineItemId: Long,
    val productId: Long?,
    val description: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val lineTotalCents: Long?,
    val unitPrice: BigDecimal?,
)

enum class DifferenceKind {
    /** On the invoice, on none of its delivery notes. */
    NOT_DELIVERED,
    /** On a delivery note, not on the invoice (delivered, not charged). */
    NOT_INVOICED,
    /** On both, invoice charges a larger quantity. */
    MORE_INVOICED,
    /** On both, invoice charges a smaller quantity. */
    LESS_INVOICED,
    /** The delivery note printed a price and the invoice charges more per unit. */
    HIGHER_PRICE,
}

data class Difference(
    val kind: DifferenceKind,
    val description: String,
    val productId: Long?,
    /** In the compared unit (kg, l or the printed unit). */
    val unit: String?,
    val delivered: BigDecimal?,
    val invoiced: BigDecimal?,
    val deliveredPrice: BigDecimal?,
    val invoicedPrice: BigDecimal?,
    /** Rough money at stake (cents), when it can be computed from printed values; never invented. */
    val amountCents: Long?,
    val invoiceLineId: Long?,
    val deliveryLineId: Long?,
) {
    /** A key that stays the same while the documents stay the same: used to remember "checked, it's fine". */
    fun key(invoiceId: Long): String = "ddt:$invoiceId:${kind.name}:${invoiceLineId ?: 0}:${deliveryLineId ?: 0}"
}

/**
 * Delivery notes and invoices. A delivery note that an invoice names ("Rif. DDT 88 del 03/04/2025") is covered by
 * that invoice: its goods are already charged there, so spending and quantities count the invoice only. Then the
 * two are compared line by line.
 */
object DeliveryMatching {

    private val MC = MathContext.DECIMAL64
    private val QTY_TOLERANCE = BigDecimal("0.005") // half a percent: scales round differently
    private val PRICE_TOLERANCE = BigDecimal("0.01") // 1%

    /**
     * Which delivery notes are covered by which invoice (delivery note id -> invoice id). Only explicit references
     * count: same supplier, same number (leading zeros, letters and a year suffix aside), compatible dates.
     * A delivery note named by two invoices goes to the earlier one.
     */
    fun covers(docs: List<MatchDoc>): Map<Long, Long> {
        val notes = docs.filter { it.kind == DocKind.DELIVERY_NOTE && it.number != null }
        if (notes.isEmpty()) return emptyMap()
        val bySeller = notes.groupBy { it.sellerId }
        val out = HashMap<Long, Long>()
        val invoices = docs.filter { (it.kind == DocKind.INVOICE || it.kind == null) && it.refs.isNotEmpty() }
            .sortedWith(compareBy<MatchDoc>({ it.date ?: LocalDate.MAX }, { it.id }))
        for (inv in invoices) {
            val candidates = bySeller[inv.sellerId] ?: continue
            for (ref in inv.refs) {
                val hit = candidates.filter { n -> sameNumber(n.number!!, ref.number) && datesFit(n.date, ref.date, inv.date) && n.id !in out }
                    .minByOrNull { n -> if (ref.date != null && n.date != null) kotlin.math.abs(ChronoUnit.DAYS.between(n.date, ref.date)) else 99 }
                if (hit != null) out[hit.id] = inv.id
            }
        }
        return out
    }

    /** References on an invoice that match no saved delivery note: the operator may not have scanned it. */
    fun missing(invoice: MatchDoc, docs: List<MatchDoc>): List<DeliveryRef> {
        val notes = docs.filter { it.kind == DocKind.DELIVERY_NOTE && it.sellerId == invoice.sellerId && it.number != null }
        return invoice.refs.filter { ref -> notes.none { sameNumber(it.number!!, ref.number) && datesFit(it.date, ref.date, invoice.date) } }
    }

    fun sameNumber(a: String, b: String): Boolean {
        val na = DuplicateDetector.normalizeNumber(a) ?: return false
        val nb = DuplicateDetector.normalizeNumber(b) ?: return false
        if (na == nb) return true
        val ca = core(na)
        return ca.isNotEmpty() && ca == core(nb)
    }

    /** The number without a year part or letters: "DDT/2026/000123" and "123" both give "123". */
    private fun core(n: String): String {
        val groups = n.split('/').filter { g -> g.any(Char::isDigit) }.map { g -> g.filter(Char::isDigit).trimStart('0').ifEmpty { "0" } }
        val noYear = if (groups.size > 1) groups.filterNot { it.length == 4 && it.startsWith("20") } else groups
        return noYear.joinToString("/")
    }

    private fun datesFit(noteDate: LocalDate?, refDate: LocalDate?, invoiceDate: LocalDate?): Boolean {
        if (noteDate != null && refDate != null) return kotlin.math.abs(ChronoUnit.DAYS.between(noteDate, refDate)) <= 3
        if (noteDate != null && invoiceDate != null) {
            val days = ChronoUnit.DAYS.between(noteDate, invoiceDate)
            return days in -3..75
        }
        return true
    }

    /**
     * Compares an invoice with the delivery notes it covers. Lines are paired by product when both are linked,
     * otherwise by description; quantities are compared in kg or l when the units allow it, otherwise only in the
     * same printed unit. Lines without a quantity (transport, fees) are not compared.
     */
    fun compare(invoice: List<CompareLine>, notes: List<CompareLine>): List<Difference> {
        class Agg(val desc: String, val productId: Long?, val descKey: String, val unit: String, var qty: BigDecimal, var cents: Long?, var price: BigDecimal?, val lineId: Long)

        fun aggregate(lines: List<CompareLine>): List<Agg> {
            val out = ArrayList<Agg>()
            for (l in lines) {
                val q = l.quantity ?: continue
                if (q.signum() <= 0) continue
                val (unit, base) = baseQuantity(q, l.unit) ?: continue
                val dk = ProductMatching.aliasKey(l.description)
                val price = unitPriceInBase(l)
                val a = out.firstOrNull { it.unit == unit && (if (l.productId != null) it.productId == l.productId else it.productId == null && it.descKey == dk) }
                if (a == null) out += Agg(l.description, l.productId, dk, unit, base, l.lineTotalCents, price, l.lineItemId)
                else {
                    a.qty = a.qty.add(base)
                    a.cents = if (a.cents != null && l.lineTotalCents != null) a.cents!! + l.lineTotalCents else null
                    if (price != null && (a.price == null || price > a.price!!)) a.price = price
                }
            }
            return out
        }

        fun same(x: Agg, y: Agg) = (x.productId != null && x.productId == y.productId) || x.descKey == y.descKey
        // A line that could not be compared on one side (no quantity, another kind of unit) must not look missing.
        val invAll = invoice.map { it.productId to ProductMatching.aliasKey(it.description) }
        val delAll = notes.map { it.productId to ProductMatching.aliasKey(it.description) }
        fun mentioned(a: Agg, all: List<Pair<Long?, String>>) = all.any { (p, d) -> (a.productId != null && p == a.productId) || d == a.descKey }

        val inv = aggregate(invoice)
        val del = aggregate(notes)
        val used = HashSet<Agg>()
        val out = ArrayList<Difference>()
        for (i in inv) {
            val d = del.firstOrNull { it !in used && it.unit == i.unit && it.productId != null && it.productId == i.productId }
                ?: del.firstOrNull { it !in used && it.unit == i.unit && same(i, it) }
            if (d == null) {
                if (del.any { same(i, it) } || mentioned(i, delAll)) continue // on a note in another unit: can't compare
                out += Difference(DifferenceKind.NOT_DELIVERED, i.desc, i.productId, i.unit, null, i.qty, null, i.price, i.cents, i.lineId, null)
                continue
            }
            used += d
            val diff = i.qty.subtract(d.qty)
            val tol = d.qty.max(i.qty).multiply(QTY_TOLERANCE).max(BigDecimal("0.001"))
            if (diff.abs() > tol) {
                val money = i.price?.let { p -> diff.abs().multiply(p).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }
                out += Difference(
                    if (diff.signum() > 0) DifferenceKind.MORE_INVOICED else DifferenceKind.LESS_INVOICED,
                    i.desc, i.productId, i.unit, d.qty, i.qty, d.price, i.price, money, i.lineId, d.lineId,
                )
            }
            val dp = d.price
            val ip = i.price
            if (dp != null && ip != null && dp.signum() > 0 && ip > dp.multiply(BigDecimal.ONE.add(PRICE_TOLERANCE))) {
                val money = ip.subtract(dp).multiply(i.qty.min(d.qty)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                out += Difference(DifferenceKind.HIGHER_PRICE, i.desc, i.productId, i.unit, d.qty, i.qty, dp, ip, money, i.lineId, d.lineId)
            }
        }
        for (d in del) {
            if (d in used || inv.any { same(it, d) } || mentioned(d, invAll)) continue
            out += Difference(DifferenceKind.NOT_INVOICED, d.desc, d.productId, d.unit, d.qty, null, d.price, null, d.cents, null, d.lineId)
        }
        return out
    }

    /** (unit, quantity) in kg or l for weights and volumes, otherwise the printed unit; null without a unit. */
    private fun baseQuantity(q: BigDecimal, unit: String?): Pair<String, BigDecimal>? {
        val u = Units.normalize(unit) ?: return null
        val f = Units.factorToBase(u) ?: return u to q
        return Units.baseUnit(Units.dimension(u)!!) to q.multiply(f)
    }

    private fun unitPriceInBase(l: CompareLine): BigDecimal? {
        val q = l.quantity ?: return null
        val u = Units.normalize(l.unit) ?: return null
        val paid = if (l.lineTotalCents != null && q.signum() > 0) ItalianNumbers.centsToDecimal(l.lineTotalCents).divide(q, MC) else l.unitPrice
        if (paid == null || paid.signum() <= 0) return null
        val f = Units.factorToBase(u) ?: return paid
        return paid.divide(f, MC)
    }
}
