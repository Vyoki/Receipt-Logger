package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A supplier's suggested category from its invoices (invented lines), and where the choice is remembered. */
class SupplierCategoriesTest {

    private fun suggest(vararg lines: String) = SupplierCategories.suggestFromLines(lines.toList())

    @Test fun electricityBill() {
        assertEquals(SupplierCategory.UTILITIES, suggest("POD/PDR: IT001E00000001 2.000,000 0,1000 KWH 22,00 200,00", "ACCISA variabile 1,00 10,00"))
        assertEquals(SupplierCategory.UTILITIES, suggest("Quota fissa 1,00 10,00", "Quota potenza 3,00 kW 4,50", "Accise 2,00"))
        assertEquals(SupplierCategory.UTILITIES, suggest("Oneri generali di sistema 12,00", "Servizio di dispacciamento 3,00"))
        // One sign alone is not enough (a fuel invoice prints its excise too).
        assertNull(suggest("Accisa assolta"))
        assertNull(suggest("Quota fissa 1,00 10,00"))
    }

    @Test fun fuelInvoice() {
        assertEquals(SupplierCategory.FUEL, suggest("Rifornimento di GASOLIO del 11/03/2031 20,50 1,60 Lt 22,00 32,80"))
        assertEquals(SupplierCategory.FUEL, suggest("27101943 ( CARB ) GASOLIO AUTOTRAZIONE 32,80"))
        assertEquals(SupplierCategory.FUEL, suggest("27101943 BENZ. SP 20,00 L 1,80 36,00"))
        // Fuel comes before the product guesser, which reads some abbreviations as food.
        assertEquals(SupplierCategory.FUEL, suggest("BENZINA 20,00 LT 1,80 36,00", "PANCETTA TESA 12,50 9,20 kg 10,00 115,00", "POMODORO CILIEGINO 6,50 2,40 KG 4,00 15,60"))
        // A fuel word without litres, or an 8-digit code of a food wholesaler, is no fuel.
        assertNull(suggest("Contributo carburante 1,00 2,00"))
        assertEquals(SupplierCategory.FOOD, suggest("27101234 PANCETTA TESA 12,50 9,20 kg 10,00 115,00", "27101235 POMODORO CILIEGINO 6,50 2,40 KG 4,00 15,60"))
        // "l'" is no litre.
        assertNull(suggest("Rifornimento dell'automezzo l'11/03 1,00"))
    }

    @Test fun kindOfProducts() {
        // A wholesaler with 70% food and 30% drinks sells food.
        val food = List(7) { "POLLO INTERO 10,00 4,50 KG 10,00 45,00" } + List(3) { "BIRRA CHIARA CL.33 24,00 0,80 PZ 22,00 19,20" }
        assertEquals(SupplierCategory.FOOD, SupplierCategories.suggestFromLines(food))
        assertEquals(SupplierCategory.DRINKS, suggest(
            "VINO ROSSO DOC 6,00 5,00 PZ 22,00 30,00", "BIRRA CHIARA CL.33 24,00 0,80 PZ 22,00 19,20", "ACQUA MINERALE NAT. 1,5 12,00 0,30 PZ 22,00 3,60",
        ))
        assertEquals(SupplierCategory.SUPPLIES, suggest(
            "DETERSIVO PIATTI LT.5 2,00 6,00 PZ 22,00 12,00", "SACCHI SPAZZATURA 2,00 3,00 PZ 22,00 6,00", "CANDEGGINA LT.4 1,00 1,50 PZ 22,00 1,50",
        ))
        // A name printed letter by letter still names its product; the payment and VAT summary rows are no products.
        assertEquals(SupplierCategory.SUPPLIES, suggest(
            "Z ( Codice articolo ) S A P O N E L I Q U I D O 2,00 1,50 PZ 22,00 3,00", "S P U G N E 3,00 0,80 PZ 22,00 2,40",
            "MP08 Carta di pagamento 5,40", "I (esigibilità immediata) 22,00 5,40 1,19",
        ))
        assertNull(suggest("MP08 Carta di pagamento 97,61", "MP08 Carta di pagamento 12,00"))
        // Half food, half drinks: no majority, no suggestion.
        assertNull(suggest("POLLO INTERO 10,00 4,50 KG 10,00 45,00", "BIRRA CHIARA CL.33 24,00 0,80 PZ 22,00 19,20"))
        assertNull(suggest())
        assertNull(suggest("ARTICOLO 1,00"))
    }

    @Test fun hardwareGetsNoSuggestion() {
        // Repairs and other expenses are never suggested: the operator taps them.
        assertNull(suggest("VITE AUTOFILETTANTE M6 100,00 0,05 PZ 22,00 5,00", "TRAPANO A BATTERIA 1,00 89,00 PZ 22,00 89,00", "MANODOPERA TECNICO 2,00 35,00 ORE 22,00 70,00"))
    }

    @Test fun aFewMisreadRowsDoNotMakeAHardwareSupplierFood() {
        // Mostly rows of no known kind, two that the product guesser reads as food: no majority, no suggestion.
        assertNull(suggest(
            "VITE AUTOFILETTANTE M6 100,00 0,05 PZ 22,00 5,00", "TASSELLO NYLON 8MM 50,00 0,10 PZ 22,00 5,00",
            "SILICONE SIGILLANTE 2,00 6,00 PZ 22,00 12,00", "NASTRO ISOLANTE 3,00 1,50 PZ 22,00 4,50",
            "PANE 1,00 2,00 PZ 4,00 2,00", "LATTE 1,00 1,50 PZ 4,00 1,50",
        ))
    }

    @Test fun theLOfACompanyFormIsNoLitre() {
        // "S.R.L." next to a fuel word is no fuel sale.
        assertNull(SupplierCategories.suggest(SupplierCategories.hints(listOf("METANO ESEMPIO S.R.L. VIA PROVA 1"))))
        assertEquals(SupplierCategory.FUEL, SupplierCategories.suggest(SupplierCategories.hints(listOf("GASOLIO AUTOTRAZIONE 40,00 LT 1,60 64,00"))))
    }

    @Test fun fromThePagesOfABundle() {
        val pages = EInvoicePrintoutFixture.bundle(
            listOf(EInvoicePrintoutFixture.ENERGY, EInvoicePrintoutFixture.MEAT, EInvoicePrintoutFixture.WHOLESALER, EInvoicePrintoutFixture.HOUSEHOLD),
        ).mapIndexed { i, p -> InvoiceBundle.signals(i, p, CUSTOMER_VAT) }
        val expected = listOf(SupplierCategory.UTILITIES, SupplierCategory.FOOD, SupplierCategory.FOOD, SupplierCategory.SUPPLIES)
        assertEquals(expected, pages.map { SupplierCategories.suggest(listOf(it)) })
        // The same from the signals saved and read back (no rows of text kept).
        val back = pages.map { InvoiceBundle.decode(InvoiceBundle.encode(it))!! }
        assertEquals(expected, back.map { SupplierCategories.suggest(listOf(it)) })
        // Signs on different pages of one supplier add up; the same sign twice counts once.
        val pod = SupplierCategories.Hints(utility = 2)
        assertNull(SupplierCategories.suggest(pod + pod))
        assertEquals(SupplierCategory.UTILITIES, SupplierCategories.suggest(pod + SupplierCategories.Hints(utility = 4)))
    }

    @Test fun memoryKeys() {
        val vat = EInvoicePrintoutFixture.pIva("0123456789")
        val keys = SupplierCategories.memoryKeys("IT$vat", "ABC S.r.l.")
        assertEquals(2, keys.size)
        assertEquals("vat:$vat", keys[0])
        assertTrue(keys[1].startsWith("name#"))
        assertEquals(64, keys[1].removePrefix("name#").length)
        // Accents, case and the legal form do not matter.
        assertEquals(keys[1], SupplierCategories.memoryKeys(null, "abc srl")[0])
        assertEquals(SupplierCategories.memoryKeys(null, "Società Àbc")[0], SupplierCategories.memoryKeys(null, "SOCIETA ABC")[0])
        // The name is never stored readably: no letters of it in the key.
        assertFalse(keys[1].removePrefix("name#").contains(Regex("[g-z]")))
        // A VAT number with a wrong check digit is no key.
        assertEquals(listOf("name#"), SupplierCategories.memoryKeys(vat.dropLast(1) + ((vat.last() - '0' + 1) % 10), "ABC S.r.l.").map { it.take(5) })
        assertEquals(emptyList<String>(), SupplierCategories.memoryKeys(null, "  "))
    }

    @Test fun keys() {
        for (c in SupplierCategory.entries) assertEquals(c, SupplierCategory.fromKey(c.key))
        assertNull(SupplierCategory.fromKey("nonsense"))
        assertNull(SupplierCategory.fromKey(null))
        assertEquals(listOf("utilities", "fuel", "equipment", "other"), SupplierCategory.NOT_GOODS_KEYS)
        assertEquals(listOf(SupplierCategory.FOOD, SupplierCategory.DRINKS, SupplierCategory.SUPPLIES), SupplierCategory.entries.filter { it.goods })
    }
}
