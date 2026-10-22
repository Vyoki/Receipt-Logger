package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.OcrLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.random.Random

/**
 * INVENTED e-invoice printouts as a PDF's own text gives them: line and word boxes on a page 2400 units wide. Two
 * published layouts, the same for every supplier:
 * - [Layout.ASSO], the AssoSoftware stylesheet most accounting programs print with: the two parties side by side, the
 *   document header grid, a band of staggered column headings ending in "Prezzo totale", notes under a line,
 *   "RIEPILOGHI IVA E TOTALI" and "Totale documento";
 * - [Layout.SDI], the SdI stylesheet (v1.2.3): labelled lists ("Denominazione: ...") in the stylesheet's block order.
 * A seed varies the column positions, which headings wrap, the letter case, the lines per page, and moves every box
 * by up to [JITTER] units. Every name, VAT number, code, lot and amount is made up. Deterministic for a seed.
 */
object EInvoicePrintGen {

    /**
     * The only VAT numbers used for suppliers, intermediaries and other buyers. Digits 8-10 (the tax office) are
     * "000", which is never assigned, so none of them can belong to a real company; the check digit is valid.
     */
    val VATS: List<String> = listOf(
        "0123456", "0234567", "0345678", "0456789", "0567890", "0678901",
        "0789012", "0890123", "0901234", "1012345", "1123456", "1234567",
    ).map { pIva(it + "000") }

    const val OWN_VAT = DocGen.OWN_VAT
    const val OWN_NAME = "RISTORANTE PROVA SAS"
    const val OTHER_NAME = "TRATTORIA ESEMPIO S.N.C."

    /** Every box moves by up to this many units, in both directions. */
    const val JITTER = 6

    private const val PAGE_TOP = 172
    private const val PAGE_BOTTOM = 3300

    enum class Layout { ASSO, SDI }

    /** A note under a line: "Tipo dato: [type]" and "[ref]: [text]" ("Rif. testo", "Rif. numero", "Rif. data"). */
    data class Note(val type: String, val text: String, val ref: String = "Rif. testo")

    data class Line(
        val desc: String,
        val qty: BigDecimal,
        val price: BigDecimal,
        val unit: String,
        val rate: Int,
        val amountCents: Long,
        val code: String? = null,
        val codeType: String? = null,
        val notes: List<Note> = emptyList(),
        /** The AssoSoftware lot form under the line: "Lotto: X", "Scadenza: ...", "Quantità del suddetto lotto: ...". */
        val lotForm: List<String> = emptyList(),
        /** The lot printed for this line; "" when the document prints notes or lots, but none for this line. */
        val lot: String? = null,
        /** Printed letter by letter ("S A P O N E"), as some programs space their text. */
        val letterSpaced: Boolean = false,
    ) {
        val qtyText: String get() = ItalianNumbers.formatDecimal(qty, qty.scale(), qty.scale())
        val priceText: String get() = ItalianNumbers.formatDecimal(price, price.scale(), price.scale())
    }

    data class Group(val rate: Int, val taxableCents: Long, val taxCents: Long)

    data class Invoice(
        val supplier: String,
        /** The rest of a name too long for one row of the box, printed on the row below. */
        val supplierMore: String? = null,
        val supplierVat: String,
        /** A person: "Cognome nome:" instead of "Denominazione:". */
        val soleTrader: Boolean = false,
        val customer: String = OWN_NAME,
        val customerVat: String = OWN_VAT,
        val docType: String = "TD01",
        val number: String,
        val date: LocalDate,
        val lines: List<Line>,
        /** Stamp duty, discount and rounding printed in the totals row: the total is then not taxable + VAT. */
        val bolloCents: Long = 0,
        val discountCents: Long = 0,
        val roundingCents: Long = 0,
        val intermediary: String? = null,
        val intermediaryVat: String? = null,
        val ddtRefs: List<String> = emptyList(),
        val payment: String? = "MP05 Bonifico",
        /** The total paid in this many instalments, each printed with its own amount. */
        val instalments: Int = 1,
        /** The VAT summary printed from the highest rate down. */
        val ratesDown: Boolean = false,
        /** "goods", "utility" or "fuel". */
        val kind: String = "goods",
    ) {
        val groups: List<Group>
            get() = lines.groupBy { it.rate }.map { (rate, l) ->
                val taxable = l.sumOf { it.amountCents }
                Group(rate, taxable, BigDecimal(taxable).multiply(BigDecimal(rate)).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong())
            }.let { g -> if (ratesDown) g.sortedByDescending { it.rate } else g.sortedBy { it.rate } }
        val taxableCents: Long get() = groups.sumOf { it.taxableCents }
        val taxCents: Long get() = groups.sumOf { it.taxCents }
        val totalCents: Long get() = taxableCents + taxCents + bolloCents - discountCents + roundingCents
        val creditNote: Boolean get() = docType == "TD04" || docType == "TD08"
        val otherBuyer: Boolean get() = customerVat != OWN_VAT
        val sellerName: String get() = listOfNotNull(supplier, supplierMore).joinToString(" ")
        val typeText: String
            get() = when (docType) {
                "TD04" -> "TD04 nota di credito"
                "TD24" -> "TD24 fattura differita di cui all'art.21, comma 4, lett. a)"
                else -> "TD01 fattura"
            }

        /** What is printed, as the bench scores it. */
        fun truth(): DocTruth = DocTruth(
            "einvoice-$kind", sellerName, supplierVat, number, date, taxableCents, taxCents, totalCents,
            lines.map { ItemTruth(it.desc, it.qty, it.unit.lowercase(), it.price, it.amountCents, it.rate, it.lot) },
        )
    }

    /** How an invoice is printed. [repeatHeader] and [pageTotals] make the pages that must not be split. */
    data class Print(
        val layout: Layout = Layout.ASSO,
        /** Seed of the look: column positions, wrapping, letter case, the jitter. */
        val style: Int = 0,
        /** The parties, the document header and the column headings printed again on every page. */
        val repeatHeader: Boolean = false,
        /** "Totale pagina" and "A riportare" at the bottom of each page, "Riporto" at the top of the next. */
        val pageTotals: Boolean = false,
        val linesPerPage: Int? = null,
        /** The VAT summary and totals alone on the last page. */
        val totalsAlone: Boolean = false,
        /** SdI: this body's place (1-based) in a file of [batchSize] invoices; null for a file of one. */
        val inBatch: Int? = null,
        val batchSize: Int = 1,
        /** SdI: "Importo totale documento" is optional in the file. */
        val sdiTotal: Boolean = true,
        val jitter: Int = JITTER,
        /** False: every box also moves up or down on its own, as on a photo (see [Sheet]). */
        val rowsTogether: Boolean = true,
    )

    /** An invoice and its pages. */
    data class Printed(val name: String, val invoice: Invoice, val print: Print, val pages: List<List<OcrLine>>) {
        /** [knownSupplier]: the phone already has this supplier (saved before under its VAT number). */
        fun benchDoc(knownSupplier: Boolean = false): BenchDoc = BenchDoc(
            name, pages, invoice.truth(), OWN_VAT,
            mustReview = when {
                invoice.creditNote -> "a credit note"
                invoice.otherBuyer -> "for another business"
                else -> null
            },
            known = if (knownSupplier) mapOf(invoice.supplierVat to invoice.sellerName) else emptyMap(),
            // A printout is a digital PDF: the app reads it from the PDF's own text.
            textLayer = true,
        )
    }

    /** One invoice of a file of many, and the pages it really covers. */
    data class Part(val invoice: Invoice, val print: Print, val pages: IntRange)

    data class Bundle(val pages: List<List<OcrLine>>, val parts: List<Part>, val cover: IntRange?)

    // ------------------------------------------------------------------ invented content

    private val WORDS = listOf(
        "VERDE", "ALFA", "BETA", "DELTA", "SOLE", "MARE", "MONTE", "VALLE", "FIUME", "AURORA", "CAMPO", "PRATO", "LAGO",
    )
    private val TRADES = listOf("INGROSSO", "ALIMENTARI", "DISTRIBUZIONE", "CARNI", "ORTOFRUTTA", "CASEIFICIO", "BEVANDE", "FORNITURE")
    private val FORMS = listOf("S.r.l.", "S.R.L.", "SRL", "S.p.A.", "S.P.A.", "S.n.c.", "S.a.s.")
    private val SURNAMES = listOf("BIANCHI", "VERDI", "NERI", "GIALLI", "CELESTI", "MARRONI")
    private val NAMES = listOf("LUCA", "ANNA", "PAOLO", "SARA", "MARCO", "ELENA")
    private val STREETS = listOf("VIA DI PROVA", "VIA ESEMPIO", "VIALE EUROPA", "VIA DELLE INDUSTRIE", "PIAZZA DI PROVA", "VIA DEL LAVORO")
    private val TOWNS = listOf("ROMA" to "RM", "MILANO" to "MI", "TORINO" to "TO", "FIRENZE" to "FI", "NAPOLI" to "NA")
    private val MONTHS = listOf(
        "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno", "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
    )

    private class Product(val name: String, val unit: String, val rate: Int, val low: Double, val high: Double, val weighed: Boolean)

    private val GOODS = listOf(
        Product("PASSATA DI POMODORO 700G", "PZ", 4, 0.8, 1.6, false),
        Product("POMODORI PELATI KG 2,5", "PZ", 4, 2.0, 4.0, false),
        Product("MOZZARELLA FIOR DI LATTE 125G", "PZ", 4, 0.6, 1.2, false),
        Product("OLIO EXTRAVERGINE DI OLIVA LT 1", "PZ", 4, 6.0, 9.0, false),
        Product("FARINA TIPO 00 KG 25", "PZ", 4, 14.0, 22.0, false),
        Product("SPAGHETTI DI SEMOLA KG 1", "PZ", 4, 1.1, 2.2, false),
        Product("PETTO DI POLLO", "KG", 10, 6.0, 9.5, true),
        Product("FILETTO DI SALMONE FRESCO", "KG", 10, 14.0, 24.0, true),
        Product("PROSCIUTTO CRUDO STAGIONATO", "KG", 10, 12.0, 22.0, true),
        Product("SALSICCIA DI SUINO", "KG", 10, 6.0, 9.0, true),
        Product("PANCETTA TESA", "KG", 10, 8.0, 11.0, true),
        Product("PECORINO STAGIONATO", "KG", 4, 12.0, 18.0, true),
        Product("BURRO 1KG", "PZ", 4, 6.0, 10.0, false),
        Product("UOVA FRESCHE X30", "CF", 4, 4.5, 8.0, false),
        Product("ZUCCHINE", "KG", 4, 1.2, 2.8, true),
        Product("PATATE", "KG", 4, 0.7, 1.5, true),
        Product("LIMONI", "KG", 4, 1.5, 2.8, true),
        Product("ZUCCHERO SEMOLATO KG 1", "PZ", 10, 0.9, 1.6, false),
        Product("BISCOTTI FROLLINI 700G", "PZ", 10, 2.5, 4.5, false),
        Product("ACQUA MINERALE NATURALE 50CL X24", "CT", 22, 3.0, 6.0, false),
        Product("BIRRA CHIARA 33CL X24", "CT", 22, 18.0, 30.0, false),
        Product("VINO ROSSO DA TAVOLA LT 5", "PZ", 22, 8.0, 14.0, false),
        Product("CAFFE IN GRANI KG 1", "PZ", 22, 12.0, 22.0, false),
        Product("DETERSIVO PIATTI LT 5", "PZ", 22, 6.0, 13.0, false),
        Product("TOVAGLIOLI DI CARTA 33X33 X100", "CF", 22, 1.5, 3.0, false),
        Product("CARTA FORNO 40CM X 50M", "PZ", 22, 4.0, 8.0, false),
        Product("MOZZARELLA DI BUFALA CAMPANA DOP 250G CONFEZIONE DA 8 PEZZI", "CF", 4, 14.0, 20.0, false),
        Product("PISELLI FINI SURGELATI BUSTA DA 2,5 KG PER RISTORAZIONE", "PZ", 10, 5.0, 9.0, false),
    )
    private val NOTES = listOf(
        Note("ORIGINE", "UE"), Note("CONAI", "CONTRIBUTO AMBIENTALE ASSOLTO"), Note("PROMO", "SCONTO IN MERCE"),
        Note("NUM.COLLI", "12,00", "Rif. numero"), Note("DATA ORDINE", "03-02-2031", "Rif. data"),
    )

    /** A valid Partita IVA from its first ten digits (the check digit added). */
    fun pIva(first10: String): String {
        require(first10.length == 10 && first10.all(Char::isDigit))
        var sum = 0
        for ((i, c) in first10.withIndex()) {
            val d = c - '0'
            sum += if (i % 2 == 0) d else (d * 2).let { if (it > 9) it - 9 else it }
        }
        return first10 + ((10 - sum % 10) % 10)
    }

    fun money(cents: Long): String = ItalianNumbers.formatDecimal(BigDecimal(cents).movePointLeft(2), 2, 2)
    private fun rate(r: Int) = "$r,00"
    private fun dmy(d: LocalDate) = "%02d-%02d-%04d".format(d.dayOfMonth, d.monthValue, d.year)

    private fun amount(q: BigDecimal, p: BigDecimal) = q.multiply(p).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()

    private fun price(r: Random, low: Double, high: Double, scale: Int) =
        BigDecimal(low + r.nextDouble() * (high - low)).setScale(scale, RoundingMode.HALF_UP)

    private fun lotCode(r: Random): String = when (r.nextInt(4)) {
        0 -> "L${r.nextInt(1000, 9999)}"
        1 -> "${('A'..'Z').random(r)}${('A'..'Z').random(r)}${r.nextInt(100, 999)}"
        2 -> "${r.nextInt(100000, 999999)}"
        else -> "${r.nextInt(100, 999)}/${r.nextInt(10, 99)}"
    }

    private fun number(r: Random): String = when (r.nextInt(7)) {
        0 -> "${r.nextInt(1, 9999)}"
        1 -> "${r.nextInt(1, 999)}/2031"
        2 -> "FT/2031/${"%04d".format(r.nextInt(1, 9999))}"
        3 -> "${r.nextInt(1, 999)}/${('A'..'Z').random(r)}"
        4 -> "${r.nextInt(1, 9)}C ${"%09d".format(r.nextInt(1, 99999))}"
        5 -> "A2031-${"%05d".format(r.nextInt(1, 99999))}"
        else -> "V2-${r.nextInt(100, 99999)}"
    }

    private fun company(r: Random, trade: String): String = buildString {
        append(WORDS[r.nextInt(WORDS.size)]).append(' ')
        if (r.nextBoolean()) append(WORDS[r.nextInt(WORDS.size)]).append(' ')
        append(trade).append(' ').append(FORMS[r.nextInt(FORMS.size)])
    }

    // ------------------------------------------------------------------ invoices

    /**
     * An invented invoice for [seed]. [kind]: "goods" (with the layout's optional parts chosen by the seed), "utility"
     * (an electricity or gas bill: kWh or Smc, POD/PDR, excise) or "fuel" (CARB codes, 2710xxxx, litres). Every amount
     * has 2 decimals. [plain]: no credit note, other buyer, stamp duty, discount or rounding.
     */
    fun invoice(seed: Int, kind: String = "goods", lineCount: Int? = null, plain: Boolean = false): Invoice {
        val r = Random(seed * 7919 + 13)
        val vatAt = r.nextInt(VATS.size)
        val date = LocalDate.of(2031, 1 + r.nextInt(12), 1 + r.nextInt(28))
        val sole = kind == "goods" && r.nextInt(7) == 0
        val name = when {
            sole -> "${SURNAMES[r.nextInt(SURNAMES.size)]} ${NAMES[r.nextInt(NAMES.size)]}"
            kind == "utility" -> company(r, listOf("ENERGIA", "LUCE E GAS", "SERVIZI ENERGETICI")[r.nextInt(3)])
            kind == "fuel" -> company(r, listOf("CARBURANTI", "PETROLI", "STAZIONE DI SERVIZIO")[r.nextInt(3)])
            else -> company(r, TRADES[r.nextInt(TRADES.size)])
        }
        // A name that does not fit its row goes on to the next one.
        val more = if (!sole && kind == "goods" && r.nextInt(10) == 0) "${SURNAMES[r.nextInt(SURNAMES.size)]} & C." else null
        val lines = when (kind) {
            "utility" -> utilityLines(r)
            "fuel" -> fuelLines(r)
            else -> goodsLines(r, lineCount ?: (2 + r.nextInt(12)))
        }
        val other = !plain && r.nextInt(14) == 0
        val credit = !plain && kind == "goods" && r.nextInt(12) == 0
        val taxable = lines.sumOf { it.amountCents }
        val withIntermediary = r.nextInt(6) == 0
        return Invoice(
            supplier = name, supplierMore = more, supplierVat = VATS[vatAt], soleTrader = sole,
            customer = if (other) OTHER_NAME else OWN_NAME,
            customerVat = if (other) VATS[(vatAt + 5) % VATS.size] else OWN_VAT,
            docType = if (credit) "TD04" else if (r.nextInt(3) == 0) "TD24" else "TD01",
            number = number(r), date = date, lines = lines,
            bolloCents = if (!plain && r.nextInt(8) == 0) 200 else 0,
            discountCents = if (!plain && r.nextInt(10) == 0) maxOf(1L, taxable / 50) else 0,
            roundingCents = if (!plain && r.nextInt(10) == 0) listOf(-2L, -1L, 1L, 2L)[r.nextInt(4)] else 0,
            intermediary = if (withIntermediary) "SERVIZI FATTURE ESEMPIO S.R.L." else null,
            intermediaryVat = if (withIntermediary && r.nextBoolean()) VATS[(vatAt + 7) % VATS.size] else null,
            ddtRefs = if (kind == "goods" && r.nextInt(3) == 0) {
                List(1 + r.nextInt(2)) { "DDT ${r.nextInt(10, 99)}/ZZ del ${dmy(date.minusDays(1L + r.nextInt(20)))}" }
            } else emptyList(),
            payment = when (kind) {
                "utility" -> "MP16 Domiciliazione bancaria"
                "fuel" -> "MP08 Carta di pagamento"
                else -> listOf("MP05 Bonifico", "MP12 RIBA", "MP01 Contanti", null)[r.nextInt(4)]
            },
            instalments = if (kind == "goods" && r.nextInt(8) == 0) 2 else 1,
            ratesDown = r.nextBoolean(),
            kind = kind,
        )
    }

    private fun goodsLines(r: Random, n: Int): List<Line> {
        val qtyScale = 2 + r.nextInt(2)
        val priceScale = 2 + r.nextInt(3)
        val codeType = listOf("INTERNO", "FORNITORE", "Cod.Forn.", "EAN", null)[r.nextInt(5)]
        val codes = codeType != null || r.nextBoolean()
        val lots = r.nextInt(3) == 0
        val lotForm = lots && r.nextBoolean()
        val notes = r.nextInt(4) == 0
        val spaced = r.nextInt(10) == 0
        return List(n) { i ->
            val p = GOODS[r.nextInt(GOODS.size)]
            val qty = if (p.weighed) BigDecimal(r.nextInt(300, 30000)).movePointLeft(3).setScale(qtyScale, RoundingMode.HALF_UP)
            else BigDecimal(1 + r.nextInt(if (r.nextInt(3) == 0) 24 else 6)).setScale(qtyScale)
            val price = price(r, p.low, p.high, priceScale)
            val lot = if (lots && r.nextInt(8) > 0) lotCode(r) else null
            val note = if (notes && r.nextInt(3) == 0) NOTES[r.nextInt(NOTES.size)] else null
            Line(
                desc = p.name, qty = qty, price = price, unit = p.unit, rate = p.rate, amountCents = amount(qty, price),
                code = if (!codes) null else if (codeType == "EAN") "2000000${r.nextInt(100000, 999999)}" else "${r.nextInt(1, 9)}${"%04d".format(i * 37 + 11)}",
                codeType = codeType,
                notes = listOfNotNull(if (lot != null && !lotForm) Note("LOTTO", lot) else null, note),
                lotForm = if (lot != null && lotForm) {
                    listOf("Lotto: $lot", "Scadenza: ${"%02d".format(1 + r.nextInt(28))}-${"%02d".format(1 + r.nextInt(12))}-2032", "Quantità del suddetto lotto: ${ItalianNumbers.formatDecimal(qty, 2, 2)}")
                } else emptyList(),
                // A lot is scored where lots or notes are printed: none may be made up for a line without one.
                lot = lot ?: if (lots || note != null) "" else null,
                letterSpaced = spaced,
            )
        }
    }

    private fun utilityLines(r: Random): List<Line> {
        val gas = r.nextBoolean()
        val unit = if (gas) "SMC" else "KWH"
        val pod = if (gas) "PDR: 0088${r.nextInt(1000000, 9999999)}000" else "POD: IT001E0000${r.nextInt(1000, 9999)}"
        val a = BigDecimal(r.nextInt(200000, 2000000)).movePointLeft(3)
        val b = BigDecimal(r.nextInt(100000, 900000)).movePointLeft(3)
        val month = BigDecimal("1.00")
        fun l(desc: String, q: BigDecimal, unit: String, p: String, note: Note? = null): Line {
            val price = BigDecimal(p)
            return Line(desc, q, price, unit, 22, amount(q, price), notes = listOfNotNull(note), lot = "")
        }
        val material = if (gas) "Spesa per la materia gas naturale" else "Spesa per la materia energia"
        return listOfNotNull(
            l("$material F1", a, unit, if (gas) "0.95" else "0.21", Note(pod.substringBefore(':'), pod.substringAfter(": "))),
            if (gas) null else l("$material F2", b, unit, "0.18"),
            l("Spesa per il trasporto e la gestione del contatore", month, "MESE", "${r.nextInt(18, 40)}.00"),
            l("Oneri di sistema", if (gas) a else a.add(b), unit, "0.04"),
            l(if (gas) "Accisa sul gas naturale" else "Accisa sull'energia elettrica", if (gas) a else a.add(b), unit, if (gas) "0.17" else "0.01"),
        )
    }

    private fun fuelLines(r: Random): List<Line> = List(1 + r.nextInt(3)) {
        val diesel = r.nextInt(3) > 0
        val qty = BigDecimal(r.nextInt(2000, 9000)).movePointLeft(2)
        val price = price(r, 1.65, 1.95, 2)
        Line(
            if (diesel) "GASOLIO AUTOTRAZIONE" else "BENZINA SENZA PIOMBO", qty, price, "LT", 22, amount(qty, price),
            code = if (diesel) "27109901" else "27109902", codeType = "CARB",
            notes = listOf(Note("TARGA", "${('A'..'Z').random(r)}${('A'..'Z').random(r)}${r.nextInt(100, 999)}ZZ")), lot = "",
        )
    }

    /**
     * The generator's own arithmetic: the lines of each rate add up to its taxable amount, its VAT is the rate on it,
     * and taxable + VAT = total unless a stamp duty, discount or rounding is printed (then exactly those apart).
     */
    fun check(inv: Invoice): List<String> {
        val out = mutableListOf<String>()
        for (l in inv.lines) if (amount(l.qty, l.price) != l.amountCents) out += "line ${l.desc}: qty x price is not its amount"
        for (g in inv.groups) {
            val sum = inv.lines.filter { it.rate == g.rate }.sumOf { it.amountCents }
            if (sum != g.taxableCents) out += "rate ${g.rate}: lines $sum, taxable ${g.taxableCents}"
            val vat = BigDecimal(g.taxableCents * g.rate).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()
            if (vat != g.taxCents) out += "rate ${g.rate}: VAT ${g.taxCents}, expected $vat"
        }
        val declared = inv.bolloCents - inv.discountCents + inv.roundingCents
        if (inv.taxableCents + inv.taxCents + declared != inv.totalCents) out += "taxable + VAT + declared rows is not the total"
        if (declared == 0L && inv.taxableCents + inv.taxCents != inv.totalCents) out += "taxable + VAT is not the total"
        if (inv.totalCents <= 0) out += "total not above zero"
        if (inv.supplierVat !in VATS) out += "supplier VAT not from the invented list"
        if (inv.otherBuyer && inv.customerVat !in VATS) out += "other buyer's VAT not from the invented list"
        return out
    }

    // ------------------------------------------------------------------ what the bench scores

    /** An invoice printed alone, as the bench reads it. */
    fun single(seed: Int, kind: String = "goods", layout: Layout = Layout.ASSO): Printed {
        val inv = invoice(seed, kind)
        val print = Print(layout, style = seed, totalsAlone = Random(seed).nextInt(10) == 0)
        return Printed("printout-${if (layout == Layout.SDI) "sdi-" else ""}$seed-$kind", inv, print, pages(inv, print))
    }

    /**
     * One invoice of 2-8 pages that must never be split: the parties, the document header and the column headings
     * printed again on every page, "Totale pagina" / "A riportare" running totals, the document total only on the
     * last page.
     */
    fun multiPageSingle(seed: Int, layout: Layout): Printed {
        val r = Random(seed * 97 + 5)
        val want = 2 + r.nextInt(7)
        var perPage = if (layout == Layout.SDI) 2 + r.nextInt(3) else 3 + r.nextInt(6)
        while (true) {
            val inv = invoice(seed + 5000, "goods", lineCount = (want - 1) * perPage + 1 + r.nextInt(perPage), plain = true)
            val print = Print(layout, style = seed + 5000, repeatHeader = true, pageTotals = true, linesPerPage = perPage)
            val pages = pages(inv, print)
            if (pages.size in 2..8 || perPage == 1) return Printed("printout-pages-$seed-${layout.name.lowercase()}", inv, print, pages)
            perPage--
        }
    }

    /** The fixed set of [multiPageSingle]s, in both layouts. */
    fun multiPageSingles(): List<Printed> = Layout.entries.flatMap { l -> (1..12).map { multiPageSingle(it, l) } }

    /**
     * The bench group "e-invoice printout": AssoSoftware singles of every kind, the multi-page ones, and SdI singles.
     * On even seeds the phone already knows the supplier, so the app may save the invoice by itself. The SdI
     * [multiPageSingle]s are left out: the SdI stylesheet never repeats its header, they only test the splitting.
     */
    fun benchDocs(): List<BenchDoc> {
        val asso = (1..100).map { it to single(it) } + (101..106).map { it to single(it, "utility") } +
            (111..116).map { it to single(it, "fuel") } + (1..12).map { it to multiPageSingle(it, Layout.ASSO) }
        val sdi = (201..230).map { it to single(it, layout = Layout.SDI) }
        return (asso + sdi).map { (seed, p) -> p.benchDoc(knownSupplier = seed % 2 == 0) }
    }

    // ------------------------------------------------------------------ files of many invoices

    /**
     * [n] invoices printed one after another into one file, with the pages each really covers: sometimes a cover page
     * first, the same supplier twice in a row, invoices running over several pages, totals alone on a last page. In
     * the SdI layout some files hold a batch of 2-3 invoices of one supplier ("Numero documento nel lotto"); each
     * invoice starts on a new page.
     */
    fun bundle(seed: Int, n: Int, layout: Layout = Layout.ASSO): Bundle {
        val r = Random(seed * 31 + 7)
        val pages = mutableListOf<List<OcrLine>>()
        val parts = mutableListOf<Part>()
        var cover: IntRange? = null
        if (r.nextInt(3) == 0) {
            pages += coverPage(r, n)
            cover = 0..0
        }
        var i = 0
        while (parts.size < n) {
            val kind = when (r.nextInt(10)) { 0 -> "utility"; 1 -> "fuel"; else -> "goods" }
            val long = kind == "goods" && r.nextInt(6) == 0
            var inv = invoice(seed * 1000 + i, kind, lineCount = if (long) 25 + r.nextInt(30) else null)
            val prev = parts.lastOrNull()?.invoice
            if (prev != null && r.nextInt(4) == 0) {
                inv = inv.copy(supplier = prev.supplier, supplierMore = prev.supplierMore, supplierVat = prev.supplierVat, soleTrader = prev.soleTrader)
            }
            val batch = if (layout == Layout.SDI && r.nextInt(4) == 0) minOf(2 + r.nextInt(2), n - parts.size) else 1
            for (b in 1..batch) {
                val body = if (b == 1) inv else invoice(seed * 1000 + i + 500 * b, kind).copy(
                    supplier = inv.supplier, supplierMore = inv.supplierMore, supplierVat = inv.supplierVat, soleTrader = inv.soleTrader,
                    customer = inv.customer, customerVat = inv.customerVat, intermediary = inv.intermediary, intermediaryVat = inv.intermediaryVat,
                )
                val print = Print(
                    layout, style = seed * 1000 + i, totalsAlone = r.nextInt(6) == 0,
                    inBatch = if (batch > 1) b else null, batchSize = batch, sdiTotal = r.nextInt(5) > 0,
                )
                val p = pages(body, print)
                parts += Part(body, print, pages.size until pages.size + p.size)
                pages += p
            }
            i++
        }
        return Bundle(pages, parts, cover)
    }

    private fun coverPage(r: Random, n: Int): List<OcrLine> {
        val s = Sheet(r, JITTER)
        s.page()
        s += t(620, 400, "ELENCO FATTURE DI ACQUISTO", 30, 52)
        s += t(300, 600, "Periodo: dal 01-03-2031 al 31-03-2031")
        s += t(300, 660, "Intestatario: $OWN_NAME")
        s += t(300, 720, "Partita IVA: $OWN_VAT")
        s += t(300, 780, "Numero documenti: $n")
        return s.pages.single()
    }

    // ------------------------------------------------------------------ the pages

    fun pages(inv: Invoice, print: Print = Print()): List<List<OcrLine>> = when (print.layout) {
        Layout.ASSO -> asso(inv, print)
        Layout.SDI -> sdi(inv, print)
    }

    /** The look of one document. */
    private class Style(seed: Int) {
        val r = Random(seed * 131 + 17)
        val upper = r.nextInt(3) > 0
        val cw = 15 + r.nextInt(4)
        val leftX = 120 + r.nextInt(16)
        val rightX = 1280 + r.nextInt(20)
        val partyGap = 46 + r.nextInt(8)
        val codeX = 80 + r.nextInt(10)
        val descX = 610 + r.nextInt(80)
        val qtyR = 1320 + r.nextInt(60)
        val priceR = 1640 + r.nextInt(60)
        val umX = 1715 + r.nextInt(30)
        val vatR = 2050 + r.nextInt(40)
        val amountR = 2350 + r.nextInt(30)

        /** Which headings wrap onto two rows: at least one in each band, so the bands are staggered. */
        private fun wraps(n: Int): List<Boolean> = List(n) { r.nextBoolean() }.let { w -> if (w.none { it }) w.toMutableList().also { it[r.nextInt(n)] = true } else w }
        val grid = wraps(5)
        val band = wraps(3)

        /** Characters of the description that fit before the quantity column. */
        val descChars = (qtyR - 9 * cw - descX) / cw
        fun h(s: String) = if (upper) s.uppercase() else s
    }

    /**
     * The pages being laid out. Every box moves by up to [jitter] units: sideways on its own; up or down with the
     * other boxes of its printed row (a PDF prints a row on one baseline) plus a little on its own. With [rowsTogether]
     * false every box also moves up or down on its own, as on a photo.
     */
    private class Sheet(val r: Random, val jitter: Int, val rowsTogether: Boolean = true) {
        val pages = mutableListOf<MutableList<OcrLine>>()
        var y = 0
        private var rowDy: Int? = null
        fun page() { pages += mutableListOf<OcrLine>() }
        private fun shift(n: Int) = r.nextInt(-n, n + 1)

        /** The boxes added in [f] are one printed row. */
        fun row(f: () -> Unit) {
            rowDy = if (rowsTogether) shift(jitter - jitter / 3) else null
            f()
            rowDy = null
        }

        operator fun plusAssign(l: OcrLine) {
            val dx = shift(jitter)
            val dy = rowDy?.let { it + shift(jitter / 3) } ?: shift(jitter)
            fun m(o: OcrLine) = o.copy(left = o.left + dx, top = o.top + dy, right = o.right + dx, bottom = o.bottom + dy)
            pages.last() += m(l).copy(words = l.words.map(::m))
        }
    }

    /** One text line, its words spread over its box ([cw] units per character). */
    private fun t(x: Int, y: Int, text: String, cw: Int = 20, h: Int = 34): OcrLine {
        var at = x
        val words = text.split(' ').filter { it.isNotEmpty() }.map { w -> OcrLine(w, at, y, at + w.length * cw, y + h).also { at += (w.length + 1) * cw } }
        return OcrLine(text, x, y, x + text.length * cw, y + h, words = if (words.size > 1) words else emptyList())
    }

    /** Right-aligned at [right]. */
    private fun rt(right: Int, y: Int, text: String, cw: Int = 20, h: Int = 34) = t(right - text.length * cw, y, text, cw, h)

    private fun small(x: Int, y: Int, text: String) = t(x, y, text, 12, 22)

    /** Letter by letter, a space as wide as a letter. */
    private fun spaced(x: Int, y: Int, text: String): OcrLine {
        val words = mutableListOf<OcrLine>()
        var at = x
        for (c in text) {
            if (c != ' ') words += OcrLine(c.toString(), at, y, at + 24, y + 34)
            at += 33
        }
        return OcrLine(words.joinToString(" ") { it.text }, x, y, at, y + 34, words = words)
    }

    /** Words in rows of at most [max] characters. */
    private fun wrap(text: String, max: Int): List<String> {
        val rows = mutableListOf<String>()
        var cur = ""
        for (w in text.split(' ')) {
            cur = if (cur.isEmpty()) w else if (cur.length + 1 + w.length <= max) "$cur $w" else { rows += cur; w }
        }
        if (cur.isNotEmpty()) rows += cur
        return rows
    }

    // ------------------------------------------------------------------ AssoSoftware

    private fun asso(inv: Invoice, p: Print): List<List<OcrLine>> {
        val s = Style(p.style)
        val sh = Sheet(s.r, p.jitter, p.rowsTogether)
        var onPage = 0
        var pageSum = 0L
        var carried = 0L
        val reserve = if (p.pageTotals) 140 else 0
        fun open(first: Boolean) {
            sh.page(); sh.y = PAGE_TOP; onPage = 0; pageSum = 0
            if (first || p.repeatHeader) assoHeader(sh, s, inv)
        }
        fun labelled(label: String, cents: Long, x: Int, gap: Int) {
            sh.row { sh += t(x, sh.y, s.h(label), 17); sh += rt(s.amountR, sh.y, money(cents)) }
            sh.y += gap
        }
        fun pageTotals(last: Boolean) {
            if (!p.pageTotals) return
            carried += pageSum
            sh.y += 24
            labelled("Totale pagina", pageSum, s.priceR - 200, 46)
            if (!last) labelled("A riportare", carried, s.priceR - 200, 46)
        }
        open(true)
        assoBand(sh, s)
        for (ref in inv.ddtRefs) { sh += t(s.descX - 10, sh.y, ref, 15, 26); sh.y += 35 }
        for (line in inv.lines) {
            val rows = if (line.letterSpaced) wrap(line.desc, (s.qtyR - 9 * s.cw - s.descX) / 33) else wrap(line.desc, s.descChars)
            val height = 36 * rows.size + line.notes.size * 69 + line.lotForm.size * 36 + 22
            if (sh.y + height > PAGE_BOTTOM - reserve || (p.linesPerPage != null && onPage >= p.linesPerPage)) {
                pageTotals(false)
                open(false)
                assoBand(sh, s)
                if (p.pageTotals) labelled("Riporto", carried, s.descX, 52)
            }
            assoLine(sh, s, line, rows)
            onPage++
            pageSum += line.amountCents
        }
        val summary = 60 + 44 + 38 + inv.groups.size * 58 + 46 + 121 + 46 * (inv.instalments + 1)
        if (p.totalsAlone || sh.y + summary + reserve > PAGE_BOTTOM) {
            pageTotals(false)
            open(false)
            if (p.pageTotals) labelled("Riporto", carried, s.descX, 52)
        } else {
            pageTotals(true)
        }
        assoTotals(sh, s, inv)
        if (p.repeatHeader) {
            val n = sh.pages.size
            sh.pages.indices.forEach { i -> sh.pages[i] += t(2050, 3380, "Pagina ${i + 1} di $n", 14, 24) }
        }
        return sh.pages
    }

    private fun assoHeader(sh: Sheet, s: Style, inv: Invoice) {
        var y = sh.y
        sh.row {
            sh += t(s.leftX - 26, y, "Cedente/prestatore (fornitore)", 19, 42)
            sh += t(s.rightX - 26, y, "Cessionario/committente (cliente)", 19, 42)
        }
        y += 62
        val (street, town) = STREETS[inv.supplierVat.hashCode().mod(STREETS.size)] to TOWNS[inv.supplierVat.hashCode().mod(TOWNS.size)]
        val left = mutableListOf("Identificativo fiscale ai fini IVA: IT${inv.supplierVat}")
        if (inv.soleTrader) {
            val (surname, name) = inv.supplier.split(' ').let { it[0] to it[1] }
            // An invented tax code that cannot be anyone's: day 00, place Z000.
            left += "Codice fiscale: ${(surname.filter { it !in "AEIOU" } + "XXX").take(3)}${(name.filter { it !in "AEIOU" } + "XXX").take(3)}00A00Z000X"
            left += "Cognome nome: ${inv.supplier}"
        } else {
            left += "Codice fiscale: ${inv.supplierVat}"
            left += "Denominazione: ${inv.supplier}"
        }
        inv.supplierMore?.let { left += it }
        left += listOf(
            "Regime fiscale: RF01 ordinario", "Indirizzo: $street ${inv.supplierVat.takeLast(2).trimStart('0').ifEmpty { "1" }}",
            "Comune: ${town.first} Provincia: ${town.second}", "Cap: 00100 Nazione: IT",
        )
        val right = listOf(
            "Identificativo fiscale ai fini IVA: IT${inv.customerVat}", "Codice fiscale: ${inv.customerVat}", "Denominazione: ${inv.customer}",
            "Indirizzo: VIA ESEMPIO 1", "Comune: ROMA Provincia: RM", "Cap: 00100 Nazione: IT",
        )
        for (i in 0 until maxOf(left.size, right.size)) {
            sh.row {
                left.getOrNull(i)?.let { sh += t(s.leftX, y, it, 18) }
                right.getOrNull(i)?.let { sh += t(s.rightX, y, it, 18) }
            }
            y += s.partyGap
        }
        inv.intermediary?.let { name ->
            y += 12
            sh += t(s.leftX - 26, y, "Terzo intermediario o soggetto emittente", 19, 42); y += 62
            inv.intermediaryVat?.let { sh += t(s.leftX, y, "Identificativo fiscale ai fini IVA: IT$it", 18); y += s.partyGap }
            sh += t(s.leftX, y, "Denominazione: $name", 18); y += s.partyGap
        }
        // The document header: one-row labels in the middle row, wrapped ones above and below it.
        y += 42
        val gy = y
        fun label(wrap: Boolean, x: Int, top: String, bottom: String) {
            if (wrap) {
                val w = maxOf(top.length, bottom.length)
                sh += small(x + (w - top.length) * 6, gy, s.h(top)); sh += small(x + (w - bottom.length) * 6, gy + 25, s.h(bottom))
            } else sh += small(x, gy + 12, s.h("$top $bottom"))
        }
        sh.row {
            label(s.grid[0], 268, "Tipologia", "documento")
            label(s.grid[1], 759, "Art.", "73")
            label(s.grid[2], 1054, "Numero", "documento")
            label(s.grid[3], 1544, "Data", "documento")
            label(s.grid[4], 1932, "Codice", "destinatario")
        }
        y += 71
        val typeRows = wrap(inv.typeText, 36)
        sh.row {
            sh += t(84, y, typeRows[0], 17)
            sh += t(1171 - inv.number.length * 10, y, inv.number)
            sh += t(1526, y, dmy(inv.date), 17)
            sh += t(1979, y, "ABC1234", 21)
        }
        typeRows.drop(1).forEachIndexed { i, row -> sh += t(84, y + (i + 1) * 30, row, 17) }
        sh.y = y + 30 * (typeRows.size - 1) + 204
    }

    private fun assoBand(sh: Sheet, s: Style) {
        val y = sh.y
        fun label(wrap: Boolean, x: Int, top: String, bottom: String?) {
            if (wrap && bottom != null) {
                val w = maxOf(top.length, bottom.length)
                sh += small(x + (w - top.length) * 6, y, s.h(top)); sh += small(x + (w - bottom.length) * 6, y + 25, s.h(bottom))
            } else sh += small(x, y + 12, s.h(listOfNotNull(top, bottom).joinToString(" ")))
        }
        sh.row {
            label(s.band[0], s.codeX + 175, "Cod.", "articolo")
            label(false, s.descX + 245, "Descrizione", null)
            label(false, s.qtyR - 20, "Quantità", null)
            label(s.band[1], s.priceR - 136, "Prezzo", "unitario")
            label(false, s.umX + 6, "UM", null)
            label(s.band[2], s.umX + 112, "Sconto", "o magg.")
            label(false, s.vatR - 70, "%IVA", null)
            label(false, s.amountR - 251, "Prezzo totale", null)
        }
        sh.y = y + 70
    }

    private fun assoLine(sh: Sheet, s: Style, line: Line, rows: List<String>) {
        val y = sh.y
        fun desc(i: Int) = if (line.letterSpaced) spaced(s.descX, y + i * 36, rows[i]) else t(s.descX, y + i * 36, rows[i], s.cw, 30)
        sh.row {
            line.code?.let { c -> sh += t(s.codeX, y + 1, if (line.codeType != null) "$c ( ${line.codeType} )" else c, 16, 26) }
            sh += desc(0)
            sh += rt(s.qtyR, y, line.qtyText, s.cw, 30)
            sh += rt(s.priceR, y, line.priceText, s.cw, 30)
            sh += t(s.umX, y, line.unit, s.cw, 30)
            sh += rt(s.vatR, y, rate(line.rate), s.cw, 30)
            sh += rt(s.amountR, y, money(line.amountCents), s.cw, 30)
        }
        for (i in 1 until rows.size) sh += desc(i)
        var ny = y + 36 * rows.size + 4
        for (n in line.notes) {
            sh += t(s.descX, ny, "Tipo dato: ${n.type}", 14, 26)
            sh += t(s.descX, ny + 34, "${n.ref}: ${n.text}", 14, 26)
            ny += 69
        }
        for (row in line.lotForm) { sh += t(s.descX, ny, row, 14, 26); ny += 36 }
        sh.y = maxOf(ny + 18, y + 58)
    }

    private fun assoTotals(sh: Sheet, s: Style, inv: Invoice) {
        var y = sh.y + 60
        sh += small(1095, y, s.h("Riepiloghi IVA e totali")); y += 44
        sh.row {
            sh += small(240, y, s.h("Esigibilità IVA / riferimenti normativi")); sh += small(890, y, s.h("%IVA")); sh += small(1007, y, s.h("Spese accessorie"))
            sh += small(1264, y, s.h("Arr.")); sh += small(1493, y, s.h("Totale imponibile")); sh += small(2032, y, s.h("Totale imposta"))
        }
        y += 38
        for (g in inv.groups) {
            sh.row {
                sh += t(84, y, "I (esigibilità immediata)", 17)
                sh += rt(960, y, rate(g.rate))
                sh += rt(1840, y, money(g.taxableCents))
                sh += rt(2366, y, money(g.taxCents))
            }
            y += 58
        }
        sh.row {
            sh += small(238, y, s.h("Importo bollo")); sh += small(779, y, s.h("Sconto/Maggiorazione")); sh += small(1264, y, s.h("Arr."))
            sh += small(1748, y, s.h("Totale documento"))
        }
        y += 46
        sh.row {
            if (inv.bolloCents != 0L) sh += rt(560, y, money(inv.bolloCents))
            if (inv.discountCents != 0L) sh += rt(1180, y, money(-inv.discountCents))
            if (inv.roundingCents != 0L) sh += rt(1440, y, money(inv.roundingCents))
            sh += rt(2366, y, money(inv.totalCents))
        }
        y += 121
        sh.row {
            sh += small(318, y, s.h("Modalità pagamento")); sh += small(1065, y, s.h("Dettagli")); sh += small(1462, y, s.h("Scadenze")); sh += small(1958, y, s.h("Importo"))
        }
        inv.payment?.let { mode ->
            val first = if (inv.instalments == 2) inv.totalCents / 2 else inv.totalCents
            listOfNotNull(first, if (inv.instalments == 2) inv.totalCents - first else null).forEachIndexed { i, amount ->
                y += 46
                sh.row {
                    sh += t(84, y, mode, 17)
                    sh += t(1462, y, dmy(inv.date.plusDays(30L * (i + 1))), 17)
                    sh += rt(2366, y, money(amount))
                }
            }
        }
        sh.y = y + 60
    }

    // ------------------------------------------------------------------ SdI

    /** A row of the SdI page: [size] 0 the title, 1 a block heading, 2 a sub-heading, 3 an item. */
    private class Row(val level: Int, val text: String, val size: Int)

    private fun sdiDate(d: LocalDate) = "$d (${"%02d".format(d.dayOfMonth)} ${MONTHS[d.monthValue - 1]} ${d.year})"
    private fun xml(v: BigDecimal) = v.toPlainString()
    private fun xmlCents(c: Long) = BigDecimal(c).movePointLeft(2).toPlainString()

    private fun sdiParties(inv: Invoice): List<Row> {
        val (street, town) = STREETS[inv.supplierVat.hashCode().mod(STREETS.size)] to TOWNS[inv.supplierVat.hashCode().mod(TOWNS.size)]
        val supplier = mutableListOf(Row(0, "Dati del cedente / prestatore", 1), Row(1, "Dati anagrafici", 2), Row(2, "Identificativo fiscale ai fini IVA: IT${inv.supplierVat}", 3))
        if (inv.soleTrader) {
            val (surname, name) = inv.supplier.split(' ').let { it[0] to it[1] }
            supplier += Row(2, "Nome: $name", 3); supplier += Row(2, "Cognome: $surname", 3)
        } else {
            supplier += Row(2, "Codice fiscale: ${inv.supplierVat}", 3); supplier += Row(2, "Denominazione: ${inv.sellerName}", 3)
        }
        supplier += listOf(
            Row(2, "Regime fiscale: RF01 (ordinario)", 3), Row(1, "Dati della sede", 2), Row(2, "Indirizzo: $street", 3),
            Row(2, "Numero civico: ${inv.supplierVat.takeLast(2).trimStart('0').ifEmpty { "1" }}", 3), Row(2, "CAP: 00100", 3),
            Row(2, "Comune: ${town.first}", 3), Row(2, "Provincia: ${town.second}", 3), Row(2, "Nazione: IT", 3),
        )
        val customer = listOf(
            Row(0, "Dati del cessionario / committente", 1), Row(1, "Dati anagrafici", 2),
            Row(2, "Identificativo fiscale ai fini IVA: IT${inv.customerVat}", 3), Row(2, "Codice Fiscale: ${inv.customerVat}", 3),
            Row(2, "Denominazione: ${inv.customer}", 3), Row(1, "Dati della sede", 2), Row(2, "Indirizzo: VIA ESEMPIO", 3),
            Row(2, "Numero civico: 1", 3), Row(2, "CAP: 00100", 3), Row(2, "Comune: ROMA", 3), Row(2, "Provincia: RM", 3), Row(2, "Nazione: IT", 3),
        )
        val third = inv.intermediary?.let { name ->
            listOfNotNull(
                Row(0, "Dati del terzo intermediario soggetto emittente", 1), Row(1, "Dati anagrafici", 2),
                inv.intermediaryVat?.let { Row(2, "Identificativo fiscale ai fini IVA: IT$it", 3) }, Row(2, "Denominazione: $name", 3),
                Row(0, "Soggetto emittente la fattura", 1), Row(1, "Soggetto emittente: TZ (terzo)", 3),
            )
        }.orEmpty()
        return supplier + customer + third
    }

    private fun sdiGeneral(inv: Invoice, p: Print): List<Row> {
        val type = when (inv.docType) {
            "TD04" -> "TD04 (nota di credito)"
            "TD24" -> "TD24 (fattura differita - art.21 c.4 terzo periodo lett. a - DPR 633/72)"
            else -> "TD01 (fattura)"
        }
        val out = mutableListOf(
            Row(0, "Dati generali del documento", 1), Row(1, "Tipologia documento: $type", 3), Row(1, "Valuta importi: EUR", 3),
            Row(1, "Data documento: ${sdiDate(inv.date)}", 3), Row(1, "Numero documento: ${inv.number}", 3),
        )
        if (p.sdiTotal) out += Row(1, "Importo totale documento: ${xmlCents(inv.totalCents)}", 3)
        if (inv.roundingCents != 0L) out += Row(1, "Arrotondamento su Importo totale documento: ${xmlCents(inv.roundingCents)}", 3)
        if (inv.bolloCents != 0L) out += listOf(Row(1, "Bollo", 2), Row(2, "Bollo virtuale: SI", 3), Row(2, "Importo bollo: ${xmlCents(inv.bolloCents)}", 3))
        if (inv.discountCents != 0L) out += listOf(Row(1, "Sconto/maggiorazione", 2), Row(2, "Tipologia: SC (sconto)", 3), Row(2, "Importo: ${xmlCents(inv.discountCents)}", 3))
        return out
    }

    private fun sdiLine(i: Int, l: Line): List<Row> {
        val out = mutableListOf(Row(1, "Nr. linea: ${i + 1}", 2))
        l.code?.let { out += listOf(Row(2, "Codifica articolo", 2), Row(3, "Tipo: ${l.codeType ?: "INTERNO"}", 3), Row(3, "Valore: $it", 3)) }
        out += listOf(
            Row(2, "Descrizione bene/servizio: ${l.desc}", 3), Row(2, "Quantità: ${xml(l.qty)}", 3), Row(2, "Unità di misura: ${l.unit}", 3),
            Row(2, "Valore unitario: ${xml(l.price)}", 3), Row(2, "Valore totale: ${xmlCents(l.amountCents)}", 3), Row(2, "IVA (%): ${l.rate}.00", 3),
        )
        val notes = l.notes + listOfNotNull(l.lotForm.firstOrNull()?.let { Note("LOTTO", it.substringAfter(": ")) })
        for (n in notes) {
            out += Row(2, "Altri dati gestionali", 2)
            out += Row(3, "Tipo dato: ${n.type}", 3)
            out += Row(3, when (n.ref) { "Rif. numero" -> "Valore numerico: ${n.text.replace(',', '.')}"; "Rif. data" -> "Valore data: ${n.text}"; else -> "Valore testo: ${n.text}" }, 3)
        }
        return out
    }

    private fun sdi(inv: Invoice, p: Print): List<List<OcrLine>> {
        val s = Style(p.style)
        val sh = Sheet(s.r, p.jitter, p.rowsTogether)
        var onPage = 0
        var pageSum = 0L
        var carried = 0L
        val reserve = if (p.pageTotals) 120 else 0
        fun advance(row: Row) = when (row.size) { 0 -> 84; 1 -> 58; 2 -> 46; else -> 38 }
        fun put(row: Row) {
            val x = 90 + row.level * 45
            sh += when (row.size) {
                0 -> t(1200 - row.text.length * 15, sh.y, row.text, 30, 50)
                1 -> t(x, sh.y, row.text, 21, 34)
                2 -> t(x, sh.y, row.text, 19, 30)
                else -> t(x, sh.y, row.text, 16, 26)
            }
            sh.y += advance(row)
        }
        fun open() { sh.page(); sh.y = 150; onPage = 0; pageSum = 0 }
        val linesHeading = Row(0, "Dati relativi alle linee di dettaglio della fornitura", 1)
        fun pageTotals(last: Boolean) {
            if (!p.pageTotals) return
            carried += pageSum
            put(Row(1, "Totale pagina: ${xmlCents(pageSum)}", 3))
            if (!last) put(Row(1, "A riportare: ${xmlCents(carried)}", 3))
        }
        fun flow(rows: List<Row>) {
            for (row in rows) {
                if (sh.y + advance(row) > PAGE_BOTTOM) open()
                put(row)
            }
        }
        fun breakPage() {
            pageTotals(false)
            open()
            if (p.repeatHeader) { sdiParties(inv).forEach(::put); sdiGeneral(inv, p).forEach(::put); put(linesHeading) }
            if (p.pageTotals) put(Row(1, "Riporto: ${xmlCents(carried)}", 3))
        }
        open()
        val first = p.inBatch == null || p.inBatch == 1
        if (first) {
            put(Row(0, "FATTURA ELETTRONICA", 0))
            put(Row(0, "Versione FPR12", 3))
            flow(
                listOf(
                    Row(0, "Dati relativi alla trasmissione", 1), Row(1, "Identificativo del trasmittente: IT${inv.intermediaryVat ?: inv.supplierVat}", 3),
                    Row(1, "Progressivo di invio: ${"%05d".format(inv.number.hashCode().mod(99999))}", 3), Row(1, "Formato Trasmissione: FPR12", 3),
                    Row(1, "Codice identificativo destinatario: ABC1234", 3),
                ) + sdiParties(inv),
            )
        }
        if (p.inBatch != null) flow(listOf(Row(0, "Numero documento nel lotto: ${p.inBatch}", 1), Row(0, "Versione FPR12", 3)))
        flow(sdiGeneral(inv, p))
        for (ref in inv.ddtRefs) {
            val n = ref.removePrefix("DDT ").substringBefore(" del ")
            val d = ref.substringAfter(" del ").split('-').let { LocalDate.of(it[2].toInt(), it[1].toInt(), it[0].toInt()) }
            flow(listOf(Row(0, "Dati del documento di trasporto", 1), Row(1, "Numero DDT: $n", 3), Row(1, "Data DDT: ${sdiDate(d)}", 3)))
        }
        flow(listOf(linesHeading))
        // A line's block is kept on one page.
        inv.lines.forEachIndexed { i, l ->
            val block = sdiLine(i, l)
            if ((p.linesPerPage != null && onPage >= p.linesPerPage) || sh.y + block.sumOf(::advance) > PAGE_BOTTOM - reserve) breakPage()
            block.forEach(::put)
            onPage++
            pageSum += l.amountCents
        }
        val summary = listOf(Row(0, "Dati di riepilogo per aliquota IVA e natura", 1)) + inv.groups.flatMap { g ->
            listOf(
                Row(1, "Aliquota IVA (%): ${g.rate}.00", 3), Row(1, "Totale imponibile/importo: ${xmlCents(g.taxableCents)}", 3),
                Row(1, "Totale imposta: ${xmlCents(g.taxCents)}", 3), Row(1, "Esigibilità IVA: I (esigibilità immediata)", 3),
            )
        }
        val payment = inv.payment?.let { mode ->
            listOf(
                Row(0, "Dati relativi al pagamento", 1), Row(1, "Condizioni di pagamento: TP02 (pagamento completo)", 3), Row(1, "Dettaglio pagamento", 2),
                Row(2, "Modalità: ${mode.substringBefore(' ')} (${mode.substringAfter(' ').lowercase()})", 3),
                Row(2, "Data scadenza pagamento: ${sdiDate(inv.date.plusDays(30))}", 3), Row(2, "Importo: ${xmlCents(inv.totalCents)}", 3),
            )
        }.orEmpty()
        val end = summary + payment + Row(0, "Versione prodotta con foglio di stile SdI www.fatturapa.gov.it", 3)
        if (p.totalsAlone || sh.y + end.sumOf(::advance) + reserve > PAGE_BOTTOM) {
            pageTotals(false)
            open()
            if (p.repeatHeader) { sdiParties(inv).forEach(::put); sdiGeneral(inv, p).forEach(::put) }
            if (p.pageTotals) put(Row(1, "Riporto: ${xmlCents(carried)}", 3))
        } else {
            pageTotals(true)
        }
        end.forEach(::put)
        if (p.repeatHeader) {
            val n = sh.pages.size
            sh.pages.indices.forEach { i -> sh.pages[i] += t(2050, 3380, "Pagina ${i + 1} di $n", 14, 24) }
        }
        return sh.pages
    }
}
