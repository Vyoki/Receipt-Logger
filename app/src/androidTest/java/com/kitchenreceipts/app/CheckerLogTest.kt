package com.kitchenreceipts.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.diagnostics.CheckerLog
import com.kitchenreceipts.core.CheckerCases
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DraftField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/** The checker's file on the phone: values of checked invoices (invented), counted, and shared as one zip. */
@RunWith(AndroidJUnit4::class)
class CheckerLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() { File(context.filesDir, "checker").deleteRecursively() }

    @Test fun recordsCountsAndSharesNumbersOnly() {
        val log = CheckerLog(context)
        assertEquals(0 to 0, log.counts())
        val shown = DocumentDraft(seller = DraftField("ABC S.r.l.", uncertain = true), date = DraftField("03/02/2031"), total = DraftField("12,40"))
        log.record(CheckerCases.cases("a", shown, shown.copy(seller = DraftField("ABC Srl")), null))
        log.record(CheckerCases.cases("b", shown, shown, null))
        assertEquals(2 to 6, log.counts())
        val shared = log.shareable()
        assertNotNull(shared)
        val uri = shared!!
        val names = mutableListOf<String>()
        var text = ""
        context.contentResolver.openInputStream(uri)!!.use { s ->
            ZipInputStream(s).use { z ->
                generateSequence { z.nextEntry }.forEach { e -> names += e.name; if (e.name.endsWith(".csv")) text = z.readBytes().decodeToString() }
            }
        }
        assertEquals(listOf("cases-v${CheckerCases.VERSION}.csv", "README.txt"), names)
        assertEquals(1 + 6, text.trim().lines().size) // the header once, then the values
        for (word in listOf("ABC", "12,40", "2031")) assertFalse(text.contains(word))
    }
}
