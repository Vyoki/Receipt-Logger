package com.kitchenreceipts.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kitchenreceipts.app.ai.AiModelStore
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.app.ai.NativeAi
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.ParseOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The on-device AI reader, end to end on the emulator: native library, CPU backend loading from the app's
 * library folder, model + vision file loading, image input, grammar-constrained answer, decoding.
 * CI pushes a tiny vision model (SmolVLM 256M) to /data/local/tmp; the test skips if it is not there.
 * (The tiny model reads badly; what is checked here is the machinery, not the reading quality.)
 */
@RunWith(AndroidJUnit4::class)
class AiNativeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Copies a file the shell can read (adb push) into the app's storage. */
    private fun fromShell(src: String, dst: File): Boolean {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $src")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dst.outputStream().use { input.copyTo(it) } }
        return dst.length() > 1000
    }

    @Test fun nativeLibraryLoads() {
        assertTrue("libreceipt_ai.so did not load", NativeAi.available)
    }

    @Test fun readsAPageWithATinyModel() = runBlocking {
        val store = AiModelStore(context)
        val ok = fromShell("/data/local/tmp/ai-test/model.gguf", store.modelFile) &&
            fromShell("/data/local/tmp/ai-test/mmproj.gguf", store.mmprojFile)
        assumeTrue("no test model pushed", ok)
        try {
            val reader = AiPageReader.open(store, context.applicationInfo.nativeLibraryDir)
            val page = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
            Canvas(page).apply {
                drawColor(Color.WHITE)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.BLACK }
                drawText("ESEMPIO S.R.L.   FATTURA N. 12   23/09/2026", 20f, 50f, p)
                drawText("MOZZARELLA   KG 2,000   8,90   17,80", 20f, 130f, p)
                drawText("TOTALE DOCUMENTO 19,58", 20f, 220f, p)
            }
            var progressCalls = 0
            val r = reader.read(page, "ESEMPIO S.R.L.\nMOZZARELLA KG 2,000 8,90 17,80\nTOTALE DOCUMENTO 19,58", ParseOptions()) { _, _ -> progressCalls++ }
            reader.close()
            File(context.filesDir, "ai-e2e.txt").writeText("stats=${r.stats} millis=${r.millis} error=${r.error}\n${r.raw}\n")
            assertEquals(null, r.error)
            assertTrue(progressCalls > 0)
            // Constrained by the grammar: always the expected JSON shape, whatever the model.
            assertNotNull("answer is not the expected JSON: ${r.raw}", AiReader.decode(r.raw))
        } finally {
            store.remove()
        }
    }
}
