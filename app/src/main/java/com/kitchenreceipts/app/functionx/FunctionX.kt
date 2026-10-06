package com.kitchenreceipts.app.functionx

// FUNCTION X — temporary: one text file with everything useful for improving the reading, to give to Claude.
// To remove it: delete this folder (functionx/), res/values*/function_x.xml, and the FunctionXSection(c) line in
// SettingsScreen.kt. Nothing else depends on it.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.OcrLineCodec
import com.kitchenreceipts.core.ReadingCheck
import com.kitchenreceipts.core.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FunctionX {

    /** Most recent saved documents included (the file stays small enough to send). */
    private const val MAX_DOCUMENTS = 150

    /** Builds the file on the phone; nothing is sent until the operator picks where to share it. */
    suspend fun build(c: AppContainer): Uri = withContext(Dispatchers.Default) {
        val ctx = c.appContext
        val out = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, "function-x-for-claude.txt")
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val s = c.settings
        val text = buildString {
            append("KITCHEN RECEIPTS — FUNCTION X EXPORT FOR CLAUDE\n")
            append("Purpose: real documents and corrections, to improve the reading. Contains document data: do not publish.\n")
            append("App ").append(pkg.versionName).append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Exported ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())).append('\n')
            append("Settings: language=").append(s.language).append(" ai=").append(s.aiMode)
                .append(" aiModel=").append(if (c.aiModels.installed) c.aiModels.installedName else "none")
                .append(" autoSave=").append(s.autoSave).append(" autoLink=").append(s.autoLinkProducts)
                .append(" network=").append(s.networkMode).append(" productLookup=").append(s.onlineProductLookup)
                .append(" ownVatSet=").append(s.ownVatNumber.isNotBlank()).append('\n')
            append("Knowledge pack ").append(c.knowledge.pack.value.version).append('\n')

            // Last re-check of the saved documents.
            section("READING CHECK")
            val r = c.readingChecker.last.value
            if (r == null) append("not run yet\n") else {
                append("version ").append(r.version).append(" previous ").append(r.previousVersion ?: "-")
                    .append(" documents ").append(r.docs.size).append(" right ").append(r.percentRight).append("%\n")
                r.docs.filter { it.now.right < it.now.of }.forEach { d ->
                    append("doc").append(d.documentId).append(" ").append(d.label).append(": ")
                        .append(d.now.right).append('/').append(d.now.of).append(" (before ").append(d.before ?: "-").append(")\n")
                    d.now.differences.forEach { append("   ").append(it).append('\n') }
                }
            }

            // Saved documents: what the operator saved, what this version reads, and the OCR reading itself.
            val docs = c.repository.readingCheckDocuments().sortedByDescending { it.id }
            val items = c.database.documentDao().allItemsOnce().groupBy { it.documentId }
            section("SAVED DOCUMENTS (" + minOf(docs.size, MAX_DOCUMENTS) + " of " + docs.size + ", newest first)")
            val options = s.parseOptions()
            fun money(v: Long?) = v?.let { ItalianNumbers.formatCents(it) } ?: "-"
            for (d in docs.take(MAX_DOCUMENTS)) {
                append("\n======== doc").append(d.id).append(" · ").append(d.label).append(" · ").append(d.mimeType).append(" ========\n")
                val k = d.confirmed
                append("SAVED: date=").append(k.date?.let(ItalianDates::format) ?: "-").append(" number=").append(k.number ?: "-")
                    .append(" taxable=").append(money(k.subtotalCents)).append(" vat=").append(money(k.vatCents))
                    .append(" total=").append(money(k.totalCents)).append('\n')
                items[d.id].orEmpty().sortedBy { it.position }.forEach { l ->
                    append("  line: ").append(l.originalDescription).append(" | qty=").append(l.quantity?.toPlainString() ?: "-")
                        .append(' ').append(l.unit ?: "").append(" | price=").append(l.unitPrice?.toPlainString() ?: "-")
                        .append(" | amount=").append(money(l.lineTotalCents)).append(" | vat%=").append(l.vatRate?.toPlainString() ?: "-")
                        .append(" | lot=").append(l.lotNumber ?: "-").append(" | exp=").append(l.expiryDate?.let(ItalianDates::format) ?: "-").append('\n')
                }
                val pages = c.readings.load(d.id)
                val parsed = runCatching {
                    when {
                        pages != null -> ReceiptParser.parsePages(pages, options)
                        !d.ocrText.isNullOrBlank() -> ReceiptParser.parse(d.ocrText, options)
                        else -> null
                    }
                }.getOrNull()
                if (parsed != null) {
                    val score = ReadingCheck.score(k, parsed)
                    append("THIS VERSION READS: ").append(score.right).append('/').append(score.of).append(" as saved\n")
                    score.differences.forEach { append("   ").append(it).append('\n') }
                }
                when {
                    pages != null -> append("OCR (with positions):\n").append(OcrLineCodec.encode(pages)).append('\n')
                    !d.ocrText.isNullOrBlank() -> append("OCR (text only):\n").append(d.ocrText).append('\n')
                    else -> append("OCR: none kept\n")
                }
            }

            // Documents corrected by hand (with their full reading reports).
            section("CORRECTED BY HAND")
            append(runCatching { readUri(ctx, c.problems.exportForSharing(null)) }.getOrElse { "unavailable: ${it.message}" }).append('\n')

            section("OPERATION LOG")
            append(runCatching { c.log.text() }.getOrElse { "unavailable" }).append('\n')
        }
        out.writeText(text)
        c.log.event("FUNCTION_X_EXPORT", "bytes" to text.length)
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
    }

    private fun StringBuilder.section(title: String) {
        append("\n\n################ ").append(title).append(" ################\n")
    }

    private fun readUri(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
}

@Composable
fun FunctionXSection(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.function_x_title)

    SectionTitle(stringResource(R.string.function_x_title))
    Text(stringResource(R.string.function_x_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
    if (building) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(stringResource(R.string.function_x_failed), style = MaterialTheme.typography.bodyMedium)
    BigButton(stringResource(R.string.function_x_button), Icons.Filled.Share, enabled = !building, onClick = {
        scope.launch {
            building = true
            failed = false
            val uri = runCatching { FunctionX.build(c) }.onFailure { c.log.error("functionX", it) }.getOrNull()
            building = false
            if (uri == null) { failed = true; return@launch }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Kitchen Receipts – Function X")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    })
}
