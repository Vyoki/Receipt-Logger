package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The suggested category of a supplier, from its invoices' lines (invented). Only ever a suggestion. */
class SupplierCategoriesTest {

    private fun suggest(vararg lines: String) = SupplierCategories.suggest(lines.toList())

    @Test fun fuelInLitres() {
        assertEquals(SupplierCategory.FUEL, suggest("Rifornimento di GASOLIO del 11/03/2031 20,50 1,60 Lt 22,00 32,80"))
        assertEquals(SupplierCategory.FUEL, suggest("27101943 ( CARB ) Rifornimento 32,80"))
    }

    @Test fun electricityBill() {
        assertEquals(SupplierCategory.UTILITIES, suggest("POD/PDR: IT001E00000001 2.000,000 0,1000 KWH 22,00 200,00", "ACCISA variabile 1,00 10,00"))
        // One sign alone is not enough.
        assertNull(suggest("Accisa assolta"))
    }

    @Test fun kindOfProducts() {
        assertEquals(SupplierCategory.FOOD, suggest(
            "PANCETTA TESA 12,50 9,20 kg 10,00 115,00", "POMODORO CILIEGINO 6,50 2,40 KG 4,00 15,60",
            "MOZZARELLA FIOR DI LATTE 3,00 7,00 KG 4,00 21,00", "BIRRA CHIARA BOTT. CL.33X3 8,00 2,40 PZ 22,00 19,20",
        ))
        assertEquals(SupplierCategory.DRINKS, suggest(
            "VINO ROSSO DOC 6,00 5,00 PZ 22,00 30,00", "BIRRA CHIARA CL.33 24,00 0,80 PZ 22,00 19,20", "ACQUA MINERALE NAT. 1,5 12,00 0,30 PZ 22,00 3,60",
        ))
        assertEquals(SupplierCategory.SUPPLIES, suggest(
            "DETERSIVO PIATTI LT.5 2,00 6,00 PZ 22,00 12,00", "SACCHI SPAZZATURA 2,00 3,00 PZ 22,00 6,00", "CANDEGGINA LT.4 1,00 1,50 PZ 22,00 1,50",
        ))
        assertNull(suggest())
        assertNull(suggest("ARTICOLO 1,00"))
    }

    @Test fun fromTheInvoicesOfABundle() {
        val segs = InvoiceBundle.find(
            EInvoicePrintoutFixture.bundle(listOf(EInvoicePrintoutFixture.ENERGY, EInvoicePrintoutFixture.MEAT)), CUSTOMER_VAT,
        )
        assertEquals(listOf(SupplierCategory.UTILITIES, SupplierCategory.FOOD), segs.map { SupplierCategories.suggest(it.lines) })
    }

    @Test fun keys() {
        for (c in SupplierCategory.entries) assertEquals(c, SupplierCategory.fromKey(c.key))
        assertNull(SupplierCategory.fromKey("nonsense"))
        assertNull(SupplierCategory.fromKey(null))
        assertEquals(listOf("utilities", "fuel", "equipment", "other"), SupplierCategory.NOT_GOODS_KEYS)
    }
}
