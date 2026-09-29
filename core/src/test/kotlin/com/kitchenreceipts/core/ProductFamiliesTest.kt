package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Synthetic names only (invented brands and suppliers). */
class ProductFamiliesTest {

    private fun g(name: String) = ProductFamilies.genericName(name)

    @Test fun genericNamesOfTomatoProducts() {
        assertEquals("Passata di pomodoro", g("PASSATA DI POMODORO VALLEVERDE 700G"))
        assertEquals("Passata di pomodoro", g("Passata rustica Collina Rossa 680 g"))
        assertEquals("Concentrato di pomodoro", g("DOPPIO CONCENTRATO TUBO 130G"))
        assertEquals("Concentrato di pomodoro", g("Concentrato di pomodoro Sole d'Oro"))
        assertEquals("Pomodori pelati", g("PELATI 2,5 KG"))
        assertEquals("Polpa di pomodoro", g("POLPA DI POMODORO FINE 400G"))
        assertEquals("Pomodorini", g("POMODORINI DATTERINI VASC 500G"))
        assertEquals("Pomodori", g("Pomodori ramati"))
    }

    @Test fun orderResolvesTheUsualAmbiguities() {
        assertEquals("Tonno", g("TONNO ALL'OLIO DI OLIVA 1,7KG")) // not oil
        assertEquals("Cipolle", g("CIPOLLINE AL BALSAMICO")) // not vinegar
        assertEquals("Patate", g("PATATE PELATE")) // not tomatoes
        assertEquals("Mozzarella", g("FIORDILATTE PER PIZZA")) // not milk
        assertEquals("Mozzarella di bufala", g("MOZZARELLA BUFALA CAMPANA 250G"))
        assertEquals("Olio extravergine di oliva", g("OLIO EXTRAVERGINE DI OLIVA 5LT"))
        assertEquals("Olio extravergine di oliva", g("OLIO EVO LATTA 5 L"))
        assertEquals("Latte", g("LATTE INTERO UHT 1L"))
        assertEquals("Sale", g("SALE GROSSO 1KG"))
        assertEquals("Salame", g("SALAME NOSTRANO")) // "sale" must be a whole word
    }

    @Test fun unknownProductsGetNoName() {
        assertNull(g("ART. 44821 VARIE"))
        assertNull(g(""))
        assertNull(g("Pesto alla genovese")) // not in the dictionary: no guess
    }

    @Test fun suggestsNewGroupOnlyForTwoOrMore() {
        val products = listOf(
            ProductFamilies.Member(1, "PASSATA VALLEVERDE 700G", null, false),
            ProductFamilies.Member(2, "Passata rustica Collina Rossa", null, false),
            ProductFamilies.Member(3, "Mozzarella fior di latte", null, false),
            ProductFamilies.Member(4, "PASSATA DEL CONTADINO", 9, false), // already in a group
            ProductFamilies.Member(5, "PASSATA ORTO FELICE", null, true), // operator said no
        )
        val s = ProductFamilies.suggestions(products, emptyList())
        assertEquals(1, s.size)
        assertEquals("Passata di pomodoro", s[0].name)
        assertNull(s[0].existingFamilyId)
        assertEquals(listOf(1L, 2L), s[0].productIds)
    }

    @Test fun existingGroupTakesASingleProduct() {
        val products = listOf(ProductFamilies.Member(3, "MOZZARELLA 125G", null, false))
        val s = ProductFamilies.suggestions(products, listOf(ProductFamilies.Family(7, "MOZZARELLA")))
        assertEquals(1, s.size)
        assertEquals(7L, s[0].existingFamilyId)
        assertEquals("MOZZARELLA", s[0].name)
    }

    @Test fun packSizes() {
        fun p(s: String) = ProductFamilies.packSize(s)
        assertEquals(0, BigDecimal("0.7").compareTo(p("PASSATA 700G")!!.total))
        assertEquals("kg", p("PASSATA 700G")!!.unit)
        assertEquals(0, BigDecimal("2.5").compareTo(p("PELATI 2,5 KG")!!.total))
        assertEquals(0, BigDecimal("5").compareTo(p("OLIO EVO LT 5")!!.total))
        assertEquals("l", p("OLIO EVO LT 5")!!.unit)
        val multi = p("POLPA 6X400G")!!
        assertEquals(6, multi.count)
        assertEquals(0, BigDecimal("0.4").compareTo(multi.each))
        assertEquals(0, BigDecimal("2.4").compareTo(multi.total))
        assertEquals(12, p("ACQUA 0,5L X 12")!!.count)
        assertNull(p("PASSATA")) // no size printed
        assertNull(p("PASSATA 700G 1KG")) // two sizes: unclear
        assertNull(p("PASSATA 700G CF 6")) // count of packs next to a size: unclear
    }

    private var line = 0L
    private fun pp(id: Long, name: String, seller: String, date: String, qty: String, unit: String, cents: Long, basis: VatBasis = VatBasis.EXCLUSIVE) =
        PricePoint(id, name, line + 100, ++line, LocalDate.parse(date), seller, BigDecimal(qty), unit, null, cents, basis)

    @Test fun comparesPerKilo() {
        val variants = listOf(
            ProductFamilies.Variant(1, "PASSATA VALLEVERDE 700G", "Valleverde"),
            ProductFamilies.Variant(2, "PASSATA COLLINA ROSSA 5KG", null),
            ProductFamilies.Variant(3, "PASSATA SENZA MISURA", null),
        )
        val purchases = listOf(
            pp(1, "x", "Alfa Ingrosso S.r.l.", "2025-03-01", "12", "pz", 1200), // 1,00 per 700 g -> 1,4286/kg
            pp(1, "x", "Alfa Ingrosso S.r.l.", "2025-04-01", "12", "pz", 1260), // later: 1,05 -> 1,5/kg
            pp(2, "x", "Beta Distribuzione", "2025-04-02", "2", "pz", 1300), // 6,50 per 5 kg -> 1,30/kg
            pp(3, "x", "Beta Distribuzione", "2025-04-02", "3", "pz", 300), // size unknown
        )
        val rows = ProductFamilies.compare(variants, purchases)
        assertEquals(3, rows.size)
        val a = rows.single { it.productId == 1L }
        assertEquals(0, BigDecimal("1.05").compareTo(a.paid)) // only the latest purchase
        assertEquals(0, BigDecimal("1.5").compareTo(a.perBase))
        assertEquals("kg", a.baseUnit)
        assertEquals("Valleverde", a.brand)
        val b = rows.single { it.productId == 2L }
        assertEquals(0, BigDecimal("1.3").compareTo(b.perBase))
        assertTrue(b.cheapest)
        assertFalse(a.cheapest)
        val c = rows.single { it.productId == 3L }
        assertNull(c.perBase) // never guessed
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.productId })
    }

    @Test fun neverComparesWithAndWithoutVat() {
        val variants = listOf(ProductFamilies.Variant(1, "MOZZARELLA A", null), ProductFamilies.Variant(2, "MOZZARELLA B", null))
        val rows = ProductFamilies.compare(
            variants,
            listOf(
                pp(1, "x", "Alfa", "2025-04-01", "2", "kg", 1600, VatBasis.EXCLUSIVE),
                pp(2, "x", "Beta", "2025-04-01", "2", "kg", 1500, VatBasis.INCLUSIVE),
            ),
        )
        assertTrue(rows.none { it.cheapest })
    }

    @Test fun cartonSizeOnlyWhenACartonWasBought() {
        val variants = listOf(ProductFamilies.Variant(1, "POLPA 6X400G", null), ProductFamilies.Variant(2, "POLPA ORTO 6X400G", null))
        val rows = ProductFamilies.compare(
            variants,
            listOf(
                pp(1, "x", "Alfa", "2025-04-01", "1", "ct", 480), // 4,80 / 2,4 kg = 2,00/kg
                pp(2, "x", "Alfa", "2025-04-01", "6", "pz", 480), // by the piece: unclear, not compared
            ),
        )
        assertEquals(0, BigDecimal("2").compareTo(rows.single { it.productId == 1L }.perBase))
        assertNull(rows.single { it.productId == 2L }.perBase)
    }

    @Test fun operatorConversionGivesTheSize() {
        val rows = ProductFamilies.compare(
            listOf(ProductFamilies.Variant(1, "BURRATA", null)),
            listOf(pp(1, "x", "Alfa", "2025-04-01", "4", "pz", 1000)), // 2,50 per piece
            mapOf(1L to listOf(UnitConversion("pz", "g", BigDecimal("250")))),
        )
        assertEquals(0, BigDecimal("10").compareTo(rows.single().perBase)) // 2,50 / 0,25 kg
    }

    @Test fun priceAlertsFromFivePercent() {
        fun ch(p: String) = PriceChange(1, "x", "kg", VatBasis.EXCLUSIVE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal(p), null, "a", 1, null, "a", 2)
        val alerts = PriceWatch.alerts(listOf(ch("4.9"), ch("5.0"), ch("-7.5"), ch("1.2")))
        assertEquals(listOf("-7.5", "5.0"), alerts.map { it.percent.toPlainString() })
    }
}
