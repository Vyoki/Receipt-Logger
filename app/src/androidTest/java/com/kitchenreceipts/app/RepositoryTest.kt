package com.kitchenreceipts.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.CategoryMatch
import com.kitchenreceipts.app.data.FoodRepository
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.SellerLearning
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.PreparedDraft
import com.kitchenreceipts.app.learning.LearningStore
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DraftField
import com.kitchenreceipts.core.DuplicateReason
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.PeriodKind
import com.kitchenreceipts.core.ProductSource
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.SupplierCategories
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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

    @After fun tearDown() {
        db.close()
        memoryFiles.forEach { it.delete(); File(it.path + ".tmp").delete() }
    }

    private fun doc(seller: String, number: String, items: List<ValidLineItem>, total: Long = 7106, date: LocalDate = LocalDate.of(2025, 3, 14)) = ValidDocument(
        sellerName = seller, date = date, number = number, currency = "EUR",
        subtotalCents = null, vatCents = null, totalCents = total, vatBasis = VatBasis.EXCLUSIVE, items = items,
    )

    private fun item(desc: String, productId: Long?, qty: String, unit: String, cents: Long) =
        ValidLineItem(desc, productId, BigDecimal(qty), unit, null, cents, null, null, null)

    @Test fun lineDiscountIsSavedAndShownWithThePurchase() = runBlocking<Unit> {
        val meat = repo.createProduct("Aletta di spalla")
        val line = ValidLineItem("ALETTA DI SPALLA", meat.id, BigDecimal("6.8"), "kg", BigDecimal("18.90"), 8996, BigDecimal.TEN, "L1", null, discount = "30")
        val id = repo.saveDocument(doc("ABC S.r.l.", "B26 305511", listOf(line), 8996), StoredFile("documents/sc.jpg", "image/jpeg", 1, "sha-sc"), null, null)
        assertEquals("30", repo.itemsOnce(id).single().item.discount)
        assertEquals("30", repo.purchasesForProduct(meat.id).first().single().discount)
    }

    @Test fun saveLinksItemsRemembersAssignmentsAndAverages() = runBlocking<Unit> {
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

    @Test fun productGroupsSuggestConfirmAndCompare() = runBlocking<Unit> {
        val a = repo.createProduct("PASSATA VALLEVERDE 700G")
        val b = repo.createProduct("PASSATA COLLINA ROSSA 5KG")
        val file = StoredFile("documents/fam.jpg", "image/jpeg", 1, "sha-fam")
        repo.saveDocument(
            doc("Alfa Ingrosso", "7", listOf(item("PASSATA VALLEVERDE 700G", a.id, "12", "pz", 1260), item("PASSATA COLLINA ROSSA 5KG", b.id, "2", "pz", 1300)), 2560),
            file, null, null,
        )
        // Suggested, not applied.
        val s = repo.familySuggestions().first().single()
        assertEquals("Passata di pomodoro", s.name)
        assertTrue(repo.productsOnce().all { it.familyId == null })

        val fid = repo.addToFamily(s.name, s.productIds)
        assertTrue(repo.familySuggestions().first().isEmpty())
        val rows = repo.familyComparison(fid).first()
        assertEquals(2, rows.size)
        val cheapest = rows.single { it.cheapest }
        assertEquals(b.id, cheapest.productId) // 6,50 per 5 kg = 1,30/kg < 1,05 per 700 g = 1,50/kg
        assertEquals(0, BigDecimal("1.3").compareTo(cheapest.perBase))

        // Products stay separate: each keeps its own purchases.
        assertEquals(1, repo.purchasesForProduct(a.id).first().size)

        // Removing a product from its group: it is not suggested again.
        repo.dismissFamilySuggestion(listOf(a.id))
        assertEquals(1, repo.productsInFamily(fid).first().size)
        assertTrue(repo.familySuggestions().first().isEmpty())

        repo.deleteFamily(fid)
        assertTrue(repo.productsOnce().all { it.familyId == null })
        assertEquals(2, repo.productsOnce().size)
    }

    @Test fun duplicateWarningAndDeleteCascade() = runBlocking<Unit> {
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

    @Test fun learnsSupplierAndRecognisesItNextTime() = runBlocking<Unit> {
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

    @Test fun operatorsOwnVatNumberIsNeverLearnedAsASuppliers() = runBlocking<Unit> {
        var detected: String? = null
        repo.onSharedVatNumber = { detected = it }
        // Two different suppliers whose documents only show the customer's (operator's) VAT number.
        val own = "09876543217"
        val a = "FORNITORE UNO S.R.L.\nSpett.le RISTORANTE P.IVA $own"
        val b = "FORNITORE DUE S.R.L.\nSpett.le RISTORANTE P.IVA $own"
        repo.saveDocument(doc("Fornitore Uno", "1", emptyList()), StoredFile("documents/a.jpg", "image/jpeg", 1, "sha-a"), a, null, SellerLearning(a, null, null))
        repo.saveDocument(doc("Fornitore Due", "2", emptyList()), StoredFile("documents/b.jpg", "image/jpeg", 1, "sha-b"), b, null, SellerLearning(b, null, null))
        // The operator's number sits on the customer line: it is not learned as either supplier's VAT number.
        assertTrue(detected == null || detected == own)
        val r = repo.identifySeller("ALTRO FORNITORE\nSpett.le RISTORANTE P.IVA $own", "ALTRO FORNITORE", null)
        assertTrue(r == null || r.match.reason != SellerMatchReason.VAT_NUMBER)
    }

    @Test fun aVatFromTheSuppliersOwnBoxMovesToTheCleanNameAndNeverBecomesTheRestaurants() = runBlocking<Unit> {
        var detected: String? = null
        repo.onSharedVatNumber = { detected = it }
        val vat = "01234567897"
        // An earlier reading took words of the next box into the supplier's name.
        val polluted = "ABC S.R.L. CESSIONARIO COMMITTENTE\nCedente/prestatore (fornitore)\nP.IVA $vat\nFattura n. 1 del 03/02/2031"
        repo.saveDocument(doc("ABC S.r.l. Cessionario committente", "1", emptyList()), StoredFile("documents/c4a.pdf", "application/pdf", 1, "sha-c4a"), polluted, null, SellerLearning(polluted, null, null))
        assertEquals("ABC S.r.l. Cessionario committente", db.sellerDao().byVatNumber(vat)?.name)
        // The same supplier read cleanly, its number in its own role-labelled box.
        val clean = "Cedente/prestatore (fornitore)\nABC S.r.l.\nP.IVA $vat\nFattura n. 2 del 04/02/2031"
        repo.saveDocument(doc("ABC S.r.l.", "2", emptyList()), StoredFile("documents/c4b.pdf", "application/pdf", 1, "sha-c4b"), clean, null, SellerLearning(clean, null, null, vatFromRoleBox = true))
        // Not taken as the restaurant's own number, not forgotten: it moved to the supplier saved now.
        assertEquals(null, detected)
        assertEquals("ABC S.r.l.", db.sellerDao().byVatNumber(vat)?.name)
        // The next document of that supplier is recognised by it.
        val r = repo.identifySeller(clean.replace("n. 2", "n. 3"), "ABC S.r.l.", null, vatFromRoleBox = true)!!
        assertEquals("ABC S.r.l.", r.match.name)
        assertEquals(SellerMatchReason.VAT_NUMBER, r.match.reason)
    }

    @Test fun withoutTheRoleBoxAVatOnTwoSuppliersIsStillTheRestaurants() = runBlocking<Unit> {
        var detected: String? = null
        repo.onSharedVatNumber = { detected = it }
        val vat = "01234567897"
        val a = "ABC S.R.L.\nP.IVA $vat\nFattura n. 1 del 03/02/2031"
        val b = "VERDE FRESCO S.P.A.\nP.IVA $vat\nFattura n. 2 del 04/02/2031"
        repo.saveDocument(doc("ABC S.r.l.", "1", emptyList()), StoredFile("documents/c4c.pdf", "application/pdf", 1, "sha-c4c"), a, null, SellerLearning(a, null, null))
        repo.saveDocument(doc("VERDE FRESCO S.p.A.", "2", emptyList()), StoredFile("documents/c4d.pdf", "application/pdf", 1, "sha-c4d"), b, null, SellerLearning(b, null, null))
        // As before: one number on two suppliers' documents is the customer's, and is used for neither.
        assertEquals(vat, detected)
        assertEquals(null, db.sellerDao().byVatNumber(vat))
    }

    @Test fun aVatFromTheSuppliersOwnBoxIsNotForgottenWhenTheNameDisagrees() = runBlocking<Unit> {
        var detected: String? = null
        repo.onSharedVatNumber = { detected = it }
        val vat = "01234567897"
        // An earlier reading took the customer's name for the supplier's, with the supplier's number.
        val earlier = "RISTORANTE PROVA SAS\nCedente/prestatore (fornitore)\nP.IVA $vat\nFattura n. 5 del 03/02/2031"
        repo.saveDocument(doc("RISTORANTE PROVA SAS", "5", emptyList()), StoredFile("documents/c4e.pdf", "application/pdf", 1, "sha-c4e"), earlier, null, SellerLearning(earlier, null, null))
        val now = "Cedente/prestatore (fornitore)\nVERDE FRESCO S.p.A.\nP.IVA $vat\nFattura n. 6 del 04/02/2031"
        // The clearly printed name differs from the one saved under the number: the number is in the supplier's own
        // box, so it is the supplier's, never the restaurant's. Nothing is forgotten.
        repo.identifySeller(now, "VERDE FRESCO S.p.A.", null, ocrSellerReliable = true, vatFromRoleBox = true)
        assertEquals(null, detected)
        assertEquals("RISTORANTE PROVA SAS", db.sellerDao().byVatNumber(vat)?.name)
        // Without the role box: today's rule (the number is taken as the restaurant's).
        repo.identifySeller(now, "VERDE FRESCO S.p.A.", null, ocrSellerReliable = true)
        assertEquals(vat, detected)
    }

    @Test fun productRememberedByArticleCodeEvenIfDescriptionIsMisread() = runBlocking<Unit> {
        val galletti = repo.createProduct("Biscotti Galletti")
        val line = ValidLineItem("BISCOTTI M.BIANCO GALLETTI 800", galletti.id, BigDecimal.ONE, "pz", null, 345, null, null, null, "2046225")
        repo.saveDocument(doc("Cash and Carry Esempio", "7", listOf(line)), StoredFile("documents/c.jpg", "image/jpeg", 1, "sha-c"), null, null)
        assertEquals(galletti.id, repo.rememberedProduct("Cash and Carry Esempio", "BISC0TTI M.BIANC0 GALLETT1 800", "2046225"))
    }

    @Test fun newProductsAreCreatedOnSaveAndTyposFindThemAgain() = runBlocking<Unit> {
        val first = ValidLineItem("SALE MARINO GROSSO GR.1000", null, BigDecimal("10"), "pz", null, 422, null, null, null, newProductName = "Sale marino grosso gr.1000")
        repo.saveDocument(doc("Grossista Uno", "1", listOf(first)), StoredFile("documents/n1.jpg", "image/jpeg", 1, "sha-n1"), null, null)
        val created = repo.productsOnce().single()
        // Stored as "guessed from the name" (null), so better rules apply later; the guess is dry goods.
        assertEquals(null, created.category)
        assertEquals(Category.DRY_GOODS, Category.fromKey(created.category) ?: com.kitchenreceipts.core.Categories.guess(created.name))
        assertEquals(created.id, repo.itemsOnce(1).single().item.productId)

        // Another supplier, a misreading: the same product, not a new one.
        val draft = LineItemDraft(1, DraftField("SALE MARINO GROSOS GR 1000"), quantity = DraftField("20"), unit = DraftField("pz"), lineTotal = DraftField("9,20"))
        val linked = repo.autoAssign("Grossista Due", listOf(draft), createNew = true).single()
        assertEquals(created.id, linked.productId)
        assertEquals(ProductSource.RECOGNISED, linked.productSource)

        // 0,46 instead of 0,422 per piece: a price change against the other supplier's purchase.
        val changes = repo.priceChangesForDraft("Grossista Due", LocalDate.of(2025, 3, 20), VatBasis.EXCLUSIVE, listOf(linked), null)
        assertEquals(1, changes.size)
        assertTrue(changes.single().isIncrease)

        // Something different becomes a new product.
        val other = repo.autoAssign("Grossista Due", listOf(draft.copy(description = DraftField("ACETO DI VINO BIANCO LT. 1"))), createNew = true).single()
        assertEquals(null, other.productId)
        assertEquals(ProductSource.NEW, other.productSource)
    }

    @Test fun mergeMovesPurchasesAndDeletesTheDuplicate() = runBlocking<Unit> {
        val a = repo.createProduct("Mozzarella")
        val b = repo.createProduct("Mozarella")
        repo.saveDocument(doc("Caseificio", "1", listOf(item("Mozzarella", a.id, "1", "kg", 900))), StoredFile("documents/m1.jpg", "image/jpeg", 1, "sha-m1"), null, null)
        repo.saveDocument(doc("Caseificio", "2", listOf(item("Mozarella", b.id, "2", "kg", 1800))), StoredFile("documents/m2.jpg", "image/jpeg", 1, "sha-m2"), null, null)
        assertEquals(1, repo.possibleDuplicateProducts().size)
        repo.mergeProducts(b.id, a.id)
        assertEquals(listOf(a.id), repo.productsOnce().map { it.id })
        assertEquals(2, repo.productSummaries().first().single().purchaseCount)
        val inv = repo.inventory(Period.of(LocalDate.of(2025, 3, 14), PeriodKind.MONTH)).first()
        assertEquals(0, BigDecimal("3").compareTo(inv.lines.single().quantities.single().amount))
    }

    // ------------------------------------------------------------ supplier categories
    // Invented suppliers and numbers only (valid check digits), dated 2031.

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val memoryFiles = mutableListOf<File>()

    /** A file for the remembered supplier categories, deleted after the test. */
    private fun memoryFile(): File = File(context.cacheDir, "learning-test-${System.nanoTime()}.json").also { memoryFiles += it }

    private val settings: AppSettings get() = AppSettings(context)

    /** Runs [block] with no own VAT number set and price notifications on, then puts both back. */
    private fun withSettings(block: suspend () -> Unit) = runBlocking {
        val s = settings
        val own = s.ownVatNumber
        val alerts = s.priceAlerts
        s.ownVatNumber = ""
        s.priceAlerts = true
        try { block() } finally { s.ownVatNumber = own; s.priceAlerts = alerts }
    }

    private fun preparer(repo: ReceiptRepository, learning: LearningStore?) = DraftPreparer(repo, settings, AppLog(context, settings), learning)

    private fun prepared(seller: String, category: SupplierCategory?) =
        PreparedDraft(DocumentDraft(seller = DraftField(seller)), DocumentDraft(), null, emptyList(), emptyList(), null, supplierCategory = category)

    @Test fun aCategoryRememberedByVatOutlivesTheSupplierAndAppliesToItsNextInvoice() = withSettings {
        val learning = LearningStore(memoryFile())
        val repo = ReceiptRepository(db, FileStore(context), learning)
        val text = "ABC S.R.L.\nVia Prova 1\nP.IVA $ABC_VAT\nFattura n. 1 del 03/03/2031"
        val id = repo.saveDocument(
            doc(ABC, "1", emptyList(), 1000, MARCH), StoredFile("documents/k1.jpg", "image/jpeg", 1, "sha-k1"), text, null, SellerLearning(text, null, null),
        )
        val seller = repo.sellerStats().first().single()
        assertEquals(ABC_VAT, seller.vatNumber)
        repo.rememberCategory(seller.vatNumber, seller.name, SupplierCategory.UTILITIES)
        assertEquals(mapOf(seller.id to SupplierCategory.UTILITIES), repo.sellerCategories().first())

        // Its only document goes, and with it the supplier's row: the memory stays.
        repo.deleteDocument(id)
        assertTrue(repo.sellerStats().first().isEmpty())
        assertEquals(CategoryMatch(SupplierCategory.UTILITIES, known = true), repo.categoryOf(ABC_VAT, "A name read differently"))

        // The next invoice printing that VAT number has it, whatever the name was read as.
        assertEquals(SupplierCategory.UTILITIES, preparer(repo, learning).supplierCategory("A8C 5RL\nP.IVA $ABC_VAT\nFattura n. 2", "A8C 5RL"))
    }

    @Test fun aCategoryFoundByTheNameAloneIsOnlyASuggestion() = withSettings {
        val file = memoryFile()
        val learning = LearningStore(file)
        val repo = ReceiptRepository(db, FileStore(context), learning)
        // Chosen for a supplier whose VAT number was not read.
        repo.rememberCategory(null, VERDE, SupplierCategory.FOOD)
        // Case and legal form do not matter, but it is not known: shown as "Chosen last time", never applied.
        assertEquals(CategoryMatch(SupplierCategory.FOOD, known = false), repo.categoryOf(null, "Verde Fresco SpA"))
        assertEquals(CategoryMatch(SupplierCategory.FOOD, known = false), repo.categoryOf(VERDE_VAT, "VERDE FRESCO S.P.A."))
        val preparer = preparer(repo, learning)
        assertNull(preparer.supplierCategory("VERDE FRESCO S.P.A.\nFattura n. 7", VERDE))
        assertNull(preparer.supplierCategory("VERDE FRESCO S.P.A.\nP.IVA $VERDE_VAT\nFattura n. 8", VERDE))
        // No readable name is stored.
        assertFalse(file.readText().contains("verde", ignoreCase = true))
        // A saved supplier of that name with a VAT number goes by the number only (Sellers, lists, labels): not sorted.
        val text = "VERDE FRESCO S.P.A.\nVia Prova 2\nP.IVA $VERDE_VAT\nFattura n. 9 del 03/03/2031"
        repo.saveDocument(
            doc(VERDE, "9", emptyList(), 1000, MARCH), StoredFile("documents/k9.jpg", "image/jpeg", 1, "sha-k9"), text, null, SellerLearning(text, null, null),
        )
        assertEquals(VERDE_VAT, repo.sellerStats().first().single().vatNumber)
        assertEquals(emptyMap<Long, SupplierCategory>(), repo.sellerCategories().first())
        // One without a VAT number goes by its name, as chosen for it on Sellers.
        repo.saveDocument(doc(ABC, "10", emptyList(), 1000, MARCH), StoredFile("documents/k10.jpg", "image/jpeg", 1, "sha-k10"), "ABC S.R.L.\nFattura n. 10", null)
        repo.rememberCategory(null, ABC, SupplierCategory.DRINKS)
        val abc = repo.sellerStats().first().single { it.name == ABC }
        assertEquals(mapOf(abc.id to SupplierCategory.DRINKS), repo.sellerCategories().first())
    }

    @Test fun forgetRemovesBothKeys() = withSettings {
        val file = memoryFile()
        val learning = LearningStore(file)
        val repo = ReceiptRepository(db, FileStore(context), learning)
        repo.rememberCategory(ABC_VAT, ABC, SupplierCategory.FUEL)
        assertEquals(SupplierCategories.memoryKeys(ABC_VAT, ABC).toSet(), learning.supplierCategoryMemory.value.keys)

        repo.forgetCategory(ABC_VAT, ABC)
        assertNull(repo.categoryOf(ABC_VAT, null))
        assertNull(repo.categoryOf(null, ABC))
        assertTrue(learning.supplierCategoryMemory.value.isEmpty())
        // Gone from the file too.
        assertTrue(LearningStore(file).supplierCategoryMemory.value.isEmpty())
    }

    @Test fun theLearningFileStillReadsAsOlderVersionsReadIt() = withSettings {
        val file = memoryFile()
        // A file as older versions wrote it: one supplier's entry, words of product names and their categories.
        file.writeText(
            JSONObject()
                .put("vat:$ABC_VAT", JSONObject().put("rules", JSONArray()).put("examples", JSONArray().put(JSONObject().put("row", "MOZZARELLA 2,00 9,00 18,00").put("description", "Mozzarella"))))
                .put("#categories", JSONObject().put("mozzarella", "dairy_eggs"))
                .put("#names", JSONObject().put("fdl", "fior di latte"))
                .toString(),
        )
        val before = olderReader(JSONObject(file.readText()))

        LearningStore(file).rememberSupplierCategory(SupplierCategories.memoryKeys(ABC_VAT, ABC), SupplierCategory.DRINKS)

        val root = JSONObject(file.readText())
        // What an older version reads is unchanged: the new key is a list, which it does not take for a supplier.
        assertEquals(before, olderReader(root))
        assertTrue(root.get("supplier_categories") is JSONArray)
        // And this version reads both.
        val again = LearningStore(file)
        assertEquals(Triple(1, 0, 1), again.stats())
        assertEquals(SupplierCategory.DRINKS, again.supplierCategory("vat:$ABC_VAT")?.category)
    }

    /** learning.json as LearningStore read it before supplier categories: every object at the top is a supplier's entry. */
    private fun olderReader(root: JSONObject): List<Any> {
        val entries = root.keys().asSequence().filter { it != "#categories" && it != "#names" }.mapNotNull { k ->
            val o = root.optJSONObject(k) ?: return@mapNotNull null
            val rows = o.optJSONArray("examples")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("row") } }.orEmpty()
            Triple(k, o.optJSONArray("rules")?.length() ?: 0, rows)
        }.sortedBy { it.first }.toList()
        fun map(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.optString(it) }.orEmpty()
        return listOf(entries, map(root.optJSONObject("#categories")), map(root.optJSONObject("#names")))
    }

    @Test fun justThisInvoiceLeavesTheMemoryAlone() = withSettings {
        val file = memoryFile()
        val learning = LearningStore(file)
        val repo = ReceiptRepository(db, FileStore(context), learning)
        val preparer = preparer(repo, learning)
        val text = "ABC S.R.L.\nP.IVA $ABC_VAT\nFattura n. 3 del 05/03/2031"

        val one = preparer.chooseCategory(prepared(ABC, null), text, SupplierCategory.EQUIPMENT, forSupplier = false)
        assertEquals(SupplierCategory.EQUIPMENT, one.supplierCategory)
        assertTrue(learning.supplierCategoryMemory.value.isEmpty())
        assertNull(repo.categoryOf(ABC_VAT, ABC))
        assertFalse(file.exists())

        // For all of the supplier's invoices: remembered by VAT number.
        preparer.chooseCategory(prepared(ABC, null), text, SupplierCategory.EQUIPMENT, forSupplier = true)
        assertEquals(CategoryMatch(SupplierCategory.EQUIPMENT, known = true), repo.categoryOf(ABC_VAT, null))
    }

    /** Everything the app computes from the documents, for comparing two phones field by field. */
    private data class Figures(
        val months: List<com.kitchenreceipts.core.FoodCost.Month>,
        val prices: Map<Long, com.kitchenreceipts.core.IngredientPrice>,
        val inventory: List<com.kitchenreceipts.core.InventoryReport>,
        val boss: List<com.kitchenreceipts.core.BossReport>,
        val sellers: List<List<Any?>>,
        val products: List<List<Any?>>,
        val aliases: List<List<Any?>>,
        val linked: List<List<Any?>>,
        val foodAlerts: List<com.kitchenreceipts.core.PriceChange>,
    )

    /** One phone: its own database, and the memory of supplier categories when [learning] is set. */
    private inner class Phone(val db: AppDatabase, val learning: LearningStore?) {
        val repo = ReceiptRepository(db, FileStore(context), learning)
        val preparer = preparer(repo, learning)
        var verdeAlerts: List<com.kitchenreceipts.core.PriceChange> = emptyList()

        private fun line(desc: String, productId: Long?, qty: String, cents: Long, newName: String? = null) =
            ValidLineItem(desc, productId, BigDecimal(qty), "pz", null, cents, null, null, null, newProductName = newName)

        private fun draftLine(key: Long, desc: String, qty: String, total: String) =
            LineItemDraft(key, DraftField(desc), quantity = DraftField(qty), unit = DraftField("pz"), lineTotal = DraftField(total))

        private fun ValidLineItem.from(d: LineItemDraft) = copy(productId = d.productId, newProductName = d.newProductName)

        /** The same invented documents on every phone: a food supplier, and an electricity supplier. */
        suspend fun fill(): Figures {
            val abcText = "ABC S.R.L.\nP.IVA $ABC_VAT\nFattura n. A1 del 03/03/2031"
            val verdeText = "VERDE FRESCO S.P.A.\nP.IVA $VERDE_VAT\nFattura n. E1 del 10/03/2031"
            repo.saveDocument(
                doc(ABC, "A1", listOf(
                    line("MOZZARELLA FIOR DI LATTE", null, "2", 1800, "Mozzarella fior di latte"),
                    line("POMODORO PELATO", null, "6", 900, "Pomodoro pelato"),
                    line("BASILICO FRESCO", null, "1", 150),
                ), 2850, MARCH),
                StoredFile("documents/f1.pdf", "application/pdf", 1, "sha-f1"), abcText, null, SellerLearning(abcText, null, null),
            )
            repo.saveDocument(
                doc(VERDE, "E1", listOf(
                    line("QUOTA FISSA", null, "1", 2000, "Quota fissa"),
                    line("ONERI DI SISTEMA", null, "1", 1500),
                ), 3500, MARCH.plusDays(7)),
                StoredFile("documents/e1.pdf", "application/pdf", 1, "sha-e1"), verdeText, null, SellerLearning(verdeText, null, null),
            )
            // April: the same lines again, linked by the app, at new prices.
            val verdeLinks = repo.autoAssign(VERDE, listOf(draftLine(1, "QUOTA FISSA", "1", "22,00"), draftLine(2, "CONSUMO DEL MESE", "1", "30,00")), createNew = true)
            val verde2Text = verdeText.replace("E1 del 10/03", "E2 del 10/04")
            val verde2 = repo.saveDocument(
                doc(VERDE, "E2", listOf(line("QUOTA FISSA", null, "1", 2200).from(verdeLinks[0]), line("CONSUMO DEL MESE", null, "1", 3000).from(verdeLinks[1])), 5200, APRIL),
                StoredFile("documents/e2.pdf", "application/pdf", 1, "sha-e2"), verde2Text, null, SellerLearning(verde2Text, null, null),
            )
            val abcLinks = repo.autoAssign(ABC, listOf(draftLine(1, "MOZZARELLA FIOR DI LATTE", "2", "20,00")), createNew = true)
            val abc2Text = abcText.replace("A1 del 03/03", "A2 del 12/04")
            val abc2 = repo.saveDocument(
                doc(ABC, "A2", listOf(line("MOZZARELLA FIOR DI LATTE", null, "2", 2000).from(abcLinks[0])), 2000, APRIL.plusDays(2)),
                StoredFile("documents/f2.pdf", "application/pdf", 1, "sha-f2"), abc2Text, null, SellerLearning(abc2Text, null, null),
            )
            // The price notifications the background reader would send for the April documents.
            verdeAlerts = preparer.priceAlerts(verde2, prepared(VERDE, preparer.supplierCategory(verde2Text, VERDE)))
            val foodAlerts = preparer.priceAlerts(abc2, prepared(ABC, preparer.supplierCategory(abc2Text, ABC)))

            val food = FoodRepository(db)
            val periods = listOf(MARCH, APRIL).map { Period.of(it, PeriodKind.MONTH) }
            val products = repo.productsOnce()
            return Figures(
                months = food.months(3, today = APRIL.plusDays(20)).first(),
                prices = food.prices().first(),
                inventory = periods.map { repo.inventory(it).first() },
                boss = periods.map { repo.bossReport(it) },
                sellers = repo.sellerStats().first().map { listOf(it.id, it.name, it.vatNumber, it.documentCount, it.totalCents, it.lastDate) },
                products = products.map { listOf(it.id, it.name, it.normalizedName, it.category, it.brand, it.familyId) },
                aliases = products.flatMap { p -> repo.aliasesForProduct(p.id).first().map { listOf(p.id, it.id, it.sellerName, it.aliasKey) } },
                linked = (verdeLinks + abcLinks).map { listOf(it.productId, it.newProductName, it.productSource) },
                foodAlerts = foodAlerts,
            )
        }
    }

    @Test fun aNonFoodSupplierChangesNoFigureOnlyTheLists() = withSettings {
        val sortedDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val plain = Phone(db, learning = null)
            val sorted = Phone(sortedDb, LearningStore(memoryFile()))
            // On the second phone the electricity supplier was sorted before any of its documents was saved.
            sorted.repo.rememberCategory(VERDE_VAT, VERDE, SupplierCategory.UTILITIES)

            val a = plain.fill()
            val b = sorted.fill()
            // Food cost, prices, inventory, the boss report, supplier totals, products, aliases and links: all the same.
            assertEquals(a, b)
            assertTrue(a.foodAlerts.isNotEmpty())
            assertTrue(a.products.size >= 3)

            val verdeId = sorted.repo.sellerStats().first().single { it.name == VERDE }.id
            val quota = sorted.repo.productsOnce().single { it.name == "Quota fissa" }.id
            val mozzarella = sorted.repo.productsOnce().single { it.name == "Mozzarella fior di latte" }.id
            // Without a category: everything is listed as before.
            assertEquals(setOf("BASILICO FRESCO", "ONERI DI SISTEMA"), plain.repo.unassignedGroups().first().map { it.description }.toSet())
            assertTrue(plain.repo.notFoodProductIds().first().isEmpty())
            assertTrue(plain.verdeAlerts.isNotEmpty())
            // With it: the bill's lines are not asked about, its products are listed apart, no price notification, and
            // the supplier is not in the usual-order list.
            assertEquals(setOf("BASILICO FRESCO"), sorted.repo.unassignedGroups().first().map { it.description }.toSet())
            val notFood = sorted.repo.notFoodProductIds().first()
            assertTrue(quota in notFood)
            assertFalse(mozzarella in notFood)
            assertTrue(sorted.verdeAlerts.isEmpty())
            assertFalse(sorted.repo.goodsOnly(FoodRepository(sortedDb).suppliers()) { it.id }.first().any { it.id == verdeId })
            assertTrue(plain.repo.goodsOnly(FoodRepository(db).suppliers()) { it.id }.first().any { it.name == VERDE })

            // Moved to Food: its lines come back to the to-link list, and its products to the main list.
            sorted.repo.rememberCategory(VERDE_VAT, VERDE, SupplierCategory.FOOD)
            assertEquals(setOf("BASILICO FRESCO", "ONERI DI SISTEMA"), sorted.repo.unassignedGroups().first().map { it.description }.toSet())
            assertTrue(sorted.repo.notFoodProductIds().first().isEmpty())
        } finally {
            sortedDb.close()
        }
    }

    private companion object {
        const val ABC = "ABC S.r.l."
        const val ABC_VAT = "01234567897"
        const val VERDE = "VERDE FRESCO S.p.A."
        const val VERDE_VAT = "09876543217"
        val MARCH: LocalDate = LocalDate.of(2031, 3, 3)
        val APRIL: LocalDate = LocalDate.of(2031, 4, 10)
    }
}
