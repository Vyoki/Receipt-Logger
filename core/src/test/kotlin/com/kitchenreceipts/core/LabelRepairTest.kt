package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelRepairTest {
    private fun line(t: String) = OcrLine(t, 0, 0, t.length * 9, 16)
    private fun fix(t: String) = OcrCleanup.repairLabels(listOf(line(t))).single().text

    @Test fun headingAndLabelWordsWithACameraSlipAreRestored() {
        assertEquals("ID LOTTO QTA.LOT.", fix("ID LOTTO0 QTA.LOT."))
        assertEquals("TOTALE IMPONIBILE 70,03", fix("T0TALE IMPONIBILE 70,03"))
        assertEquals("CODICE DESCRIZIONE QUANTITA PREZZO", fix("CODICE DESCRIZIONE QUANTlTA PREZZO"))
        assertEquals("TOTALE IMPONIBILE", fix("TOTALE IMP0NIBILE"))
    }

    @Test fun cleanWordsAndProductRowsAreNeverTouched() {
        // A real word close to a label is not a slip.
        assertEquals("ESEMPIO FORNITURE S.R.L.", fix("ESEMPIO FORNITURE S.R.L."))
        // A product row has no label words: nothing changes, whatever its words look like.
        assertEquals("10003 SALE MARINO FINO NR 10 0,422 4,62", fix("10003 SALE MARINO FINO NR 10 0,422 4,62"))
        assertEquals("LOTT0 DI PROVA", fix("LOTT0 DI PROVA"))
    }
}
