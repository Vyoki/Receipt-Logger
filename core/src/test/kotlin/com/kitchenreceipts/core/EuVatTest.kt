package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VAT numbers of other EU countries (all invented, made with each country's check digits). */
class EuVatTest {

    @Test fun eachCountrysCheckDigits() {
        for (v in listOf("DE123456788", "ATU12345675", "FR11123456782", "BE0123456749", "NL123456782B01", "NL100000000B23")) {
            assertTrue(v, EuVat.isValid(v))
        }
        // One digit misread: the check fails.
        for (v in listOf("DE123456789", "ATU12345676", "FR12123456782", "BE0123456748", "NL123456783B01", "NL100000000B24")) {
            assertFalse(v, EuVat.isValid(v))
        }
        // A country whose check is not known here is never valid; nor an Italian number written with its prefix.
        assertFalse(EuVat.isValid("ES12345678Z"))
        assertFalse(EuVat.isValid("IT01234567897"))
    }

    @Test fun foundWhereItIsPrinted() {
        assertEquals(listOf("DE123456788"), EuVat.find("Identificativo fiscale ai fini IVA: DE123456788"))
        assertEquals(listOf("DE123456788"), EuVat.find("USt-IdNr. DE 123 456 788 Muster"))
        assertEquals(listOf("ATU12345675"), EuVat.find("UID ATU12345675"))
        // Words and numbers that only look like one.
        assertEquals(emptyList<String>(), EuVat.find("DE CECCO 123456789 Cap: 00000 Nazione: DE"))
        assertEquals(emptyList<String>(), EuVat.find("FRESCO 12345678901"))
    }

    @Test fun theSupplierBoxOfAForeignSupplier() {
        val page = listOf(
            EInvoicePrintoutFixture.t(102, 172, "Cedente/prestatore (fornitore)"),
            EInvoicePrintoutFixture.t(1263, 172, "Cessionario/committente (cliente)"),
            EInvoicePrintoutFixture.t(128, 234, "Identificativo fiscale ai fini IVA: DE123456788"),
            EInvoicePrintoutFixture.t(1289, 234, "Identificativo fiscale ai fini IVA: IT09876543217"),
            EInvoicePrintoutFixture.t(128, 284, "Denominazione: MUSTER HANDEL GMBH"),
            EInvoicePrintoutFixture.t(1289, 284, "Denominazione: RISTORANTE PROVA SAS"),
        )
        val s = Parties.supplier(listOf(LayoutRows.layout(page)), "09876543217")
        assertEquals("DE123456788", s?.vatNumber)
        assertEquals("MUSTER HANDEL GMBH", s?.name?.value)
    }
}
