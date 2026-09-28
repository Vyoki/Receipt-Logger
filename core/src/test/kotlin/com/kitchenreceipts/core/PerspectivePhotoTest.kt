package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * What the OCR engine returns for an invoice photographed at an angle: every column comes back as
 * separate pieces (code, description, pack, qty, price, amount, VAT), in column order, and the rows
 * slope differently at the top and at the bottom of the page (keystone / perspective).
 */
class PerspectivePhotoTest {

    private val columnsX = listOf(60, 160, 230, 900, 1040, 1130, 1260, 1360)

    // Layout of page 1 of the cash & carry invoice (content invented).
    private val table = listOf(
        listOf("1000001", "1x1", "BISCOTTI FROLLINI 800 - MARCA A", "SK GR 800", "1", "3,450", "3,45", "10"),
        listOf("1000002", "1x1", "FETTE BISCOTTATE GR.250 - MARCA B", "SC GR 250", "1", "1,090", "1,09", "04"),
        listOf("O 1000003", "2x3", "CANDEGGINA NORMALE LT.5 - MARCA C", "FL LT 5", "6", "1,790", "10,74", "22"),
        listOf("1000004", "1", "FILONE SUINO SV - .", "NC KG", "4,45", "4,390", "19,54", "10"),
        listOf("O 1000005", "1", "FILETTO B/A KG 3,5+ S/V - .", "CS KG", "4,24", "29,900", "126,78", "10"),
        listOf("1000006", "1x6", "ACQUA MINERALE NAT.1,5 - MARCA D", "PT CL 150", "6", "0,420", "2,52", "22"),
        listOf("1000007", "2x1", "UOVA MEDIE 180 - MARCA E", "VA KG 11.3", "2", "40,900", "81,80", "10"),
        listOf("1000008", "1x4", "PARMIGIANO DOP 15M S/V 800", "KG 0.8", "4", "15,550", "62,20", "04"),
        listOf("1000009", "1", "SALAMELLA DOLCE", "CF GR", "0,48", "10,210", "4,90", "10"),
        listOf("1000010", "1x1", "CARBONE VEGETALE KG.10", "NC PZ 1", "1", "11,320", "11,32", "22"),
        listOf("1000011", "1x1", "CIPOLLA DORATA KG10 PZ CRT - .", "", "", "", "", ""),
        listOf("", "", "", "KG 10", "1", "11,900", "11,90", "04"),
        listOf("", "", "Prov SU CONFEZIONE Cat I Cal", "", "", "", "", ""),
        listOf("1000012", "1x10", "CIPOLLA ROSSA KG1X16 CRT - PG", "NC KG 1", "10", "1,490", "14,90", "04"),
        listOf("1000013", "1x2", "RICOTTA KG.1,5 - MARCA F", "CF GR 1500", "2", "4,850", "9,70", "04"),
    )
    private val header = listOf(
        "ABC S.r.l." to 60, "P.IVA 01234567897" to 60, "SPETTABILE" to 700, "RISTORANTE PROVA SAS" to 700,
    )

    private val height = 1000.0
    private val charW = 11
    private val lineH = 22

    /** Keystone: rows rise [topDeg] at the top of the page and [bottomDeg] at the bottom; the top is narrower. */
    private fun photo(topDeg: Double, bottomDeg: Double): List<OcrLine> {
        val out = mutableListOf<Pair<Int, OcrLine>>() // (column, line) so we can return column order
        fun place(text: String, x: Int, y: Int, col: Int) {
            if (text.isBlank()) return
            val w = text.length * charW
            val corners = listOf(x to y, x + w to y, x to y + lineH, x + w to y + lineH).map { (px, py) ->
                val t = py / height
                val slope = tan(Math.toRadians(topDeg + (bottomDeg - topDeg) * t))
                val scale = 0.93 + 0.07 * t // narrower at the top
                val nx = 700 + (px - 700) * scale
                nx to (py + slope * px)
            }
            val l = corners.minOf { it.first }.toInt(); val r = corners.maxOf { it.first }.toInt()
            val tp = corners.minOf { it.second }.toInt(); val b = corners.maxOf { it.second }.toInt()
            out += col to OcrLine(text, l, tp, r, b)
        }
        header.forEachIndexed { i, (t, x) -> place(t, x, 40 + i * 30, if (x < 500) 0 else 9) }
        place("CODICE COLLI DESCRIZIONE BENI TIPO CONF. TOT. PZ/KG PREZZO UNIT. IMPORTO EUR COD IVA", 60, 200, 0)
        table.forEachIndexed { r, row ->
            row.forEachIndexed { c, text -> place(text, columnsX[c], 240 + r * 30, c) }
        }
        // The engine returns block by block (column by column), top to bottom inside each block.
        return out.sortedWith(compareBy<Pair<Int, OcrLine>> { it.first }.thenBy { it.second.top }).map { it.second }
    }

    private fun check(topDeg: Double, bottomDeg: Double) {
        val text = LayoutRows.toText(photo(topDeg, bottomDeg))
        val d = ReceiptParser.parse(text)
        assertEquals(
            "keystone $topDeg°..$bottomDeg°\n$text",
            listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1190L, 1490L, 970L),
            d.lineItems.map { it.lineTotalCents?.value },
        )
    }

    @Test fun straight() = check(0.0, 0.0)
    @Test fun keystoneRisingThenFalling() = check(2.0, -1.5)
    @Test fun keystoneStronger() = check(-3.0, 1.0)
    @Test fun tiltedAndKeystone() = check(3.0, 1.5)
}
