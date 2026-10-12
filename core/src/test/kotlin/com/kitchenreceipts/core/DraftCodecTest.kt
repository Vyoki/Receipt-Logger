package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DraftCodecTest {

    private val choice = LineChoice(BigDecimal("2"), BigDecimal("5"), 1000)

    private fun draft() = DocumentDraft(
        seller = DraftField("ABC S.r.l.", uncertain = true, source = "logo"),
        date = DraftField("03/09/2026"),
        number = DraftField("B26 305511"),
        total = DraftField("1.234,50", uncertain = true),
        vatBasis = VatBasis.EXCLUSIVE,
        lotsPrinted = false,
        sellerAlternatives = listOf("ABC SRL"),
        items = listOf(
            LineItemDraft(
                key = 1, description = DraftField("MOZZARELLA \"FIOR\" 125G\\n"), quantity = DraftField("2", uncertain = true),
                unitPrice = DraftField("5"), lineTotal = DraftField("10,00"), productId = 42, productName = "Mozzarella",
                productSource = ProductSource.REMEMBERED, choices = listOf(choice), aiRead = mapOf("quantity" to "3"),
                marks = listOf(ItemMark("storage", "F", "fresco")),
            ),
            LineItemDraft(key = 10_000, description = DraftField("Riga aggiunta\nsu due righe"), newProductName = "Basilico"),
        ),
    )

    @Test fun everyEditableValueComesBackExactly() {
        val d = draft()
        val back = DraftCodec.decode(DraftCodec.encode(d), hints = d)!!
        assertEquals(d, back)
    }

    @Test fun hintsOnlyFillWhatTheOperatorCannotType() {
        val d = draft()
        // The operator picked a reading on line 1 (choices gone) and confirmed the supplier.
        val edited = d.copy(seller = d.seller.confirmed(), items = d.items.map { if (it.key == 1L) it.pick(choice) else it })
        val back = DraftCodec.decode(DraftCodec.encode(edited), hints = d)!!
        assertTrue(back.items.first().choices.isEmpty())
        assertTrue(back.sellerAlternatives.isEmpty())
        assertEquals(edited.items.first().quantity, back.items.first().quantity)
        // Without hints the values are still all there.
        val bare = DraftCodec.decode(DraftCodec.encode(edited))!!
        assertEquals(edited.items.map { it.quantity }, bare.items.map { it.quantity })
        assertEquals(edited.seller, bare.seller)
    }

    @Test fun damagedTextIsIgnored() {
        assertNull(DraftCodec.decode("{\"v\":\"1\",\"seller\""))
        assertNull(DraftCodec.decode("{\"v\":\"99\"}"))
    }
}
