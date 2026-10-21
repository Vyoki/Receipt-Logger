package com.kitchenreceipts.core

/**
 * INVENTED invoices in the standard e-invoice printout layout (the AssoSoftware stylesheet many accounting programs
 * print with): the two parties side by side, the document header row, the item heading printed as a band of staggered
 * labels ("PREZZO / UNITARIO", "SCONTO / O MAGG."), notes under a line ("Tipo dato: LOTTO / Rif. testo: ..."), the VAT
 * summary ("RIEPILOGHI IVA E TOTALI") and "TOTALE DOCUMENTO". Word boxes on a page 2400 units wide, as the PDF's own
 * text gives them. Every name, number, lot and amount here is made up.
 */
internal object EInvoicePrintoutFixture {

    /** [valueLabel]: "Rif. testo" (AssoSoftware) or "Valore testo" (SdI); [x]: where the note is printed. */
    data class Note(val type: String, val text: String, val valueLabel: String = "Rif. testo", val x: Int = 643)

    data class Line(
        val desc: String,
        val qty: String?,
        val price: String?,
        val unit: String?,
        val vat: String,
        val amount: String?,
        val code: String? = null,
        val codeType: String? = "INTERNO",
        val notes: List<Note> = emptyList(),
        val more: List<String> = emptyList(),
        val letterSpaced: Boolean = false,
        val descX: Int = 643,
    )

    data class Group(val rate: String, val taxable: String, val tax: String, val label: String = "I (esigibilità immediata)")

    data class Invoice(
        val supplierVat: String,
        val supplierName: String,
        val number: String,
        val date: String,
        val lines: List<Line>,
        val groups: List<Group>,
        val total: String,
        val type: String = "TD01 fattura",
        val supplierName2: String? = null,
        val soleTrader: Boolean = false,
        val intermediary: String? = null,
        val ddtRefs: List<String> = emptyList(),
        val payment: String? = "MP05 Bonifico",
        /** Rows printed after the lines: a label in the description column and an amount in the amount column. */
        val footRows: List<Pair<String, String>> = emptyList(),
        /** Stamp duty, a rounding and a document discount printed on the row of the document total. */
        val bollo: String? = null,
        val rounding: String? = null,
        val docDiscount: String? = null,
        /** The VAT summary block ("RIEPILOGHI IVA E TOTALI", its heading, its rows and the total). */
        val summary: Boolean = true,
        /** The summary's heading row ("ESIGIBILITÀ IVA / RIFERIMENTI NORMATIVI ... TOTALE IMPOSTA"). */
        val summaryHeading: Boolean = true,
    )

    const val CUSTOMER_VAT = "09876543217"
    const val CUSTOMER_NAME = "RISTORANTE PROVA SAS"

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

    /** One text line, its words spread over its box ([cw] units per character). */
    fun t(x: Int, y: Int, text: String, cw: Int = 20, h: Int = 34): OcrLine {
        var at = x
        val words = text.split(' ').filter { it.isNotEmpty() }.map { w -> OcrLine(w, at, y, at + w.length * cw, y + h).also { at += (w.length + 1) * cw } }
        return OcrLine(text, x, y, x + text.length * cw, y + h, words = if (words.size > 1) words else emptyList())
    }

    /** A number right-aligned at [right]. */
    fun r(right: Int, y: Int, text: String, cw: Int = 20): OcrLine = t(right - text.length * cw, y, text, cw)

    /** Small heading text. */
    private fun s(x: Int, y: Int, text: String) = t(x, y, text, cw = 12, h = 22)

    private fun letterSpaced(x: Int, y: Int, text: String): OcrLine {
        var at = x
        val words = text.filter { it != ' ' }.map { c -> OcrLine(c.toString(), at, y, at + 24, y + 34).also { at += 33 } }
        return OcrLine(words.joinToString(" ") { it.text }, x, y, at, y + 34, words = words)
    }

    private const val PAGE_BOTTOM = 3300

    private fun headingBand(y: Int) = listOf(
        s(1542, y, "PREZZO"), s(1841, y, "SCONTO"),
        s(259, y + 12, "COD. ARTICOLO"), s(888, y + 12, "DESCRIZIONE"), s(1332, y + 12, "QUANTITÀ"), s(1733, y + 12, "UM"),
        s(2002, y + 12, "%IVA"), s(2115, y + 12, "PREZZO TOTALE"),
        s(1534, y + 25, "UNITARIO"), s(1839, y + 25, "O MAGG."),
    )

    /** The pages of one invoice. */
    fun pages(inv: Invoice, customerVat: String = CUSTOMER_VAT): List<List<OcrLine>> {
        val pages = mutableListOf<MutableList<OcrLine>>()
        var page = mutableListOf<OcrLine>()
        pages += page
        // The parties.
        page += t(102, 172, "Cedente/prestatore (fornitore)", cw = 19, h = 42)
        page += t(1263, 172, "Cessionario/committente (cliente)", cw = 19, h = 42)
        val left = mutableListOf("Identificativo fiscale ai fini IVA: IT${inv.supplierVat}")
        if (inv.soleTrader) {
            left += "Codice fiscale: RSSMRA80A01H501U"
            left += "Cognome nome: ${inv.supplierName}"
        } else {
            left += "Codice fiscale: ${inv.supplierVat}"
            left += "Denominazione: ${inv.supplierName}"
        }
        inv.supplierName2?.let { left += it }
        left += listOf("Regime fiscale: RF01 ordinario", "Indirizzo: VIA DI PROVA 12", "Comune: ROMA Provincia: RM", "Cap: 00100 Nazione: IT")
        val right = listOf(
            "Identificativo fiscale ai fini IVA: IT$customerVat", "Codice fiscale: $customerVat", "Denominazione: $CUSTOMER_NAME",
            "Indirizzo: VIA ESEMPIO 1", "Comune: ROMA Provincia: RM", "Cap: 00100 Nazione: IT",
        )
        var y = 234
        for (i in 0 until maxOf(left.size, right.size)) {
            left.getOrNull(i)?.let { page += t(128, y, it) }
            right.getOrNull(i)?.let { page += t(1289, y, it) }
            y += 50
        }
        inv.intermediary?.let {
            page += t(102, y, "Terzo Intermediario"); y += 62
            page += t(128, y, "Denominazione: $it"); y += 50
        }
        // The document header.
        y += 42
        page += s(759, y, "ART."); page += s(1583, y, "DATA")
        page += s(268, y + 12, "TIPOLOGIA DOCUMENTO"); page += s(1054, y + 12, "NUMERO DOCUMENTO"); page += s(1932, y + 12, "CODICE DESTINATARIO")
        page += s(771, y + 25, "73"); page += s(1544, y + 25, "DOCUMENTO")
        y += 71
        page += t(84, y, inv.type, cw = 17)
        page += t(1171 - inv.number.length * 10, y, inv.number, cw = 20)
        page += t(1526, y, inv.date, cw = 17)
        page += t(1979, y, "ABC1234", cw = 21)
        y += 204
        page += headingBand(y)
        y += 52
        for (ref in inv.ddtRefs) { page += t(633, y, ref, cw = 15, h = 26); y += 35 }
        for (line in inv.lines) {
            val height = 58 + line.notes.size * 69 + line.more.size * 41
            if (y + height > PAGE_BOTTOM) {
                page = mutableListOf(); pages += page
                y = 177
                page += headingBand(y)
                y += 70
            }
            line.code?.let { c -> page += t(84, y + 1, if (line.codeType != null) "$c ( ${line.codeType} )" else c, cw = 16, h = 26) }
            page += if (line.letterSpaced) letterSpaced(line.descX, y, line.desc) else t(line.descX, y, line.desc)
            line.qty?.let { page += r(1352, y, it) }
            line.price?.let { page += r(1670, y, it) }
            line.unit?.let { page += t(1727, y, it) }
            page += r(2072, y, line.vat)
            line.amount?.let { page += r(2366, y, it) }
            var ny = y + 41
            for (m in line.more) { page += t(643, ny, m); ny += 41 }
            for (n in line.notes) {
                page += t(n.x, ny + 2, "Tipo dato: ${n.type}", cw = 14, h = 26)
                page += t(n.x, ny + 36, "${n.valueLabel}: ${n.text}", cw = 14, h = 26)
                ny += 69
            }
            y = maxOf(ny, y + 58)
        }
        for ((label, amount) in inv.footRows) {
            page += t(643, y, label)
            page += r(2366, y, amount)
            y += 58
        }
        // The VAT summary and the totals.
        y += 60
        val summaryHeight = 140 + inv.groups.size * 58 + 300
        if (y + summaryHeight > PAGE_BOTTOM) {
            page = mutableListOf(); pages += page
            y = 177
        } else if (inv.summary) {
            page += s(1095, y, "RIEPILOGHI IVA E TOTALI")
            y += 44
        }
        if (inv.summary) {
            if (inv.summaryHeading) {
                page += s(240, y, "ESIGIBILITÀ IVA / RIFERIMENTI NORMATIVI"); page += s(890, y, "%IVA"); page += s(1007, y, "SPESE ACCESSORIE")
                page += s(1264, y, "ARR."); page += s(1493, y, "TOTALE IMPONIBILE"); page += s(2032, y, "TOTALE IMPOSTA")
            }
            y += 38
            for (g in inv.groups) {
                page += t(84, y, g.label, cw = 17)
                page += r(960, y, g.rate)
                page += r(1840, y, g.taxable)
                page += r(2366, y, g.tax)
                y += 58
            }
            page += s(238, y, "IMPORTO BOLLO"); page += s(779, y, "SCONTO/MAGGIORAZIONE"); page += s(1264, y, "ARR."); page += s(1748, y, "TOTALE DOCUMENTO")
            y += 46
            inv.bollo?.let { page += r(420, y, it) }
            inv.docDiscount?.let { page += r(1010, y, it) }
            inv.rounding?.let { page += r(1330, y, it) }
            page += r(2366, y, inv.total)
            y += 121
        }
        page += s(318, y, "MODALITÀ PAGAMENTO"); page += s(1065, y, "DETTAGLI"); page += s(1462, y, "SCADENZE"); page += s(1958, y, "IMPORTO")
        inv.payment?.let {
            y += 46
            page += t(84, y, it, cw = 17)
            page += r(2366, y, inv.total)
        }
        return pages
    }

    /** Several invoices printed one after another into one file. */
    fun bundle(invoices: List<Invoice>): List<List<OcrLine>> = invoices.flatMap { pages(it) }

    // ------------------------------------------------------------------ ready-made invented invoices

    val MEAT = Invoice(
        supplierVat = pIva("0123456789"), supplierName = "CARNI ESEMPIO S.R.L.", number = "318", date = "14-03-2031",
        type = "TD24 fattura differita di cui all'art.21,",
        lines = listOf(
            Line("Pancetta tesa", "12,50", "9,20", "kg", "10,00", "115,00", code = "0007", codeType = "FORNITORE", notes = listOf(Note("LOTTO", "AB123"))),
            Line("Salsiccia fresca", "8,00", "7,50", "kg", "10,00", "60,00", code = "0012", codeType = "FORNITORE", notes = listOf(Note("LOTTO", "CD456"))),
            Line("Pecorino stagionato", "3,00", "16,00", "kg", "4,00", "48,00", code = "0081", codeType = "FORNITORE", notes = listOf(Note("LOTTO", "EF789"))),
        ),
        groups = listOf(Group("10,00", "175,00", "17,50"), Group("4,00", "48,00", "1,92")),
        total = "242,42",
        intermediary = "SOFTWARE ESEMPIO SRL",
        ddtRefs = listOf("DDT 61 del 03-03-2031", "DDT 74 del 07-03-2031"),
    )

    val WHOLESALER = Invoice(
        supplierVat = pIva("0234567891"), supplierName = "INGROSSO PROVA S.P.A.", number = "7C 000012345", date = "19-03-2031",
        lines = listOf(
            Line("BIRRA CHIARA BOTT. CL.33X3", "8,00", "2,40", "PZ", "22,00", "19,20", code = "4101011"),
            Line("CECI LESSATI KG.2,5", "4,00", "3,10", "PZ", "10,00", "12,40", code = "4101012"),
            Line("OLIO EVO ITALIA LT.1", "2,00", "9,50", "PZ", "4,00", "19,00", code = "4101013"),
            Line("COPPA SUINO S.V.", "4,20", "5,00", "KG", "10,00", "21,00", code = "4101014", notes = listOf(Note("CUN", "COPPA-INTERA"))),
            Line("TOVAGLIOLI BIANCHI 3V40X40X30", "1,00", "1,20", "PZ", "22,00", "1,20", code = "4101015"),
            Line("POMODORO CILIEGINO", "6,50", "2,40", "KG", "4,00", "15,60", code = "4101016"),
        ),
        groups = listOf(Group("4,00", "34,60", "1,38"), Group("10,00", "33,40", "3,34"), Group("22,00", "20,40", "4,49")),
        total = "97,61",
        payment = "MP08 Carta di pagamento",
    )

    val ENERGY = Invoice(
        supplierVat = pIva("0345678912"), supplierName = "ENERGIA ESEMPIO SRL", number = "000920310000054321", date = "18-03-2031",
        lines = listOf(
            Line("POD/PDR: IT001E00000001", "2.000,000", "0,1000", "KWH", "22,00", "200,00", code = "11223344", codeType = null,
                notes = listOf(Note("POD", "IT001E00000001"), Note("Contratto", "TARIFFA ESEMPIO 2024"))),
            Line("POD/PDR: IT001E00000001", "1,00", "10,00", "MESE", "22,00", "10,00", code = "11223344", codeType = null,
                notes = listOf(Note("POD", "IT001E00000001"))),
            Line("POD/PDR: IT001E00000001", "1,00", "-0,30", "CAD", "N2.2", "-0,30", code = "11223344", codeType = null),
        ),
        groups = listOf(Group("22,00", "210,00", "46,20"), Group("N2.2", "-0,30", "0")),
        total = "255,90",
        payment = "MP09 RID",
    )

    val HOUSEHOLD = Invoice(
        supplierVat = pIva("0456789123"), supplierName = "BIANCHI LUCA", soleTrader = true, number = "55/A", date = "14-03-2031",
        type = "TD24 fattura differita di cui all'art.21,",
        intermediary = "RETE ESEMPIO S.R.L.",
        lines = listOf(
            Line("SAPONE LIQUIDO 500", "2,00", "1,50", "PZ", "22,00", "3,00", code = "Z7", codeType = "Codice articolo", letterSpaced = true),
            Line("SPUGNE ABRASIVE", "3,00", "0,80", "PZ", "22,00", "2,40", code = "Z7", codeType = "Codice articolo", letterSpaced = true),
        ),
        groups = listOf(Group("22,00", "5,40", "1,19")),
        total = "6,59",
        payment = null,
    )

    val TWO_LINE_NAME = Invoice(
        supplierVat = pIva("0567891234"), supplierName = "VERDI CARNI DI VERDI", supplierName2 = "ANNA & C. SNC", number = "1200/2031", date = "16-03-2031",
        lines = listOf(
            Line("POLLO INTERO", "10,00", "4,50", "KG", "10,00", "45,00", code = "0105", codeType = "FORNITORE"),
            Line("ARISTA DI SUINO", "6,00", "6,00", "KG", "10,00", "36,00", code = "0100", codeType = "FORNITORE"),
        ),
        groups = listOf(Group("10,00", "81,00", "8,10")),
        total = "89,10",
    )

    /** A long invoice, so its lines run over a second page. */
    val LONG = Invoice(
        supplierVat = pIva("0678912345"), supplierName = "FRUTTA ESEMPIO S.R.L.", number = "4410", date = "15-03-2031",
        lines = (1..60).map { i -> Line("ARTICOLO DI PROVA N$i", "2,00", "1,50", "KG", "4,00", "3,00", code = (2000000 + i).toString()) },
        groups = listOf(Group("4,00", "180,00", "7,20")),
        total = "187,20",
    )
}
