package com.kitchenreceipts.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.core.ParseOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo benchmark: reads every photo pushed to /data/local/tmp/photo-bench/ (adb push, never part of this repository)
 * exactly as the phone reads a photo taken in the app (stored, flattened, both OCR passes, no AI), and writes each
 * reading report ("share the reading" format, with the raw lines) to files/photo-bench/<photo>.txt for adb pull.
 * Skipped when no photo was pushed, so CI never reads one. Optional instrumentation argument ownVat: the restaurant's
 * VAT number.
 */
@RunWith(AndroidJUnit4::class)
class PhotoBenchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shell = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun run(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Copies a file the shell can read (adb push) into the app's storage. */
    private fun fromShell(src: String, dst: File) {
        ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand("cat $src")).use { input -> dst.outputStream().use { input.copyTo(it) } }
    }

    @Test fun readsThePushedPhotos() = runBlocking<Unit> {
        val names = run("ls $SOURCE").lines().map { it.trim() }.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
        assumeTrue("no photos pushed to $SOURCE", names.isNotEmpty())
        val ownVat = InstrumentationRegistry.getArguments().getString("ownVat")
        val out = File(context.filesDir, "photo-bench").apply { deleteRecursively(); mkdirs() }
        val files = FileStore(context)
        val renderer = PageRenderer(files)
        val processor = ImportProcessor(renderer)
        val tmp = File(context.cacheDir, "photo-bench").apply { mkdirs() }
        for (name in names.sorted()) {
            val photo = File(tmp, name)
            fromShell("$SOURCE/$name", photo)
            // As a photo taken in the app is stored (see CaptureScreen), then read.
            val stored = files.storePhotos(listOf(photo)) { renderer.decodeImage(it, 3000) }
            val reading = processor.process(stored, MlKitOcrEngine(), options = ParseOptions(ownVatNumber = ownVat))
            File(out, name.substringBeforeLast('.') + ".txt").writeText(reading.debugReport())
            photo.delete()
        }
    }

    private companion object {
        const val SOURCE = "/data/local/tmp/photo-bench"
    }
}
