package com.kitchenreceipts.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kitchenreceipts.app.data.SellerLearning
import com.kitchenreceipts.app.files.EInvoiceEntry
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Screenshots of the main screens with invented data, so the look can be checked from CI (they are published to
 * the ci-screens branch). Not a pass/fail test of the design: it fails only if a screen cannot be opened.
 */
@RunWith(AndroidJUnit4::class)
// mainScreens first: the later tests add data of their own, which would change its screens.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTest {

    private val app: KitchenReceiptsApp = ApplicationProvider.getApplicationContext()
    private val out = File(app.filesDir, "screens").apply { mkdirs() }

    companion object {
        /** Emptied once per run (not per test), so every test's screenshots are kept. */
        @BeforeClass @JvmStatic fun clearScreens() {
            File(ApplicationProvider.getApplicationContext<KitchenReceiptsApp>().filesDir, "screens").deleteRecursively()
        }

        // Invented suppliers for the long lists: two per letter of the radio alphabet.
        private val WORDS = listOf(
            "Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett", "Kilo", "Lima", "Mike",
            "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray", "Yankee", "Zulu",
        )
        private const val LONG_SELLER = "ABC S.r.l."
        // An invented electricity supplier (valid check digit).
        private const val UTILITY_SELLER = "VERDE FRESCO S.p.A."
        private const val UTILITY_VAT = "09876543217"
    }

    private fun shot(name: String) {
        Thread.sleep(1500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seed() = runBlocking {
        val repo = app.container.repository
        if (repo.productsOnce().isNotEmpty()) return@runBlocking
        fun item(d: String, q: String, u: String, p: String, cents: Long, size: String? = null) =
            ValidLineItem(d, null, BigDecimal(q), u, BigDecimal(p), cents, BigDecimal(10), null, null, newProductName = d, packSize = size)
        val doc = ValidDocument(
            "Alfa Ingrosso S.r.l.", LocalDate.now(), "12A/345", "EUR", 4840, 484, 5324, VatBasis.EXCLUSIVE,
            listOf(
                item("PASSATA DI POMODORO 700G", "12", "pz", "1.05", 1260, "700 g"),
                item("MOZZARELLA FIOR DI LATTE", "2.5", "kg", "8.90", 2225),
                item("OLIO EXTRAVERGINE LT 1", "2", "pz", "6.775", 1355, "1 l"),
            ),
        )
        val f = File(app.filesDir, "documents/screens.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
        repo.saveDocument(doc, StoredFile("documents/screens.jpg", "image/jpeg", 1, "screens-${f.length()}"), null, null)
        // A delivery note with a lot and a use-by date, and the invoice that names it (one more kg charged).
        fun lotItem(q: String, cents: Long) = ValidLineItem(
            "ZUCCHINE", null, BigDecimal(q), "kg", null, cents, BigDecimal(4), "L-4471", LocalDate.now().plusDays(3), newProductName = "ZUCCHINE",
        )
        File(app.filesDir, "documents/screens-ddt.jpg").writeBytes(ByteArray(11))
        repo.saveDocument(
            ValidDocument("Beta Ortofrutta S.r.l.", LocalDate.now().minusDays(2), "77", "EUR", 1600, null, 1600, VatBasis.EXCLUSIVE, listOf(lotItem("8", 1600))),
            StoredFile("documents/screens-ddt.jpg", "image/jpeg", 1, "screens-ddt"), "Beta Ortofrutta S.r.l.\nDOCUMENTO DI TRASPORTO N. 77", null,
        )
        File(app.filesDir, "documents/screens-inv.jpg").writeBytes(ByteArray(12))
        repo.saveDocument(
            ValidDocument("Beta Ortofrutta S.r.l.", LocalDate.now(), "301", "EUR", 1800, 72, 1872, VatBasis.EXCLUSIVE, listOf(lotItem("9", 1800).copy(lotNumber = null, expiryDate = null))),
            StoredFile("documents/screens-inv.jpg", "image/jpeg", 1, "screens-inv"),
            "Beta Ortofrutta S.r.l.\nFATTURA N. 301\nRif. DDT n. 77 del " + com.kitchenreceipts.core.ItalianDates.format(LocalDate.now().minusDays(2)), null,
        )
        // An agreed price below what was paid, and a dish.
        val byName = repo.productsOnce().associateBy { it.name.uppercase() }
        val mozz = byName.entries.first { it.key.contains("MOZZARELLA") }.value.id
        val passata = byName.entries.first { it.key.contains("PASSATA") }.value.id
        val olio = byName.entries.first { it.key.contains("OLIO") }.value.id
        app.container.checks.setAgreedPrice(mozz, null, "kg", BigDecimal("8.50"), VatBasis.EXCLUSIVE)
        app.container.food.save(
            com.kitchenreceipts.app.data.RecipeDraft(
                com.kitchenreceipts.app.data.RecipeEntity(0, "Spaghetti al pomodoro", "primi", 1200, true, BigDecimal.TEN, BigDecimal.ONE, 0, 0),
                listOf(
                    com.kitchenreceipts.app.data.RecipeItemEntity(0, 0, 0, null, "Spaghetti", BigDecimal("100"), "g", BigDecimal.ZERO, null, null),
                    com.kitchenreceipts.app.data.RecipeItemEntity(0, 0, 1, passata, "Passata", BigDecimal("120"), "g", BigDecimal.ZERO, null, null),
                    com.kitchenreceipts.app.data.RecipeItemEntity(0, 0, 2, olio, "Olio", BigDecimal("10"), "ml", BigDecimal("5"), null, null),
                    com.kitchenreceipts.app.data.RecipeItemEntity(0, 0, 3, mozz, "Mozzarella", BigDecimal("60"), "g", BigDecimal.ZERO, null, null),
                ),
            ),
        )
    }

    @Test fun mainScreens() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("1-home")
            for ((name, route) in listOf("2-products" to "products", "3-inventory" to "inventory", "4-settings" to "settings", "5-documents" to "documents")) {
                app.container.pendingRoute.value = route
                shot(name)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { }
                app.container.pendingRoute.value = "home"
                Thread.sleep(500)
            }
            val docId = runBlocking { app.container.repository.recentDocuments(1).first().first().id }
            for ((name, route) in listOf(
                "7-checks" to "checks", "8-lots" to "lots", "9-food" to "food", "10-orders" to "orders", "11-backup" to "backup",
                "12-document" to "document/$docId",
            )) {
                app.container.pendingRoute.value = route
                shot(name)
                app.container.pendingRoute.value = "home"
                Thread.sleep(500)
            }
            val dish = runBlocking { app.container.food.recipeCosts(BigDecimal.ZERO).first().first().recipe.id }
            app.container.pendingRoute.value = "recipe/$dish"
            shot("13-dish")
            app.container.pendingRoute.value = "home"
            Thread.sleep(500)
            app.container.pendingRoute.value = "edit/$docId"
            shot("6-edit")
        }
    }

    /**
     * The import of a file of many invoices at its three steps (what the file holds, a new supplier, Read), its list
     * and home card while it is read, and the page bar of a long file's viewer, in English and in Italian. Invented e-invoices only: ABC S.r.l. and VERDE
     * FRESCO S.p.A., dates in 2031; the long file is TestPdfs' invented pages. Both imports are cancelled after.
     */
    @Test fun wizardScreens() {
        val store = app.container.bundleStore
        val settings = app.container.settings
        val language = settings.language
        val sha = "5c".repeat(32)
        fun entry(name: String, vat: String, n: Int, day: Int, cents: Long?, rows: List<String>, credit: Boolean = false) =
            EInvoiceEntry(null, vat, name, null, "$n/2031", LocalDate.of(2031, 3, day), cents, credit, rows)
        val food = listOf("PATATE KG 10 9,00", "MOZZARELLA FIOR DI LATTE KG 2,5 22,25", "PASSATA DI POMODORO 700G 12,60", "CIPOLLE KG 8 16,00")
        val power = listOf("Energia elettrica F1 kWh 420 98,70", "Oneri di sistema 31,20", "Quota fissa potenza impegnata 12,00", "Accisa kWh 420 9,24")
        val entries = (1..6).map { entry("ABC S.r.l.", "01234567897", 100 + it, it * 4, 18_450L + it * 1_235, food) } +
            entry("ABC S.r.l.", "01234567897", 107, 26, null, food) +
            entry("VERDE FRESCO S.p.A.", "09876543217", 31, 5, 18_814, power) +
            entry("VERDE FRESCO S.p.A.", "09876543217", 32, 28, 2_200, power, credit = true)
        // A long file the app only reads as one document: its plan opens the viewer on the file.
        val longSha = "6d".repeat(32)
        val longPdf = TestPdfs.write(File(app.filesDir, "documents/screens-long.pdf").apply { parentFile?.mkdirs() }, 40)
        runBlocking { store.notSplittable(StoredFile("documents/${longPdf.name}", "application/pdf", 40, longSha)) }
        try {
            for ((lang, tag) in listOf(AppSettings.Language.ENGLISH to "en", AppSettings.Language.ITALIAN to "it")) {
                settings.language = lang
                runBlocking {
                    store.cancel(sha)
                    store.planFromEInvoices(sha, entries)
                }
                ActivityScenario.launch(MainActivity::class.java).use {
                    app.container.pendingRoute.value = "bundle/$sha"
                    shot("30-bundle-found-$tag")
                    runBlocking { store.update(sha) { it.copy(step = BundleWizard.Step.SUPPLIERS) } }
                    shot("31-bundle-supplier-$tag")
                    runBlocking { store.update(sha) { BundleWizard.useSuggestions(it).copy(step = BundleWizard.Step.READ) } }
                    shot("32-bundle-read-$tag")
                    // Being read: the file's list by supplier (3 saved by the app, 2 to check, 1 saved by the operator,
                    // the rest still to read), then its one card on the home screen. No job of its own: nothing is read.
                    runBlocking {
                        store.update(sha) { p ->
                            var q = BundleWizard.confirm(p)
                            val order = BundleWizard.planOrder(q)
                            q = BundleWizard.queued(q, order.associateWith { "screens-job-$it" })
                            order.forEachIndexed { k, i ->
                                val o = when {
                                    k < 3 -> InvoiceOutcome.SAVED_BY_APP
                                    k < 5 -> InvoiceOutcome.READY
                                    k == 5 -> InvoiceOutcome.SAVED_BY_YOU
                                    else -> null
                                }
                                if (o != null) q = BundleWizard.record(q, "screens-job-$i", o, if (o == InvoiceOutcome.READY) null else 900L + i)
                            }
                            q
                        }
                    }
                    shot("34-bundle-reading-$tag")
                    app.container.pendingRoute.value = "home"
                    shot("35-home-bundle-$tag")
                    // Not cancelled by a plain cancel (some are saved): put away as a cancelled import.
                    runBlocking { store.update(sha) { BundleWizard.close(it, BundleStatus.CANCELLED) } }
                    Thread.sleep(500)
                    // The page bar of a long file (40 invented pages), at page 13.
                    app.container.pendingRoute.value = "viewer/-1?bundle=$longSha&page=12"
                    shot("33-viewer-pages-$tag")
                    app.container.pendingRoute.value = "home"
                    Thread.sleep(500)
                }
            }
        } finally {
            settings.language = language
            runBlocking {
                store.cancel(sha)
                // A cancel only stops an import with saved invoices: this invented one is put away all the same.
                store.update(sha) { BundleWizard.close(it, BundleStatus.CANCELLED) }
                store.cancel(longSha)
            }
        }
    }

    /** The fast scroller and the jump chips on long screens (invented data), in English and in Italian. */
    @Test fun scrollScreens() {
        seed()
        val docId = seedLong()
        val settings = app.container.settings
        val language = settings.language
        try {
            for ((lang, tag) in listOf(AppSettings.Language.ENGLISH to "en", AppSettings.Language.ITALIAN to "it")) {
                settings.language = lang
                // The one-time "Drag here" hint shows on the first long list of each run.
                settings.scrollHintShown = false
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var desc = ""
                    scenario.onActivity { desc = it.getString(R.string.scrollbar_desc) }
                    app.container.pendingRoute.value = "document/$docId"
                    shot("20-scroll-document-$tag")
                    dragThumb(desc) { shot("21-scroll-document-drag-$tag") }
                    shot("22-scroll-document-after-$tag")
                    for ((name, route) in listOf("23-scroll-sellers" to "sellers", "24-scroll-documents" to "documents")) {
                        app.container.pendingRoute.value = "home"
                        Thread.sleep(500)
                        app.container.pendingRoute.value = route
                        shot("$name-$tag")
                        dragThumb(desc) { shot("$name-drag-$tag") }
                    }
                }
            }
        } finally {
            settings.language = language
        }
    }

    /**
     * Suppliers sorted by what they sell (invented data), in English and in Italian: Sellers in sections with filter
     * chips, the folded "Not food" products, the labels on Documents, and the tiles. The categories are forgotten after.
     */
    @Test fun sortedScreens() {
        seed()
        seedLong()
        seedUtilityBill()
        val repo = app.container.repository
        // By VAT number for the bill; by name for the others, which have none.
        val sorted = listOf(
            Triple(UTILITY_VAT, UTILITY_SELLER, SupplierCategory.UTILITIES),
            Triple(null, "Alfa Ingrosso S.r.l.", SupplierCategory.FOOD),
            Triple(null, "Beta Ortofrutta S.r.l.", SupplierCategory.FOOD),
            Triple(null, "Alfa Esempio S.r.l.", SupplierCategory.DRINKS),
            Triple(null, "Bravo Prova S.r.l.", SupplierCategory.SUPPLIES),
            Triple(null, "Charlie Esempio S.r.l.", SupplierCategory.EQUIPMENT),
        )
        val settings = app.container.settings
        val language = settings.language
        try {
            runBlocking { sorted.forEach { (vat, name, c) -> repo.rememberCategory(vat, name, c) } }
            for ((lang, tag) in listOf(AppSettings.Language.ENGLISH to "en", AppSettings.Language.ITALIAN to "it")) {
                settings.language = lang
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var food = ""
                    scenario.onActivity { food = it.getString(R.string.supcat_food) }
                    for ((name, route) in listOf("25-sorted-sellers" to "sellers", "26-sorted-products" to "products", "27-sorted-documents" to "documents")) {
                        app.container.pendingRoute.value = "home"
                        Thread.sleep(500)
                        app.container.pendingRoute.value = route
                        // Products: down to the folded "Not food" section at the end.
                        if (route == "products") { Thread.sleep(1500); scrollToEnd() }
                        shot("$name-$tag")
                    }
                    // The tiles, opened from the first supplier's "Food" pill.
                    app.container.pendingRoute.value = "home"
                    Thread.sleep(500)
                    app.container.pendingRoute.value = "sellers"
                    Thread.sleep(1500)
                    if (clickText(food)) {
                        shot("28-sorted-tiles-$tag")
                        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
            }
        } finally {
            settings.language = language
            runBlocking { sorted.forEach { (vat, name, _) -> repo.forgetCategory(vat, name) } }
        }
    }

    /** An invented electricity bill with one product line: the supplier's product is "Not food" once it is sorted. */
    private fun seedUtilityBill() = runBlocking {
        val repo = app.container.repository
        if (repo.searchDocuments("", null, null, null).first().any { it.sellerName == UTILITY_SELLER }) return@runBlocking
        val text = "$UTILITY_SELLER\nP.IVA $UTILITY_VAT\nFATTURA N. E-12/2031"
        File(app.filesDir, "documents/screens-utility.jpg").writeBytes(ByteArray(13))
        val line = ValidLineItem("QUOTA FISSA", null, BigDecimal.ONE, "pz", BigDecimal("50.00"), 5000, BigDecimal(10), null, null, newProductName = "Quota fissa")
        repo.saveDocument(
            ValidDocument(UTILITY_SELLER, LocalDate.of(2031, 12, 18), "E-12/2031", "EUR", 5000, 500, 5500, VatBasis.EXCLUSIVE, listOf(line)),
            StoredFile("documents/screens-utility.jpg", "image/jpeg", 1, "screens-utility"), text, null, SellerLearning(text, null, null),
        )
    }

    /**
     * A 60-line invoice, and enough suppliers with one invoice each for the Sellers and Documents lists to be long.
     * All invented, dated 2031. Returns the long invoice.
     */
    private fun seedLong(): Long = runBlocking {
        val repo = app.container.repository
        repo.searchDocuments("", null, null, null).first().firstOrNull { it.sellerName == LONG_SELLER }?.let { return@runBlocking it.id }
        val last = LocalDate.of(2031, 12, 20)
        var n = 0
        for (word in WORDS) for (kind in listOf("Esempio", "Prova")) {
            n++
            val path = "documents/screens-long-$n.jpg"
            File(app.filesDir, path).writeBytes(ByteArray(20 + n))
            val line = ValidLineItem("Articolo di prova $n", null, BigDecimal.ONE, "pz", BigDecimal("10.00"), 1000, BigDecimal(10), null, null)
            repo.saveDocument(
                ValidDocument("$word $kind S.r.l.", last.minusDays(n * 6L), "P-$n", "EUR", 1000, 100, 1100, VatBasis.EXCLUSIVE, listOf(line)),
                StoredFile(path, "image/jpeg", 1, "screens-long-$n"), null, null,
            )
        }
        val lines = (1..60).map { i ->
            ValidLineItem("Articolo di prova %02d".format(i), null, BigDecimal(2), "pz", BigDecimal("1.50"), 300, BigDecimal(10), null, null)
        }
        File(app.filesDir, "documents/screens-long.jpg").writeBytes(ByteArray(19))
        repo.saveDocument(
            ValidDocument(LONG_SELLER, LocalDate.of(2031, 3, 14), "60/2031", "EUR", 18000, 1800, 19800, VatBasis.EXCLUSIVE, lines),
            StoredFile("documents/screens-long.jpg", "image/jpeg", 1, "screens-long"), "$LONG_SELLER\nFATTURA N. 60/2031", null,
        )
    }

    /**
     * Holds the fast scroller's thumb (found by its TalkBack description [desc]) dragged half-way down the screen
     * while [whileHeld] runs, so the bubble shows; then lets go. Does nothing if no thumb is on screen.
     */
    private fun dragThumb(desc: String, whileHeld: () -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val thumb = (1..10).firstNotNullOfOrNull {
            inst.uiAutomation.rootInActiveWindow?.let { findByDescription(it, desc) } ?: run { Thread.sleep(300); null }
        } ?: return
        val r = Rect().also { thumb.getBoundsInScreen(it) }
        val x = r.exactCenterX()
        val from = r.exactCenterY()
        val to = app.resources.displayMetrics.heightPixels * 0.55f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            inst.sendPointerSync(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (k in 1..12) {
            send(MotionEvent.ACTION_MOVE, from + (to - from) * k / 12)
            Thread.sleep(16)
        }
        try {
            whileHeld()
        } finally {
            send(MotionEvent.ACTION_UP, to)
        }
    }

    private fun findByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? =
        findNode(node) { it.contentDescription?.toString() == desc }

    private fun findNode(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (test(node)) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNode(it, test) }
            if (found != null) return found
        }
        return null
    }

    /** Taps the first thing on screen that reads exactly [text], as TalkBack would. False when none is shown. */
    private fun clickText(text: String): Boolean {
        val inst = InstrumentationRegistry.getInstrumentation()
        val node = (1..10).firstNotNullOfOrNull {
            inst.uiAutomation.rootInActiveWindow?.let { root -> findNode(root) { n -> n.isClickable && n.text?.toString() == text } }
                ?: run { Thread.sleep(300); null }
        } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Scrolls the screen's list to its end with TalkBack's scroll action (at most [pages] pages). */
    private fun scrollToEnd(pages: Int = 20) {
        val inst = InstrumentationRegistry.getInstrumentation()
        repeat(pages) {
            val list = inst.uiAutomation.rootInActiveWindow?.let { root -> findNode(root) { it.isScrollable } } ?: return
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            Thread.sleep(250)
        }
    }
}
