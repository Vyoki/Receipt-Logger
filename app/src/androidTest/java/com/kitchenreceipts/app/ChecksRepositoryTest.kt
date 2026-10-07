package com.kitchenreceipts.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.ChecksRepository
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.DifferenceKind
import com.kitchenreceipts.core.DocKind
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** A delivery note and the invoice that names it: matched, counted once, compared, checked against an agreed price. */
@RunWith(AndroidJUnit4::class)
class ChecksRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ReceiptRepository
    private lateinit var checks: ChecksRepository

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ReceiptRepository(db, FileStore(context))
        checks = ChecksRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun item(desc: String, pid: Long?, qty: String, cents: Long) = ValidLineItem(desc, pid, BigDecimal(qty), "kg", null, cents, null, "L1", LocalDate.of(2026, 10, 12))

    private fun doc(number: String, date: LocalDate, total: Long, items: List<ValidLineItem>) = ValidDocument(
        sellerName = "VERDE FRESCO S.p.A.", date = date, number = number, currency = "EUR",
        subtotalCents = null, vatCents = null, totalCents = total, vatBasis = VatBasis.EXCLUSIVE, items = items,
    )

    @Test fun deliveryNoteCoveredByItsInvoice() = runBlocking<Unit> {
        val tom = repo.createProduct("Pomodori").id
        val zuc = repo.createProduct("Zucchine").id
        val ddt = repo.saveDocument(
            doc("101", LocalDate.of(2026, 10, 3), 4000, listOf(item("POMODORI", tom, "10", 2000), item("ZUCCHINE", zuc, "8", 2000))),
            StoredFile("documents/ddt.jpg", "image/jpeg", 1, "sha-ddt"), "VERDE FRESCO S.p.A.\nDOCUMENTO DI TRASPORTO N. 101 DEL 03/10/2026", null,
        )
        assertEquals(1, repo.reportDocuments().first().size)
        val inv = repo.saveDocument(
            doc("45", LocalDate.of(2026, 10, 31), 4500, listOf(item("POMODORI", tom, "10", 2000), item("ZUCCHINE", zuc, "10", 2500))),
            StoredFile("documents/inv.jpg", "image/jpeg", 1, "sha-inv"), "VERDE FRESCO S.p.A.\nFATTURA DIFFERITA N. 45\nRif. DDT n. 101 del 03/10/2026", null,
        )
        val docs = checks.documents().first().associateBy { it.id }
        assertEquals(DocKind.DELIVERY_NOTE.name, docs.getValue(ddt).kind)
        assertEquals(DocKind.INVOICE.name, docs.getValue(inv).kind)
        assertEquals(inv, docs.getValue(ddt).coveredBy)
        // Spending counts the invoice only.
        assertEquals(listOf(4500L), repo.reportDocuments().first().map { it.totalCents })
        // Zucchine: 8 kg delivered, 10 kg charged.
        val ic = checks.invoiceCheck(inv)!!
        assertEquals(listOf(DifferenceKind.MORE_INVOICED), ic.differences.map { it.kind })
        // The same goods on both documents show once in the expiry list.
        assertEquals(2, checks.expiring(LocalDate.of(2026, 10, 7)).first().size)
        // Agreed price for zucchine: 2,00/kg; the invoice charges 2,50.
        checks.setAgreedPrice(zuc, null, "kg", BigDecimal("2.00"), VatBasis.EXCLUSIVE)
        val over = checks.overCharges().first()
        assertEquals(1, over.size)
        assertEquals(inv, over.single().second.documentId)
        // Deleting the invoice makes the delivery note count again.
        repo.deleteDocument(inv)
        assertNull(checks.documents().first().single().coveredBy)
        assertEquals(listOf(4000L), repo.reportDocuments().first().map { it.totalCents })
    }

    @Test fun lotsFoundAsTyped() = runBlocking<Unit> {
        repo.saveDocument(
            doc("7", LocalDate.of(2026, 10, 1), 1000, listOf(ValidLineItem("MOZZARELLA", null, BigDecimal("2"), "kg", null, 1000, null, "L.24/0187", null))),
            StoredFile("documents/m.jpg", "image/jpeg", 1, "sha-m"), "BOLLA DI CONSEGNA 7", null,
        )
        assertEquals(1, checks.searchLots("240187").first().size)
        assertEquals(1, checks.searchLots("mozzarella").first().size)
        assertTrue(checks.searchLots("999").first().isEmpty())
    }

    @Test fun creditsOpenAndClose() = runBlocking<Unit> {
        val id = repo.saveDocument(doc("9", LocalDate.of(2026, 10, 1), 1000, emptyList()), StoredFile("documents/c.jpg", "image/jpeg", 1, "sha-c"), null, null)
        val sellerId = checks.documents().first().single { it.id == id }.sellerId
        val credit = checks.addCredit(sellerId, id, "Cassa di zucchine mancante", 1200)
        assertEquals(1, checks.openCredits().first().size)
        checks.closeCredit(credit)
        assertEquals(0, checks.openCredits().first().size)
    }
}
