package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** The office copy: encrypted, exact, and safe inside a web page. Content invented. */
class OfficeExportTest {

    /** The viewer page shipped with the app (found from the core or the repository folder). */
    private fun template(): String {
        var dir: File? = System.getProperty("kr.repo")?.let(::File) ?: File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/assets/office-viewer.html")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("office-viewer.html not found")
    }

    private fun snapshot(): OfficeExport.Snapshot {
        val r = Random(7)
        val sellers = listOf(
            OfficeExport.Seller(1, "ABC S.r.l.", "IT01234567897"),
            OfficeExport.Seller(2, "VERDE FRESCO S.p.A.", "IT09876543217"),
            OfficeExport.Seller(3, "Macelleria Esempio", null),
        )
        data class P(val id: Long, val name: String, val brand: String?, val cat: Category, val unit: String, val price: Double, val seller: Long, val vat: Int)
        val products = listOf(
            P(1, "Pasta Pipette Rigate n.86", "Barilla", Category.DRY_GOODS, "pz", 0.54, 1, 4),
            P(2, "Passata di pomodoro", "Cirio", Category.DRY_GOODS, "pz", 1.34, 1, 4),
            P(3, "Mozzarella fior di latte", null, Category.DAIRY_EGGS, "kg", 8.90, 2, 4),
            P(4, "Zucchine", null, Category.FRUIT_VEG, "kg", 2.19, 2, 4),
            P(5, "Lombo bovino adulto con osso", null, Category.MEAT, "kg", 18.55, 3, 10),
            P(6, "Olio extravergine d'oliva 5 l", null, Category.DRY_GOODS, "pz", 39.90, 1, 4),
            P(7, "Acqua minerale frizzante 1 l", "Frasassi", Category.BEVERAGES, "pz", 0.25, 1, 22),
            P(8, "Detergente piatti \"Limone\" </script>", null, Category.CLEANING, "pz", 3.78, 1, 22),
        )
        val docs = mutableListOf<OfficeExport.Document>()
        var id = 1L
        for (month in 0 until 12) for (sellerId in 1L..3L) {
            val date = LocalDate.of(2025, 11, 3).plusMonths(month.toLong()).plusDays(sellerId * 4)
            val lines = products.filter { it.seller == sellerId }.map { p ->
                // Prices drift up a little over the year; quantities vary.
                val price = BigDecimal(p.price * (1 + month * 0.006)).setScale(if (p.unit == "kg") 3 else 2, java.math.RoundingMode.HALF_UP)
                val qty = if (p.unit == "kg") BigDecimal(r.nextInt(1000, 9000)).movePointLeft(3) else BigDecimal(r.nextInt(1, 24))
                val total = price.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).toLong()
                OfficeExport.Line(
                    p.name.uppercase(), p.id, qty, p.unit, price, total, BigDecimal(p.vat),
                    if (p.cat == Category.MEAT || p.cat == Category.DAIRY_EGGS) "L${month + 10}-${100 + p.id}" else null,
                    if (p.cat == Category.DAIRY_EGGS) date.plusDays(12) else null, null, null,
                )
            }
            val sub = lines.sumOf { it.totalCents!! }
            val vat = lines.sumOf { BigDecimal(it.totalCents!!).multiply(it.vatRate!!).movePointLeft(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong() }
            docs += OfficeExport.Document(
                id++, sellerId, date, "${sellerId}A/${1000 + month}", "EUR", sub, vat, sub + vat, VatBasis.EXCLUSIVE,
                listOf("photo", "pdf", "e-invoice")[(month + sellerId.toInt()) % 3], lines,
            )
        }
        return OfficeExport.Snapshot(
            "RISTORANTE PROVA SAS", LocalDateTime.of(2026, 10, 4, 9, 30), sellers, listOf(OfficeExport.Family(1, "Passata di pomodoro")),
            products.map { OfficeExport.Product(it.id, it.name, it.brand, it.cat.key, it.id % 2 == 0L, if (it.id == 2L) 1 else null) },
            docs,
        )
    }

    @Test fun roundTripIsExact() {
        val json = OfficeExport.json(snapshot())
        val env = OfficeExport.encrypt(json, "una password lunga".toCharArray(), iterations = 1000)
        assertFalse(env.contains("Pasta")) // nothing readable without the password
        assertEquals(json, OfficeExport.decrypt(env, "una password lunga".toCharArray()))
        assertTrue(runCatching { OfficeExport.decrypt(env, "sbagliata!".toCharArray()) }.isFailure)
        assertTrue(runCatching { OfficeExport.encrypt(json, "corta".toCharArray()) }.isFailure)
    }

    @Test fun jsonIsSafeInsideThePage() {
        val json = OfficeExport.json(snapshot())
        assertFalse(json.contains("</script>"))
        assertTrue(json.contains("\\\"Limone\\\" \\u003c/script>"))
        assertTrue(json.contains("\"pr\":\"0.54\"")) // decimals as exact text
        val page = OfficeExport.page(template(), "{\"format\":\"x\"}")
        assertTrue(page.contains("/*KR-DATA*/{\"format\":\"x\"}/*KR-END*/"))
        assertTrue(page.contains("connect-src 'none'"))
    }

    /** OFFICE_SAMPLE=<file>: writes a full office copy (password "prova-ufficio") for the browser check in CI. */
    @Test fun sampleForTheBrowserCheck() {
        val out = System.getenv("OFFICE_SAMPLE") ?: return
        val env = OfficeExport.encrypt(OfficeExport.json(snapshot()), "prova-ufficio".toCharArray())
        File(out).writeText(OfficeExport.page(template(), env))
    }
}
