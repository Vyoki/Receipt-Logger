package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** A saved document as a training example (content invented). */
class TrainingPackageTest {

    private fun row(y: Int, vararg cells: Pair<Int, String>): OcrLine {
        val words = cells.map { (x, t) -> OcrLine(t, x, y, x + 11 * t.length, y + 18) }
        return OcrLine(words.joinToString(" ") { it.text }, words.minOf { it.left }, y, words.maxOf { it.right }, y + 18, 0f, words)
    }

    private val page = listOf(
        row(20, 100 to "ABC", 150 to "S.r.l."),
        row(60, 100 to "FATTURA", 200 to "N.", 230 to "12A/34567", 360 to "del", 400 to "14/10/2026"),
        row(120, 100 to "DESCRIZIONE", 500 to "QUANTITA'", 650 to "PREZZO", 800 to "IMPORTO", 950 to "IVA"),
        row(160, 100 to "PECORINO", 210 to "GRATTUGIATO", 500 to "0,50", 650 to "16,420", 800 to "8,21", 950 to "04"),
        row(190, 100 to "LATTE", 170 to "INTERO", 500 to "10", 650 to "1,100", 800 to "11,00", 950 to "04"),
        row(260, 600 to "TOTALE", 700 to "IMPONIBILE", 850 to "19,21"),
        row(290, 600 to "TOTALE", 700 to "DOCUMENTO", 850 to "19,98"),
    )

    private val doc = TrainingPackage.Doc(
        "ABC S.r.l.", "01234567897", "12A/34567", LocalDate.of(2026, 10, 14), 1921, 77, 1998,
        listOf(
            TrainingPackage.Line("Pecorino grattugiato", quantity = BigDecimal("0.5"), unit = "kg", price = BigDecimal("16.42"), amountCents = 821, vatRate = BigDecimal(4)),
            TrainingPackage.Line("Latte intero", quantity = BigDecimal("10"), unit = "pz", price = BigDecimal("1.1"), amountCents = 1100, vatRate = BigDecimal(4)),
        ),
    )

    @Test fun onlyDocumentsTheArithmeticProves() {
        assertNull(TrainingPackage.whyNot(doc))
        val wrongLine = doc.copy(lines = listOf(doc.lines[0].copy(amountCents = 812), doc.lines[1]))
        assertEquals(TrainingPackage.NotProven.LINE_DOES_NOT_ADD_UP, TrainingPackage.whyNot(wrongLine))
        assertEquals(TrainingPackage.NotProven.SUM_DOES_NOT_MATCH, TrainingPackage.whyNot(doc.copy(subtotalCents = 1920, totalCents = 1997)))
        assertEquals(TrainingPackage.NotProven.NO_LINES, TrainingPackage.whyNot(doc.copy(lines = emptyList())))
    }

    @Test fun truthWritesTheNumbersAsPrintedAndWhereTheyAre() {
        val t = Json.parse(TrainingPackage.truth(doc, listOf(page), listOf(1200 to 1600), ownVat = "09876543217")) as Map<*, *>
        assertEquals("it", t["lang"])
        assertEquals("14/10/2026", t["date_text"])
        val items = t["items"] as List<*>
        val pec = items[0] as Map<*, *>
        assertEquals("0,50", pec["quantity"])
        assertEquals("16,420", pec["price"])
        assertEquals("8,21", pec["amount"])
        assertEquals("04", pec["vat"])
        assertEquals(160, (pec["box"] as List<*>)[1].toString().toDouble().toInt())
        assertEquals("19,98", (t["totals"] as Map<*, *>)["total"])
        assertEquals(1200, ((t["photos"] as List<*>)[0] as Map<*, *>)["width"].toString().toDouble().toInt())
    }

    @Test fun readingIsTheReportFormat() {
        val text = TrainingPackage.reading(page)
        val first = text.lines()[3]
        assertEquals("100,160,972,178,0.0 | PECORINO GRATTUGIATO 0,50 16,420 8,21 04  [100-188:PECORINO 210-331:GRATTUGIATO 500-544:0,50 650-716:16,420 800-844:8,21 950-972:04]", first)
    }

    @Test fun aRateOrLotNotPrintedOnTheRowIsNotTaught() {
        val page = listOf(
            row(60, 100 to "FATTURA", 400 to "14/10/2026"),
            row(160, 100 to "BURRO", 300 to "2", 400 to "3,000", 500 to "6,00"),
            row(260, 100 to "TOTALE", 500 to "6,24"),
        )
        val d = TrainingPackage.Doc(null, null, null, LocalDate.of(2026, 10, 14), 600, 24, 624,
            listOf(TrainingPackage.Line("Burro", quantity = BigDecimal(2), price = BigDecimal(3), amountCents = 600, vatRate = BigDecimal(4), lot = "L123")))
        val t = Json.parse(TrainingPackage.truth(d, listOf(page), listOf(1000 to 1400))) as Map<*, *>
        val line = (t["items"] as List<*>)[0] as Map<*, *>
        assertNull(line["vat"])
        assertNull(line["lot"])
        assertEquals("4", line["vat_rate"])
    }

    @Test fun theTotalMustBePrintedOnAPageRead() {
        assertNull(TrainingPackage.whyNotReading(doc, listOf(page)))
        val firstPageOnly = page.filter { !it.text.contains("19,98") }
        assertEquals(TrainingPackage.NotProven.TOTALS_NOT_READ, TrainingPackage.whyNotReading(doc, listOf(firstPageOnly)))
    }

    @Test fun positionsAreScaledToTheExportedPicture() {
        val scaled = TrainingPackage.scaled(page, 2.0)
        assertEquals(200, scaled[3].left)
        assertEquals(200, scaled[3].words[0].left)
        assertTrue(TrainingPackage.fits(scaled, 2000, 700))
        assertFalse(TrainingPackage.fits(scaled, 1000, 700))
    }

    @Test fun aDiscountIsNotTaughtAsTheVatRate() {
        val page = listOf(
            row(60, 100 to "FATTURA", 400 to "14/10/2026"),
            row(160, 100 to "MOZZARELLA", 300 to "2", 400 to "5,000", 500 to "10%", 600 to "9,00"),
            row(260, 100 to "TOTALE", 500 to "9,90"),
        )
        val d = TrainingPackage.Doc(null, null, null, LocalDate.of(2026, 10, 14), 900, 90, 990,
            listOf(TrainingPackage.Line("Mozzarella", quantity = BigDecimal(2), price = BigDecimal(5), amountCents = 900, vatRate = BigDecimal.TEN, discount = "10")))
        val line = ((Json.parse(TrainingPackage.truth(d, listOf(page), listOf(1000 to 1400))) as Map<*, *>)["items"] as List<*>)[0] as Map<*, *>
        assertNull(line["vat"])
    }
}
