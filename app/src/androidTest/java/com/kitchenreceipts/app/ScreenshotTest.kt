package com.kitchenreceipts.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Screenshots of the main screens with invented data, so the look can be checked from CI (they are published to
 * the ci-screens branch). Not a pass/fail test of the design: it fails only if a screen cannot be opened.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private val app: KitchenReceiptsApp = ApplicationProvider.getApplicationContext()
    private val out = File(app.filesDir, "screens").apply { deleteRecursively(); mkdirs() }

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
            app.container.pendingRoute.value = "edit/$docId"
            shot("6-edit")
        }
    }
}
