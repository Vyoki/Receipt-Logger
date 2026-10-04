package com.kitchenreceipts.app.reports

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.core.OfficeExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * The office copy: one web page with everything recorded, encrypted with the operator's password (see core
 * OfficeExport). Opened on a computer by double-clicking it; it works offline and sends nothing anywhere.
 * Nothing leaves the phone until the operator picks where to send the file.
 */
object OfficeCopy {

    data class Ready(val file: File, val documents: Int, val subject: String, val message: String)

    suspend fun build(context: Context, repo: ReceiptRepository, business: String, password: CharArray): Ready {
        val snapshot = repo.officeSnapshot(business)
        val page = withContext(Dispatchers.Default) {
            val json = OfficeExport.json(snapshot)
            val envelope = OfficeExport.encrypt(json, password)
            val template = context.assets.open(TEMPLATE).use { it.readBytes().toString(Charsets.UTF_8) }
            OfficeExport.page(template, envelope)
        }
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".html") }?.forEach { it.delete() } // only the latest copy is kept
        val file = File(dir, context.getString(R.string.office_file_prefix) + "_" + LocalDate.now() + ".html")
        withContext(Dispatchers.IO) { file.writeText(page) }
        val subject = context.getString(R.string.office_subject, business.ifBlank { context.getString(R.string.app_name) }, LocalDate.now().toString())
        return Ready(file, snapshot.documents.size, subject, context.getString(R.string.office_message))
    }

    fun shareIntent(context: Context, r: Ready): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/html")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, r.subject)
            .putExtra(Intent.EXTRA_TEXT, r.message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    const val TEMPLATE = "office-viewer.html"
}
