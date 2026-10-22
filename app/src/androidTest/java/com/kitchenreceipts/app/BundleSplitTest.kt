package com.kitchenreceipts.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.SplitFailure
import com.kitchenreceipts.app.files.SplitResult
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ocr.PdfText
import com.kitchenreceipts.core.TextLayer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * A file of many invoices is cut into one PDF per invoice: each part holds its own pages, comes out as the same bytes
 * every time (so the same invoice cut twice is a duplicate by its file hash), and a failure keeps nothing.
 */
@RunWith(AndroidJUnit4::class)
class BundleSplitTest {

    private val app = ApplicationProvider.getApplicationContext<KitchenReceiptsApp>().also { it.container } // initialises the PDF reader
    private val files = FileStore(app)

    private fun documents(): Set<String> = File(app.filesDir, FileStore.DOCUMENTS_DIR).list().orEmpty().toSet()

    private suspend fun stored(name: String, pages: Int): StoredFile {
        val src = TestPdfs.write(File(app.cacheDir, name), pages)
        return files.importUri(Uri.fromFile(src)).files.single().also { src.delete() }
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun firstPageText(part: StoredFile): String {
        val glyphs = PdfText.glyphs(files.file(part.relativePath), 0).orEmpty()
        return TextLayer.lines(PdfText.scaled(glyphs, 2400, 3394)).joinToString("\n") { it.text }.replace(" ", "")
    }

    @Test fun sixPagesCutInThreeComeOutTheSameEveryTime() = runBlocking<Unit> {
        val bundle = stored("split-6.pdf", 6)
        val ranges = listOf(0..1, 2..4, 5..5)
        val first = files.splitPdf(bundle, ranges)
        assertTrue("$first", first is SplitResult.Done)
        val a = (first as SplitResult.Done).parts
        assertEquals(listOf(2, 3, 1), a.map { it.pageCount })
        a.forEachIndexed { k, part ->
            assertEquals(FileStore.MIME_PDF, part.mimeType)
            PDDocument.load(files.file(part.relativePath)).use { assertEquals(listOf(2, 3, 1)[k], it.numberOfPages) }
            // The stored hash is the file's.
            assertEquals(sha(files.file(part.relativePath)), part.sha256)
        }
        // Each part starts with its own first page.
        assertTrue(firstPageText(a[0]).contains("PAGINA1"))
        assertTrue(firstPageText(a[1]).contains("PAGINA3"))
        assertTrue(firstPageText(a[2]).contains("PAGINA6"))

        // Cut again (a second import of the same file): new files, the same bytes.
        val b = (files.splitPdf(bundle, ranges) as SplitResult.Done).parts
        assertEquals(a.map { it.sha256 }, b.map { it.sha256 })
        assertNotEquals(a.map { it.relativePath }, b.map { it.relativePath })
        // Different ranges give different files.
        assertEquals(3, a.map { it.sha256 }.distinct().size)

        (a + b).forEach { files.delete(it.relativePath) }
        files.delete(bundle.relativePath)
    }

    @Test fun aFailureKeepsNoPart() = runBlocking<Unit> {
        val bundle = stored("split-fail.pdf", 6)
        val before = documents()
        // The second range runs past the end: the first part, already written, is deleted too.
        val r = files.splitPdf(bundle, listOf(0..1, 4..9))
        assertTrue("$r", r is SplitResult.Failed)
        assertEquals(SplitFailure.OTHER, (r as SplitResult.Failed).reason)
        assertEquals(before, documents())

        // A file that is no PDF at all.
        val broken = File(app.filesDir, "${FileStore.DOCUMENTS_DIR}/split-broken.pdf").apply { writeText("%PDF-1.4\nnot really a PDF") }
        val brokenFile = StoredFile("${FileStore.DOCUMENTS_DIR}/${broken.name}", FileStore.MIME_PDF, 6, "sha-broken")
        val before2 = documents()
        val r2 = files.splitPdf(brokenFile, listOf(0..1))
        assertEquals(SplitResult.Failed(SplitFailure.DAMAGED), r2)
        assertEquals(before2, documents())

        broken.delete()
        files.delete(bundle.relativePath)
    }
}
