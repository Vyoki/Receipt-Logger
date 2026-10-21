package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.sin

/**
 * A synthetic invoice page drawn as OCR word boxes (invented content), photographed straight,
 * tilted and at an angle, then read by columns.
 */
class TableReaderTest {

    private data class Cell(val text: String, val x: Int, val rightAligned: Boolean = false)

    private val charW = 9.0
    private val h = 16.0

    /** One OCR line per cell, with a box per word; [tilt] in degrees, [keystone] widens rows lower on the page. */
    private fun page(rows: List<List<Cell>>, tilt: Double = 0.0, keystone: Double = 0.0): List<OcrLine> {
        val t = Math.toRadians(tilt)
        fun tr(x: Double, y: Double): Pair<Double, Double> {
            val kx = 500 + (x - 500) * (1 + keystone * (y - 400) / 800)
            return (kx * cos(t) - y * sin(t)) to (kx * sin(t) + y * cos(t))
        }
        fun box(l: Double, top: Double, r: Double, b: Double): IntArray {
            val pts = listOf(tr(l, top), tr(r, top), tr(l, b), tr(r, b))
            return intArrayOf(pts.minOf { it.first }.toInt(), pts.minOf { it.second }.toInt(), pts.maxOf { it.first }.toInt(), pts.maxOf { it.second }.toInt())
        }
        val out = mutableListOf<OcrLine>()
        rows.forEachIndexed { r, cells ->
            val y = 100.0 + r * 32
            for (c in cells) {
                if (c.text.isEmpty()) continue
                val width = c.text.length * charW
                val left = if (c.rightAligned) c.x - width else c.x.toDouble()
                val words = mutableListOf<OcrLine>()
                var pos = 0
                for (w in c.text.split(' ')) {
                    val wl = left + pos * charW
                    val b = box(wl, y, wl + w.length * charW, y + h)
                    words += OcrLine(w, b[0], b[1], b[2], b[3], tilt.toFloat())
                    pos += w.length + 1
                }
                val b = box(left, y, left + width, y + h)
                out += OcrLine(c.text, b[0], b[1], b[2], b[3], tilt.toFloat(), words)
            }
        }
        return out
    }

    // CODICE COLLI DESCRIZIONE BENI TIPO CONF. TOT. PREZZO IMPORTO COD
    private val header = listOf(
        Cell("CODICE", 40), Cell("COLLI", 130), Cell("DESCRIZIONE BENI", 200), Cell("TIPO", 540), Cell("CONF.", 600),
        Cell("TOT.", 690), Cell("PREZZO", 750), Cell("IMPORTO", 850), Cell("COD", 950),
    )

    private fun row(code: String, colli: String, desc: String, tipo: String, conf: String, tot: String, price: String, amount: String, vat: String) = listOf(
        Cell(code, 40), Cell(colli, 130), Cell(desc, 200), Cell(tipo, 540), Cell(conf, 600),
        Cell(tot, 725, true), Cell(price, 810, true), Cell(amount, 915, true), Cell(vat, 950),
    )

    private val rows = listOf(
        listOf(Cell("ESEMPIO INGROSSO S.R.L.", 40)),
        listOf(Cell("FATTURA N. 12A/345 DEL 23/09/2026", 40)),
        header,
        row("1000001", "1x1", "BISCOTTI FROLLINI 800", "SK", "GR 800", "1", "3,450", "3,45", "04"),
        row("1000003", "2x3", "CANDEGGINA LT.5 - MARCA C", "FL", "LT 5", "6", "1,790", "10,74", "22"),
        row("1000004", "1", "FILONE SUINO SV", "NC", "KG", "4,45", "4,390", "19,54", "10"),
        row("1000008", "1x4", "PARMIGIANO DOP 15M", "", "KG 0.8", "4", "15,550", "62,20", "04"),
        row("1000006", "1x6", "ACQUA MINERALE NAT. 1,5", "PT", "CL 150", "6", "0,420", "2,52", "22"),
        listOf(Cell("TOTALE IMPONIBILE", 600), Cell("98,45", 915, true)),
        listOf(Cell("TOTALE DOCUMENTO", 600), Cell("110,20", 915, true)),
    )

    private fun check(d: ParsedDocument) {
        // The column reading alone must get every line right...
        checkItems(TableReader.read(listOf(LayoutRows.layout(lastPage)))!!)
        // ...and so must the document as a whole, whichever reading explains it best.
        checkItems(d.lineItems)
    }

    private var lastPage: List<OcrLine> = emptyList()
    private fun page2(rows: List<List<Cell>>, tilt: Double = 0.0, keystone: Double = 0.0) = page(rows, tilt, keystone).also { lastPage = it }

    private fun checkItems(items: List<ParsedLineItem>) {
        assertEquals(listOf(345L, 1074L, 1954L, 6220L, 252L), items.map { it.lineTotalCents?.value })
        val byCode = items.associateBy { it.itemCode }
        assertEquals(listOf("1x1", "2x3", "1", "1x4", "1x6"), items.map { it.packages?.value })
        items.forEach { assertTrue(it.originalDescription, it.originalDescription.first().isLetter()) }
        val suino = byCode.getValue("1000004")
        assertEquals(0, BigDecimal("4.45").compareTo(suino.quantity!!.value))
        assertEquals(0, BigDecimal("4.390").compareTo(suino.unitPrice!!.value))
        assertEquals("kg", suino.unit?.value)
        val parm = byCode.getValue("1000008")
        assertEquals(0, BigDecimal("4").compareTo(parm.quantity!!.value))
        assertEquals(0, BigDecimal("15.550").compareTo(parm.unitPrice!!.value))
        assertTrue(parm.originalDescription.endsWith("KG 0.8"))
        val acqua = byCode.getValue("1000006")
        assertEquals(0, BigDecimal("6").compareTo(acqua.quantity!!.value))
        assertEquals(0, BigDecimal("0.420").compareTo(acqua.unitPrice!!.value))
        assertTrue(items.all { it.quantity?.confidence == Confidence.HIGH })
        assertEquals(BigDecimal("22"), byCode.getValue("1000003").vatRatePercent?.value)
    }

    @Test fun straightPhoto() = check(ReceiptParser.parsePages(listOf(page2(rows))))

    @Test fun tiltedPhoto() = check(ReceiptParser.parsePages(listOf(page2(rows, tilt = 2.5))))

    @Test fun photoTakenAtAnAngle() = check(ReceiptParser.parsePages(listOf(page2(rows, tilt = -1.5, keystone = 0.12))))

    @Test fun enginesWithoutWordBoxes() {
        val lines = page(rows).map { it.copy(words = emptyList()) }
        lastPage = lines
        check(ReceiptParser.parsePages(listOf(lines)))
    }

    @Test fun amountTheOcrMissedIsNotTakenFromThePrice() {
        val broken = rows.map { r -> if (r.firstOrNull()?.text == "1000004") r.filterNot { it.text == "19,54" } else r }
        val d = ReceiptParser.parsePages(listOf(page(broken, tilt = 1.0)))
        assertEquals("columns", d.itemsReadBy)
        val suino = d.lineItems.first { it.itemCode == "1000004" }
        assertEquals(0, BigDecimal("4.390").compareTo(suino.unitPrice!!.value)) // the price stays the price
        assertNull(suino.lineTotalCents) // missing, not invented
    }

    @Test fun priceColumnBeforeQuantityColumn() {
        val hdr = listOf(Cell("DESCRIZIONE", 40), Cell("PREZZO", 400), Cell("Q.TA'", 520), Cell("IMPORTO", 640))
        fun r(d: String, p: String, q: String, a: String) = listOf(Cell(d, 40), Cell(p, 470, true), Cell(q, 580, true), Cell(a, 710, true))
        val d = ReceiptParser.parsePages(listOf(page(listOf(
            listOf(Cell("ORTOFRUTTA ESEMPIO SRL", 40)), hdr,
            r("POMODORI CILIEGINO", "2,000", "1,500", "3,00"),
            r("MOZZARELLA FIOR DI LATTE", "8,90", "2,500", "22,25"),
            listOf(Cell("TOTALE", 500), Cell("25,25", 710, true)),
        ))))
        val pom = d.lineItems.first { it.originalDescription.startsWith("POMODORI") }
        assertEquals(0, BigDecimal("1.5").compareTo(pom.quantity!!.value))
        assertEquals(0, BigDecimal("2").compareTo(pom.unitPrice!!.value))
        val mozz = d.lineItems.first { it.originalDescription.startsWith("MOZZARELLA") }
        assertEquals(0, BigDecimal("2.5").compareTo(mozz.quantity!!.value))
        assertEquals(0, BigDecimal("8.90").compareTo(mozz.unitPrice!!.value))
    }

    @Test fun textReadingKeepsColliApart() {
        val a = ReceiptParser.parseItemLine("1x6 ACQUA MINERALE NAT. PT CL 150 6 0,420 2,52")!!
        assertEquals("1x6", a.packages?.value)
        assertTrue(a.originalDescription.startsWith("ACQUA"))
        val b = ReceiptParser.parseItemLine("19965 5 TORTA AL TESTO NR 40,000 2,384 95,36")!!
        assertEquals("5", b.packages?.value)
        assertEquals("19965", b.itemCode)
        val c = ReceiptParser.parseItemLine("5 TORTA AL TESTO NR 40,000 2,384 95,36", colliColumn = true)!!
        assertEquals("5", c.packages?.value)
        assertTrue(c.originalDescription.startsWith("TORTA"))
        val e = ReceiptParser.parseItemLine("1000002 1 x 4 PARMIGIANO DOP KG 0,8 4 15,550 62,20")!!
        assertEquals("1x4", e.packages?.value)
        assertTrue(e.originalDescription.startsWith("PARMIGIANO"))
        assertNull(ReceiptParser.parseItemLine("Pane casereccio 2 1,50 3,00")!!.packages)
    }

    @Test fun textReadingUsesTheHeaderOrder() {
        val text = "ORTOFRUTTA ESEMPIO SRL\nDESCRIZIONE PREZZO QUANTITA IMPORTO\nPOMODORI CILIEGINO 2,000 1,500 3,00\nTOTALE 3,00"
        val pom = ReceiptParser.parse(text).lineItems.single()
        assertEquals(0, BigDecimal("1.5").compareTo(pom.quantity!!.value))
        assertEquals(0, BigDecimal("2").compareTo(pom.unitPrice!!.value))
    }

    @Test fun numbersOnASectionTitleGoToTheProductBelow() {
        val page = page(listOf(
            listOf(Cell("ESEMPIO INGROSSO S.R.L.", 40)),
            header,
            row("1000001", "1x1", "BISCOTTI FROLLINI 800", "SK", "GR 800", "1", "3,450", "3,45", "04"),
            listOf(Cell("MERCE NON ALIMENTARE", 200), Cell("6", 725, true), Cell("1,790", 810, true), Cell("10,74", 915, true), Cell("22", 950)),
            listOf(Cell("1000003", 40), Cell("2x3", 130), Cell("CANDEGGINA LT.5 - MARCA C", 200), Cell("FL", 540), Cell("LT 5", 600)),
            listOf(Cell("TOTALE IMPONIBILE", 600), Cell("14,19", 915, true)),
        ))
        val items = TableReader.read(listOf(LayoutRows.layout(page)))!!
        assertEquals(2, items.size)
        val c = items[1]
        assertTrue(c.originalDescription, c.originalDescription.startsWith("CANDEGGINA"))
        assertEquals("1000003", c.itemCode)
        assertEquals("2x3", c.packages?.value)
        assertEquals(1074L, c.lineTotalCents?.value)
    }

    // ------------------------------------------------------------------ units as printed, names that run on (C2)

    @Test fun unitsPrintedUnderTheUnitHeadingAreKept() {
        val hdr = listOf(Cell("DESCRIZIONE", 40), Cell("QUANTITA", 380), Cell("U.M.", 480), Cell("PREZZO", 560), Cell("IMPORTO", 680))
        fun r(d: String, q: String, u: String, p: String, a: String) = listOf(Cell(d, 40), Cell(q, 450, true), Cell(u, 480), Cell(p, 640, true), Cell(a, 760, true))
        val page = page(listOf(
            listOf(Cell("ABC S.R.L.", 40)), hdr,
            r("MANODOPERA TECNICO", "2", "ORE", "30,00", "60,00"),
            r("GUARNIZIONE RUBINETTO", "3", "CAD", "1,50", "4,50"),
            r("PIASTRELLE POSATE", "4", "MQ", "12,00", "48,00"),
            r("CONSUMO FASCIA F1", "100", "KWH", "0,20", "20,00"),
            r("PANE COMUNE", "5", "KG", "0,90", "4,50"),
            listOf(Cell("TOTALE IMPONIBILE", 400), Cell("137,00", 760, true)),
        ))
        // Kept as printed (a unit the app knows is named as always); never a made-up "pz".
        assertEquals(listOf("ORE", "CAD", "MQ", "KWH", "kg"), TableReader.read(listOf(LayoutRows.layout(page)))!!.map { it.unit?.value })
    }

    @Test fun aLineWithNoUnitPrintedStillCountsPieces() {
        val hdr = listOf(Cell("DESCRIZIONE", 40), Cell("QUANTITA", 380), Cell("PREZZO", 520), Cell("IMPORTO", 640))
        fun r(d: String, q: String, p: String, a: String) = listOf(Cell(d, 40), Cell(q, 450, true), Cell(p, 600, true), Cell(a, 720, true))
        val d = ReceiptParser.parsePages(listOf(page(listOf(
            listOf(Cell("ESEMPIO INGROSSO S.R.L.", 40)), hdr,
            r("CARTA FORNO ROTOLO", "2", "3,45", "6,90"),
            r("SPUGNE ABRASIVE", "3", "1,20", "3,60"),
            listOf(Cell("TOTALE", 400), Cell("10,50", 720, true)),
        ), tilt = 1.0)))
        assertEquals(listOf("pz", "pz"), d.lineItems.map { it.unit?.value })
    }

    private val codeHeader = listOf(Cell("CODICE", 40), Cell("DESCRIZIONE", 140), Cell("QUANTITA", 420), Cell("PREZZO", 540), Cell("IMPORTO", 660))
    private fun codeRow(c: String, d: String, q: String, p: String, a: String) =
        listOf(Cell(c, 40), Cell(d, 140), Cell(q, 490, true), Cell(p, 620, true), Cell(a, 740, true))

    @Test fun aNameRunningOnHoldsTheSaveOnThePdfsOwnText() {
        val layouts = listOf(LayoutRows.layout(page(listOf(
            listOf(Cell("ESEMPIO INGROSSO S.R.L.", 40)), codeHeader,
            codeRow("1000011", "PROSCIUTTO CRUDO STAGIONATO", "2", "20,00", "40,00"),
            listOf(Cell("DI PROVA AFFETTATO", 140)),
            codeRow("1000012", "MOZZARELLA FIOR DI LATTE", "3", "4,00", "12,00"),
            listOf(Cell("TOTALE IMPONIBILE", 400), Cell("52,00", 740, true)),
        ))))
        val pdf = TableReader.read(layouts, textLayerPages = setOf(0))!!
        assertEquals(listOf(4000L, 1200L), pdf.map { it.lineTotalCents?.value })
        assertEquals("the name continues on the next printed line", pdf[0].nameNote)
        assertNull(pdf[1].nameNote)
        // A photo: read as before.
        assertTrue(TableReader.read(layouts)!!.all { it.nameNote == null })
    }

    @Test fun namesAboveOrBelowTheirNumbersAreNoContinuation() {
        val layouts = listOf(LayoutRows.layout(page(listOf(
            codeHeader,
            codeRow("1000011", "PROSCIUTTO CRUDO", "2", "20,00", "40,00"),
            // The name above its numbers.
            listOf(Cell("MOZZARELLA FIOR DI LATTE", 140)),
            listOf(Cell("1000012", 40), Cell("3", 490, true), Cell("4,00", 620, true), Cell("12,00", 740, true)),
            // The name below its numbers (a tilted photo reads them so).
            listOf(Cell("1000013", 40), Cell("2", 490, true), Cell("1,50", 620, true), Cell("3,00", 740, true)),
            listOf(Cell("BASILICO FRESCO", 140)),
            listOf(Cell("TOTALE IMPONIBILE", 400), Cell("55,00", 740, true)),
        ))))
        for (pages in listOf(setOf(0), emptySet())) {
            val items = TableReader.read(layouts, textLayerPages = pages)!!
            assertEquals(listOf("PROSCIUTTO CRUDO", "MOZZARELLA FIOR DI LATTE", "BASILICO FRESCO"), items.map { it.originalDescription })
            assertTrue(items.all { it.nameNote == null })
            assertTrue(items[2].nameDoubt)
        }
    }
}
