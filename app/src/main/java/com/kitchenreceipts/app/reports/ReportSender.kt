package com.kitchenreceipts.app.reports

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.content.FileProvider
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.BossReport
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A finished report: the PDF and the short message that goes with it (WhatsApp preview / e-mail body). */
data class ReadyReport(val file: File, val subject: String, val message: String, val report: BossReport)

/**
 * Builds the owner's report on the phone and hands it to the share sheet. Nothing leaves the phone until the
 * operator picks where to send it (WhatsApp, Mail, ...).
 */
object ReportSender {

    fun localized(context: Context, language: AppSettings.Language): Context {
        val tag = language.tag ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    suspend fun build(context: Context, repo: ReceiptRepository, period: Period, language: AppSettings.Language, businessName: String): ReadyReport {
        val report = repo.bossReport(period)
        val ctx = localized(context, language)
        val pdf = ReportPdf(ctx)
        val label = pdf.periodLabel(period)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".pdf") }?.forEach { it.delete() } // only the latest report is kept
        val safe = label.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val file = File(dir, ctx.getString(R.string.rep_file_prefix) + "_" + safe + ".pdf")
        withContext(Dispatchers.IO) { pdf.write(report, businessName, file) }
        val change = report.changePercent?.let { p ->
            " (" + (if (p.signum() > 0) "+" else "") + ItalianNumbers.formatDecimal(p, minScale = 1, maxScale = 1) + "% " +
                ctx.getString(R.string.rep_vs, pdf.periodLabel(report.previousPeriod)) + ")"
        } ?: ""
        val message = ctx.getString(
            R.string.rep_share_text, label, ItalianNumbers.formatMoney(report.totalCents, "EUR") + change,
            report.documentCount, report.increases,
        )
        val subject = ctx.getString(R.string.rep_subject, businessName.ifBlank { ctx.getString(R.string.rep_title) }, label)
        return ReadyReport(file, subject, message, report)
    }

    fun shareIntent(context: Context, r: ReadyReport): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, r.subject)
            .putExtra(Intent.EXTRA_TEXT, r.message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(context: Context, r: ReadyReport): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
