package com.kitchenreceipts.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.SellerLearning
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.DuplicateReason
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ReceiptRepository

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ReceiptRepository(db, FileStore(context))
    }

    @After fun tearDown() = db.close()

    private fun doc(seller: String, number: String, items: List<ValidLineItem>, total: Long = 7106) = ValidDocument(
        sellerName = seller, date = LocalDate.of(2025, 3, 14), number = number, currency = "EUR",
        subtotalCents = null, vatCents = null, totalCents = total, vatBasis = VatBasis.EXCLUSIVE, items = items,
    )

    private fun item(desc: String, productId: Long?, qty: String, unit: String, cents: Long) =
        ValidLineItem(desc, productId, BigDecimal(qty), unit, null, cents, null, null, null)

    @Test fun saveLinksItemsRemembersAssignmentsAndAverages() = runBlocking {
        val mozz = repo.createProduct("Mozzarella fior di latte")
        val file = StoredFile("documents/test.jpg", "image/jpeg", 1, "sha-1")
        val id = repo.saveDocument(doc("Caseificio Valverde S.r.l.", "145", listOf(item("MZ01 Mozzarella", mozz.id, "2", "kg", 2000))), file, "ocr", null)

        val items = repo.itemsOnce(id)
        assertEquals(id, items.single().item.documentId) // link to source document
        // Assignment is remembered for the same seller + description (spelling of seller may differ)
        assertEquals(mozz.id, repo.rememberedProduct("CASEIFICIO VALVERDE SRL", "mz01 mozzarella"))

        repo.saveDocument(doc("Caseificio Valverde", "160", listOf(item("MZ01 Mozzarella", mozz.id, "8", "kg", 6000)), 6000), file.copy(sha256 = "sha-2"), null, null)
        val summary = repo.productSummaries().first().single { it.product.id == mozz.id }.summary
        val avg = summary.averages.single()
        assertEquals(0, BigDecimal("8.0000").compareTo(avg.averageUnitCost))
        assertEquals(2, avg.purchaseCount)
        assertEquals(1, repo.sellerStats().first().size) // same seller despite different spelling
    }

    @Test fun duplicateWarningAndDeleteCascade() = runBlocking {
        val file = StoredFile("documents/test2.jpg", "image/jpeg", 1, "sha-x")
        val d = doc("Mercato Fresco", "42", listOf(item("Limoni", null, "2", "pz", 240)))
        val id = repo.saveDocument(d, file, null, null)

        val dups = repo.findDuplicates(d, "sha-x", null)
        assertTrue(dups.single().match.reasons.containsAll(setOf(DuplicateReason.SAME_FILE, DuplicateReason.SAME_SELLER_AND_NUMBER)))
        // Editing the same document is not a duplicate of itself
        assertTrue(repo.findDuplicates(d, null, id).isEmpty())

        repo.deleteDocument(id)
        assertTrue(repo.itemsOnce(id).isEmpty())
        assertTrue(repo.sellerStats().first().isEmpty())
    }

    @Test fun learnsSupplierAndRecognisesItNextTime() = runBlocking {
        val text = "CASEIFICIO VALVERDE S.R.L.\nLatticini freschi\nP.IVA IT01234567897\nSpett.le RISTORANTE P.IVA 09876543217\nFattura n. 1"
        val file = StoredFile("documents/l.jpg", "image/jpeg", 1, "sha-l")
        repo.saveDocument(
            doc("Caseificio Valverde S.r.l.", "1", emptyList()), file, text, null,
            SellerLearning(text, "CASEIFICI0 VALVERDE", "09876543217"),
        )
        // Same supplier, name misread differently, own VAT number also printed: recognised by the supplier's VAT number.
        val next = text.replace("CASEIFICIO", "CA5EIFIC10")
        val r = repo.identifySeller(next, "CA5EIFIC10 VALVERDE", "09876543217")!!
        assertEquals("Caseificio Valverde S.r.l.", r.match.name)
        assertEquals(SellerMatchReason.VAT_NUMBER, r.match.reason)
        // The misreading the operator corrected is remembered too.
        val byAlias = repo.identifySeller("CASEIFICI0 VALVERDE\nFattura n. 2", "CASEIFICI0 VALVERDE", null)!!
        assertEquals(SellerMatchReason.NAME_ALIAS, byAlias.match.reason)
    }

    @Test fun operatorsOwnVatNumberIsNeverLearnedAsASuppliers() = runBlocking {
        var detected: String? = null
        repo.onSharedVatNumber = { detected = it }
        // Two different suppliers whose documents only show the customer's (operator's) VAT number.
        val own = "09876543217"
        val a = "FORNITORE UNO S.R.L.\nSpett.le RISTORANTE P.IVA $own"
        val b = "FORNITORE DUE S.R.L.\nSpett.le RISTORANTE P.IVA $own"
        repo.saveDocument(doc("Fornitore Uno", "1", emptyList()), StoredFile("documents/a.jpg", "image/jpeg", 1, "sha-a"), a, null, SellerLearning(a, null, null))
        repo.saveDocument(doc("Fornitore Due", "2", emptyList()), StoredFile("documents/b.jpg", "image/jpeg", 1, "sha-b"), b, null, SellerLearning(b, null, null))
        assertEquals(own, detected)
        // Neither supplier is identified by the operator's number any more.
        val r = repo.identifySeller("ALTRO FORNITORE\nSpett.le RISTORANTE P.IVA $own", "ALTRO FORNITORE", null)
        assertTrue(r == null || r.match.reason != SellerMatchReason.VAT_NUMBER)
    }

    @Test fun productRememberedByArticleCodeEvenIfDescriptionIsMisread() = runBlocking {
        val galletti = repo.createProduct("Biscotti Galletti")
        val line = ValidLineItem("BISCOTTI M.BIANCO GALLETTI 800", galletti.id, BigDecimal.ONE, "pz", null, 345, null, null, null, "2046225")
        repo.saveDocument(doc("Cash and Carry Esempio", "7", listOf(line)), StoredFile("documents/c.jpg", "image/jpeg", 1, "sha-c"), null, null)
        assertEquals(galletti.id, repo.rememberedProduct("Cash and Carry Esempio", "BISC0TTI M.BIANC0 GALLETT1 800", "2046225"))
    }
}
