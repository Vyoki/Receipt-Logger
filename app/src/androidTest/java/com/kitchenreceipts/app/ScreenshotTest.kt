package com.kitchenreceipts.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.settings.AppSettings
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

    private fun findByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == desc) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findByDescription(it, desc) }
            if (found != null) return found
        }
        return null
    }
}
