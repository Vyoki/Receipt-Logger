package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.OcrLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.random.Random

/** What is really printed on a generated document: the reading is scored against this. */
data class ItemTruth(
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val amountCents: Long,
    val vatRate: Int?,
    val lot: String?,
)

data class DocTruth(
    val kind: String,
    val seller: String,
    val sellerVat: String?,
    val number: String?,
    val date: LocalDate,
    val subtotalCents: Long?,
    val vatCents: Long?,
    val totalCents: Long,
    /** null: the lines are not scored (only header and totals are known). */
    val items: List<ItemTruth>?,
)

/** A generated test document: what the OCR returns (page by page) and the truth. */
/** [text] instead of [pages] for documents kept as recognised text only. */
data class BenchDoc(val name: String, val pages: List<List<OcrLine>>, val truth: DocTruth, val ownVat: String, val text: String? = null)

/**
 * Invents Italian supplier documents (invoices, delivery notes, cash & carry invoices, shop receipts) in many layouts,
 * as a phone photo read by OCR returns them: line and word boxes, a tilted page, and the scanner's usual mistakes.
 * Everything is invented: names, VAT numbers (valid checksum), addresses, products. Deterministic for a seed.
 */
object DocGen {

    const val OWN_VAT = "09876543217"
    private const val CW = 20 // character width in pixels
    private const val LINE = 52 // line spacing

    private val SUPPLIER_WORDS = listOf(
        "VERDE", "FRESCO", "ALFA", "BETA", "DELTA", "SOLE", "MARE", "MONTE", "VALLE", "FIUME", "ROSSA", "BIANCA",
        "AURORA", "CENTRO", "NORD", "SUD", "ORTO", "CAMPO", "PRATO", "LAGO",
    )
    private val TRADE = listOf(
        "INGROSSO", "ALIMENTARI", "DISTRIBUZIONE", "FOOD", "CARNI", "ORTOFRUTTA", "CASEIFICIO", "SURGELATI", "BEVANDE",
        "FORNITURE", "COMMERCIALE",
    )
    private val FORMS = listOf("S.r.l.", "S.R.L.", "SRL", "S.p.A.", "S.P.A.", "S.n.c.", "S.a.s.", "SOC. COOP.")
    private val TOWNS = listOf("ROMA (RM)", "MILANO (MI)", "TORINO (TO)", "BOLOGNA (BO)", "FIRENZE (FI)", "NAPOLI (NA)", "BARI (BA)")
    private val STREETS = listOf("VIA ROMA", "VIA GARIBALDI", "VIALE EUROPA", "VIA DELLE INDUSTRIE", "PIAZZA MAZZINI", "CORSO ITALIA", "VIA DEL LAVORO")

    private data class Product(val name: String, val weighed: Boolean, val vat: Int, val price: Pair<Double, Double>)

    private val PRODUCTS = listOf(
        Product("PASSATA DI POMODORO 700G", false, 4, 0.8 to 1.6), Product("POMODORI PELATI KG 2,5", false, 4, 2.0 to 4.0),
        Product("MOZZARELLA FIOR DI LATTE 125G", false, 4, 0.6 to 1.2), Product("OLIO EXTRAVERGINE LT 1", false, 4, 6.0 to 9.0),
        Product("FARINA 00 KG 25", false, 4, 14.0 to 22.0), Product("PASTA SPAGHETTI KG 1", false, 4, 1.1 to 2.2),
        Product("PETTO DI POLLO", true, 10, 6.0 to 9.5), Product("SALMONE FRESCO FILETTO", true, 10, 14.0 to 24.0),
        Product("PROSCIUTTO CRUDO STAGIONATO", true, 10, 12.0 to 22.0), Product("SALSICCIA DI MAIALE", true, 10, 6.0 to 9.0),
        Product("DETERSIVO PIATTI LT 5", false, 22, 6.0 to 13.0), Product("TOVAGLIOLI 33X33 X100", false, 22, 1.5 to 3.0),
        Product("ACQUA NATURALE 50CL X24", false, 22, 3.0 to 6.0), Product("BIRRA BIONDA 33CL X24", false, 22, 18.0 to 30.0),
        Product("PATATE", true, 4, 0.7 to 1.5), Product("CIPOLLE DORATE", true, 4, 0.9 to 1.6), Product("LIMONI", true, 4, 1.5 to 2.8),
        Product("ZUCCHERO KG 1", false, 10, 0.9 to 1.6), Product("CAFFE IN GRANI KG 1", false, 22, 12.0 to 22.0),
        Product("BURRO 1KG", false, 4, 6.0 to 10.0), Product("UOVA FRESCHE X30", false, 4, 4.5 to 8.0),
        Product("BISCOTTI FROLLINI 700G", false, 10, 2.5 to 4.5), Product("GRANA PADANO DOP", true, 4, 9.0 to 15.0),
        Product("ZUCCHINE", true, 4, 1.2 to 2.8), Product("CARTA FORNO 40CM X 50M", false, 22, 4.0 to 8.0),
    )

    /** Valid Italian VAT number (Partita IVA) from 10 digits. */
    fun vat(r: Random): String {
        val d = (0 until 10).map { if (it == 0) 0 else r.nextInt(10) }
        var t = 0
        d.forEachIndexed { i, x -> t += if (i % 2 == 0) x else (x * 2).let { y -> if (y > 9) y - 9 else y } }
        return d.joinToString("") + ((10 - t % 10) % 10)
    }

    private fun money(c: Long) = ItalianNumbers.formatDecimal(BigDecimal(c).movePointLeft(2), minScale = 2, maxScale = 2)
    private fun dec(v: BigDecimal, scale: Int) = ItalianNumbers.formatDecimal(v, minScale = scale, maxScale = scale)

    private class Cell(val x: Int, val text: String)
    private class Row(val cells: List<Cell>)

    fun generate(seed: Int, noise: Noise = Noise.LIGHT): BenchDoc {
        val r = Random(seed)
        val kind = listOf("invoice", "invoice", "ddt", "cashcarry", "receipt")[r.nextInt(5)]
        val seller = buildString {
            append(SUPPLIER_WORDS[r.nextInt(SUPPLIER_WORDS.size)]).append(' ')
            if (r.nextBoolean()) append(SUPPLIER_WORDS[r.nextInt(SUPPLIER_WORDS.size)]).append(' ')
            append(TRADE[r.nextInt(TRADE.size)]).append(' ').append(FORMS[r.nextInt(FORMS.size)])
        }
        val sellerVat = vat(r)
        val date = LocalDate.of(2026, 1 + r.nextInt(12), 1 + r.nextInt(28))
        val number = when (r.nextInt(6)) {
            0 -> "${r.nextInt(1, 9999)}"
            1 -> "2026/${"%04d".format(r.nextInt(1, 9999))}"
            2 -> "${r.nextInt(10, 99)}${('A'..'Z').random(r)}/${r.nextInt(10000, 99999)}"
            3 -> "${('A'..'Z').random(r)}${r.nextInt(10, 99)} ${r.nextInt(100000, 999999)}"
            4 -> "FT${r.nextInt(1000, 99999)}"
            else -> "${r.nextInt(1, 999)}/${('A'..'Z').random(r)}"
        }
        val n = if (kind == "receipt") 2 + r.nextInt(5) else 3 + r.nextInt(10)
        val items = (0 until n).map {
            val p = PRODUCTS[r.nextInt(PRODUCTS.size)]
            val qty = if (p.weighed) BigDecimal(r.nextInt(300, 9000)).movePointLeft(3) else BigDecimal(1 + r.nextInt(if (r.nextInt(3) == 0) 24 else 6))
            val price = BigDecimal(p.price.first + r.nextDouble() * (p.price.second - p.price.first)).setScale(if (r.nextBoolean()) 3 else 2, RoundingMode.HALF_UP)
            val amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
            val lot = if (kind == "ddt") "${r.nextInt(100000, 999999)}" else null
            ItemTruth(p.name, qty, if (p.weighed) "kg" else "pz", price, amount, p.vat, lot)
        }
        val subtotal = items.sumOf { it.amountCents }
        val groups = items.groupBy { it.vatRate!! }.mapValues { (rate, l) -> l.sumOf { it.amountCents }.let { b -> b to Math.round(b * rate / 100.0) } }
        val vatTotal = groups.values.sumOf { it.second }
        val truth = when (kind) {
            // A shop receipt: prices include VAT; the total is what was paid.
            // One piece: the receipt prints only the amount (the app leaves quantity and price for the operator: never invented).
            "receipt" -> DocTruth(kind, seller, sellerVat, number, date, null, null, subtotal, items.map {
                it.copy(vatRate = null, lot = null, unitPrice = if (it.quantity.compareTo(BigDecimal.ONE) == 0) BigDecimal(it.amountCents).movePointLeft(2) else it.unitPrice)
            })
            else -> DocTruth(kind, seller, sellerVat, number, date, subtotal, vatTotal, subtotal + vatTotal, items)
        }
        val rows = when (kind) {
            "receipt" -> receipt(r, truth)
            else -> document(r, truth, groups)
        }
        val pages = listOf(toOcr(r, rows, noise))
        return BenchDoc("gen-$seed-$kind", pages, truth, OWN_VAT)
    }

    // ------------------------------------------------------------------ layouts

    private fun document(r: Random, t: DocTruth, groups: Map<Int, Pair<Long, Long>>): List<Row> {
        val rows = mutableListOf<Row>()
        val ddt = t.kind == "ddt"
        val cash = t.kind == "cashcarry"
        val street = "${STREETS[r.nextInt(STREETS.size)]}, ${r.nextInt(1, 200)}"
        val town = "${"%05d".format(r.nextInt(10, 98) * 1000 + r.nextInt(1000))} ${TOWNS[r.nextInt(TOWNS.size)]}"
        val customerLabel = listOf("Spett.le", "SPETTABILE", "DESTINATARIO", "Cliente:", "DESTINAZIONE MERCE", "INTESTATARIO")[r.nextInt(6)]
        val vatLabel = listOf("P.IVA", "Partita IVA", "C.F. e P.IVA", "P.I.", "Cod.Fisc. e P.IVA")[r.nextInt(5)]
        val sellerBlock = listOf(t.seller, street, town, "$vatLabel ${t.sellerVat}") +
            (if (r.nextBoolean()) listOf("Tel. 06 ${r.nextInt(1000000, 9999999)} - info@${t.seller.split(' ')[0].lowercase()}.it") else emptyList())
        val customerBlock = listOf(customerLabel, "RISTORANTE PROVA SAS", "VIA ESEMPIO, 1", "00100 ROMA (RM)", "P.IVA $OWN_VAT")
        when (r.nextInt(3)) {
            // Supplier left, customer right on the same rows.
            0 -> for (i in 0 until maxOf(sellerBlock.size, customerBlock.size)) {
                rows += Row(listOfNotNull(sellerBlock.getOrNull(i)?.let { Cell(100, it) }, customerBlock.getOrNull(i)?.let { Cell(1400, it) }))
            }
            // Supplier on top, customer below.
            1 -> { sellerBlock.forEach { rows += Row(listOf(Cell(100, it))) }; rows += Row(emptyList()); customerBlock.forEach { rows += Row(listOf(Cell(1300, it))) } }
            // Customer printed first (window envelope), supplier under it.
            else -> { customerBlock.forEach { rows += Row(listOf(Cell(1300, it))) }; rows += Row(emptyList()); sellerBlock.forEach { rows += Row(listOf(Cell(100, it))) } }
        }
        rows += Row(emptyList())
        val dateText = when (r.nextInt(4)) {
            0 -> "%02d/%02d/%04d".format(t.date.dayOfMonth, t.date.monthValue, t.date.year)
            1 -> "%02d-%02d-%04d".format(t.date.dayOfMonth, t.date.monthValue, t.date.year)
            2 -> "%02d.%02d.%04d".format(t.date.dayOfMonth, t.date.monthValue, t.date.year)
            else -> "%02d/%02d/%02d".format(t.date.dayOfMonth, t.date.monthValue, t.date.year % 100)
        }
        val docWord = if (ddt) listOf("DOCUMENTO DI TRASPORTO", "D.D.T.", "DDT")[r.nextInt(3)] else listOf("FATTURA", "FATTURA IMMEDIATA", "FATTURA ACCOMPAGNATORIA", "COPIA FATTURA")[r.nextInt(4)]
        when (r.nextInt(3)) {
            0 -> rows += Row(listOf(Cell(100, "$docWord N. ${t.number} DEL $dateText")))
            1 -> {
                rows += Row(listOf(Cell(100, "TIPO DOCUMENTO"), Cell(700, listOf("N.RO DOCUMENTO", "NUMERO", "N. DOC.")[r.nextInt(3)]), Cell(1150, "DATA DOCUMENTO"), Cell(1600, "PAGAMENTO")))
                rows += Row(listOf(Cell(100, docWord), Cell(720, t.number!!), Cell(1170, dateText), Cell(1600, "RIMESSA DIRETTA")))
            }
            else -> {
                rows += Row(listOf(Cell(100, docWord)))
                rows += Row(listOf(Cell(100, "Numero: ${t.number}"), Cell(900, "Data: $dateText")))
            }
        }
        rows += Row(emptyList())

        // The item table: columns chosen and named as different suppliers do.
        data class Col(val key: String, val head: String, val x: Int, val right: Boolean)
        val cols = mutableListOf<Col>()
        var x = 100
        fun col(key: String, head: String, width: Int, right: Boolean = false) { cols += Col(key, head, x, right); x += width }
        if (cash || r.nextBoolean()) col("code", listOf("CODICE", "COD.ART.", "ARTICOLO", "COD.")[r.nextInt(4)], 220)
        if (cash || ddt) col("colli", listOf("COLLI", "N.COLLI", "CARTONI")[r.nextInt(3)], 140)
        col("desc", listOf("DESCRIZIONE", "DESCRIZIONE BENI", "PRODOTTO", "DESCRIZIONE ARTICOLO")[r.nextInt(4)], 760)
        if (r.nextInt(3) > 0) col("um", listOf("U.M.", "UM", "UNITA'")[r.nextInt(3)], 110)
        col("qty", listOf("QUANTITA'", "Q.TA", "QTA", "QUANT.")[r.nextInt(4)], 200, true)
        col("price", listOf("PREZZO", "PREZZO UNIT.", "P.UNIT.", "PR.UNITARIO")[r.nextInt(4)], 240, true)
        col("amount", listOf("IMPORTO", "TOTALE", "VALORE")[r.nextInt(3)], 240, true)
        col("vat", listOf("IVA", "% IVA", "ALIQ.", "COD.IVA")[r.nextInt(4)], 120, true)
        // Cash & carry invoices often print each line on two rows (code and name, then the numbers) with produce notes
        // under fresh goods ("Prov ITALIA Cat II Cal 40 -45"). On a tilted photo the numbers row is read before its
        // name. A separate random stream, so the other documents stay as they were.
        val layout = Random(t.number.hashCode())
        val twoRows = cash && cols.any { it.key == "um" } && layout.nextInt(3) == 0
        val numbersFirst = twoRows && layout.nextBoolean()
        val nameKeys = setOf("code", "colli", "desc")
        rows += Row(cols.map { Cell(it.x, it.head) })
        if (ddt) rows += Row(listOf(Cell(cols.first { it.key == "desc" }.x, "ID LOTTO")))
        t.items!!.forEachIndexed { i, it ->
            val keyed = cols.mapNotNull { c ->
                val text = when (c.key) {
                    "code" -> "${100000 + i * 37 + 11}"
                    "colli" -> if (it.unit == "kg") "1" else "${maxOf(1, it.quantity.toInt() / 6)}"
                    "desc" -> it.description
                    "um" -> if (it.unit == "kg") "KG" else listOf("PZ", "NR", "CF")[i % 3]
                    "qty" -> if (it.unit == "kg") dec(it.quantity, 3) else if (cash) "${it.quantity.toInt()}" else dec(it.quantity, if (r.nextBoolean()) 0 else 2)
                    "price" -> dec(it.unitPrice, it.unitPrice.scale())
                    "amount" -> money(it.amountCents)
                    "vat" -> if (cash) "%02d".format(it.vatRate) else "${it.vatRate}"
                    else -> null
                } ?: return@mapNotNull null
                c.key to Cell(if (c.right) c.x + 200 - CW * text.length else c.x, text)
            }
            val cells = keyed.map { it.second }
            if (twoRows) {
                val name = Row(keyed.filter { k -> k.first in nameKeys }.map { k -> k.second })
                val numbers = Row(keyed.filter { k -> k.first !in nameKeys }.map { k -> k.second })
                if (numbersFirst) { rows += numbers; rows += name } else { rows += name; rows += numbers }
                if (it.unit == "kg" && layout.nextBoolean()) {
                    rows += Row(listOf(Cell(cols.first { c -> c.key == "desc" }.x, listOf("Prov ITALIA Cat II Cal 40 -45", "Prov SPAGNA Cat I", "Origine: Italia Categoria I Calibro 70/80")[layout.nextInt(3)])))
                }
            } else rows += Row(cells)
            if (it.lot != null) rows += Row(listOf(Cell(cols.first { c -> c.key == "desc" }.x, if (r.nextBoolean()) it.lot else "LOTTO ${it.lot}")))
        }
        rows += Row(emptyList())
        // VAT summary and totals.
        rows += Row(listOf(Cell(100, "IMPONIBILE"), Cell(450, "ALIQUOTA"), Cell(750, "IMPOSTA")))
        groups.toSortedMap().forEach { (rate, g) ->
            rows += Row(listOf(Cell(100, money(g.first)), Cell(450, if (cash) "%02d".format(rate) else "$rate%"), Cell(750, money(g.second))))
        }
        rows += Row(emptyList())
        rows += Row(listOf(Cell(1300, "TOTALE IMPONIBILE"), Cell(1900, money(t.subtotalCents!!))))
        rows += Row(listOf(Cell(1300, "TOTALE IVA"), Cell(1900, money(t.vatCents!!))))
        rows += Row(listOf(Cell(1300, listOf("TOTALE DOCUMENTO", "TOTALE FATTURA", "TOTALE DA PAGARE", "NETTO A PAGARE")[r.nextInt(4)]), Cell(1900, money(t.totalCents))))
        return rows
    }

    private fun receipt(r: Random, t: DocTruth): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row(listOf(Cell(300, t.seller)))
        rows += Row(listOf(Cell(300, "${STREETS[r.nextInt(STREETS.size)]} ${r.nextInt(1, 99)}")))
        rows += Row(listOf(Cell(300, TOWNS[r.nextInt(TOWNS.size)])))
        rows += Row(listOf(Cell(300, "P.IVA ${t.sellerVat}")))
        rows += Row(listOf(Cell(200, "DOCUMENTO COMMERCIALE")))
        rows += Row(listOf(Cell(200, "di vendita o prestazione")))
        rows += Row(listOf(Cell(200, "DESCRIZIONE"), Cell(900, "IVA"), Cell(1100, "Prezzo(€)")))
        t.items!!.forEach { it ->
            if (it.quantity.compareTo(BigDecimal.ONE) != 0) {
                val q = if (it.unit == "kg") dec(it.quantity, 3) else "${it.quantity.toInt()}"
                rows += Row(listOf(Cell(200, "$q x ${dec(it.unitPrice, it.unitPrice.scale())}")))
            }
            val amount = money(it.amountCents)
            rows += Row(listOf(Cell(200, it.description), Cell(900, "10%"), Cell(1300 - CW * amount.length, amount)))
        }
        rows += Row(listOf(Cell(200, "TOTALE COMPLESSIVO"), Cell(1300 - CW * money(t.totalCents).length, money(t.totalCents))))
        rows += Row(listOf(Cell(200, "di cui IVA"), Cell(1300 - CW * 4, money(t.totalCents / 11))))
        rows += Row(listOf(Cell(200, "PAGAMENTO ELETTRONICO"), Cell(1300 - CW * money(t.totalCents).length, money(t.totalCents))))
        val d = "%02d-%02d-%04d".format(t.date.dayOfMonth, t.date.monthValue, t.date.year)
        rows += Row(listOf(Cell(200, "$d 12:${r.nextInt(10, 59)}"), Cell(800, "DOC.N. ${t.number}")))
        return rows
    }

    // ------------------------------------------------------------------ the photo and the OCR

    enum class Noise { CLEAN, LIGHT, HEAVY }

    /**
     * The scanner's usual mistakes inside one word: in amounts and quantities, in words, and in VAT numbers. Never in
     * the document number or date (a misread there cannot be told from the real value by anyone).
     */
    private fun slip(r: Random, w: String, rate: Double): String {
        if (r.nextDouble() >= rate || w.length < 2) return w
        val eligible = ',' in w || (w.length >= 4 && w.all(Char::isLetter)) || (w.length == 11 && w.all(Char::isDigit))
        if (!eligible) return w
        val chars = w.toCharArray()
        val i = r.nextInt(chars.size)
        chars[i] = when (chars[i]) {
            '0' -> 'O'; 'O' -> '0'; '1' -> listOf('l', 'I', '7')[r.nextInt(3)]; 'I' -> '1'; 'l' -> '1'; '5' -> 'S'; 'S' -> '5'
            '8' -> 'B'; 'B' -> '8'; 'E' -> 'F'; ',' -> '.'; 'A' -> '4'
            else -> chars[i]
        }
        return String(chars)
    }

    private fun toOcr(r: Random, rows: List<Row>, noise: Noise): List<OcrLine> {
        val slope = when (noise) { Noise.CLEAN -> 0.0; Noise.LIGHT -> (r.nextDouble() - 0.5) * 0.02; Noise.HEAVY -> (r.nextDouble() - 0.5) * 0.05 }
        val rate = when (noise) { Noise.CLEAN -> 0.0; Noise.LIGHT -> 0.02; Noise.HEAVY -> 0.06 }
        val h = 36
        val out = mutableListOf<OcrLine>()
        rows.forEachIndexed { ri, row ->
            val y0 = 120 + ri * LINE
            for (cell in row.cells) {
                var x = cell.x
                val words = cell.text.split(' ').filter { it.isNotEmpty() }.map { w0 ->
                    val w = slip(r, w0, rate)
                    val wl = x
                    val wr = x + CW * w.length
                    x = wr + CW
                    val yl = y0 + (wl * slope).toInt()
                    val yr = y0 + (wr * slope).toInt()
                    OcrLine(w, wl, minOf(yl, yr), wr, maxOf(yl, yr) + h)
                }
                if (words.isEmpty()) continue
                // The OCR sometimes loses a lone small number (a quantity "1").
                if (noise != Noise.CLEAN && words.size == 1 && words[0].text.length == 1 && r.nextDouble() < rate * 2) continue
                out += OcrLine(
                    words.joinToString(" ") { it.text }, words.minOf { it.left }, words.minOf { it.top }, words.maxOf { it.right }, words.maxOf { it.bottom },
                    (Math.toDegrees(kotlin.math.atan(slope))).toFloat(), words,
                )
            }
        }
        // Engines return blocks in no particular order.
        return out.shuffled(r)
    }
}
