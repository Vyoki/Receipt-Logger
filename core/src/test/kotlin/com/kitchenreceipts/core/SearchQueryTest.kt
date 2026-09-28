package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SearchQueryTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun fullDate() = assertEquals(SearchQuery("", d(2025, 3, 14), d(2025, 3, 14)), SearchQuery.parse("14/03/2025"))
    @Test fun monthNumeric() = assertEquals(SearchQuery("", d(2025, 3, 1), d(2025, 3, 31)), SearchQuery.parse("03/2025"))
    @Test fun monthIso() = assertEquals(SearchQuery("", d(2025, 2, 1), d(2025, 2, 28)), SearchQuery.parse("2025-02"))
    @Test fun monthName() = assertEquals(SearchQuery("rossi", d(2025, 3, 1), d(2025, 3, 31)), SearchQuery.parse("Rossi marzo 2025").copy(text = "rossi"))
    @Test fun textAndDate() = assertEquals(SearchQuery("caseificio", d(2025, 3, 14), d(2025, 3, 14)), SearchQuery.parse("caseificio 14.03.25"))
    @Test fun plainText() = assertEquals(SearchQuery("L24-118", null, null), SearchQuery.parse(" L24-118 "))
    @Test fun documentNumberNotAMonth() = assertEquals(SearchQuery("0145/2025", null, null), SearchQuery.parse("0145/2025"))
}
