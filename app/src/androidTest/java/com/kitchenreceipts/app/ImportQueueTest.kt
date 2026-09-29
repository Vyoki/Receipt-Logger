package com.kitchenreceipts.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.ImportQueue
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.app.jobs.ReadingNotifier
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Background reading: a queued document is read without any screen open, and after a restart the finished
 * reading comes back (rebuilt from what was stored, without reading again).
 */
@RunWith(AndroidJUnit4::class)
class ImportQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun readsInTheBackgroundAndSurvivesARestart() = runBlocking {
        File(context.filesDir, "jobs").deleteRecursively()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val files = FileStore(context)
        val renderer = PageRenderer(files)
        val settings = AppSettings(context)
        val log = AppLog(context, settings)
        val repo = ReceiptRepository(db, files)
        fun queue(scope: CoroutineScope) = ImportQueue(
            context, scope, ImportProcessor(renderer), MlKitOcrEngine(), settings, { null },
            DraftPreparer(repo, settings, log), files, log, ReadingNotifier(context),
        )

        // A one-page "photo" with a little text.
        val page = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            val p = android.graphics.Paint().apply { textSize = 36f; color = Color.BLACK; isAntiAlias = true }
            drawText("FORNITORE ESEMPIO S.R.L.", 40f, 80f, p)
            drawText("PATATE KG 10,000 0,90 9,00", 40f, 250f, p)
            drawText("TOTALE 9,00", 40f, 400f, p)
        }
        val photo = File(context.cacheDir, "queue-test.jpg")
        photo.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val stored = files.storePhotos(listOf(photo)) { BitmapFactory.decodeFile(it.absolutePath) }

        val scope1 = CoroutineScope(SupervisorJob())
        val q1 = queue(scope1)
        val job = q1.enqueue(stored, "test")
        val done = withTimeout(120_000) {
            q1.jobs.first { list -> list.any { it.id == job.id && it.status in setOf(JobStatus.READY, JobStatus.SAVED, JobStatus.FAILED) } }
        }.first { it.id == job.id }
        assertTrue("status ${done.status} ${done.error}", done.status == JobStatus.READY || done.status == JobStatus.SAVED)
        scope1.cancel()

        if (done.status == JobStatus.READY) {
            // "Restart": a new queue on the same storage.
            val scope2 = CoroutineScope(SupervisorJob())
            val q2 = queue(scope2)
            q2.restore()
            val restored = withTimeout(30_000) { q2.jobs.first { l -> l.any { it.id == job.id && it.pending != null } } }.first { it.id == job.id }
            assertEquals(JobStatus.READY, restored.status)
            assertNotNull(restored.pending)
            assertEquals(done.pending!!.ocrText, restored.pending!!.ocrText)
            q2.discard(job.id)
            assertTrue(q2.jobs.value.none { it.id == job.id })
            scope2.cancel()
        }
        db.close()
    }
}
