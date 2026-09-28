package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class ReportsTest {

    private val docs = listOf(
        ReportDocument(1, "Caseificio Valverde", LocalDate.of(2025, 3, 14), "145", 7106, 4),
        ReportDocument(2, "Caseificio Valverde", LocalDate.of(2025, 3, 28), "160", 3000, 2),
        ReportDocument(3, "Mercato Fresco", LocalDate.of(2025, 3, 14), null, 1640, 4),
        ReportDocument(4, "Mercato Fresco", LocalDate.of(2025, 4, 2), null, null, 1),
        ReportDocument(5, "Ortofrutta", null, "88", 7465, 5),
    )

    @Test fun groupsBySellerAndCalendarMonth() {
        val rows = Reports.monthlyBySeller(docs)
        assertEquals(4, rows.size)
        val april = rows[0]
        assertEquals(YearMonth.of(2025, 4), april.month)
        assertEquals(0L, april.totalCents)
        assertEquals(1, april.documentsMissingTotal)

        val marchCaseificio = rows.first { it.month == YearMonth.of(2025, 3) && it.sellerName == "Caseificio Valverde" }
        assertEquals(2, marchCaseificio.documentCount)
        assertEquals(10106L, marchCaseificio.totalCents)
        assertEquals(6, marchCaseificio.lineItemCount)

        val undated = rows.last()
        assertEquals(null, undated.month)

        val totals = Reports.monthTotals(rows)
        assertEquals(11746L, totals.first { it.month == YearMonth.of(2025, 3) }.totalCents)
    }

    @Test fun italianCsvUsesSemicolonAndDecimalComma() {
        val csv = ReportCsv.monthlySeller(Reports.monthlyBySeller(docs), CsvFormat.ITALIAN_EXCEL)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals("mese;fornitore;documenti;totale_eur;documenti_senza_totale;righe", lines[0])
        assertTrue(lines.contains("2025-03;Caseificio Valverde;2;101,06;0;6"))
        assertTrue(lines.contains("senza data;Ortofrutta;1;74,65;0;5"))
    }

    @Test fun standardCsvQuotesAndEscapes() {
        val row = PurchaseExportRow(
            LocalDate.of(2025, 3, 14), "Rossi, Verdi & C.", "A\"1", 7, "=HYPERLINK(\"x\")", null,
            BigDecimal("2.500"), "kg", BigDecimal("8.90"), 2225, VatBasis.EXCLUSIVE, "L24-118",
        )
        val csv = ReportCsv.purchases(listOf(row), CsvFormat.STANDARD)
        val line = csv.removePrefix("﻿").split("\r\n")[1]
        assertEquals(
            "2025-03-14,2025-03,\"Rossi, Verdi & C.\",\"A\"\"1\",7,\"'=HYPERLINK(\"\"x\"\")\",,2.5,kg,8.9,22.25,IVA esclusa,L24-118",
            line,
        )
    }
}
