package com.kitchenreceipts.app.jobs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kitchenreceipts.app.MainActivity
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.ItalianNumbers

/**
 * Notifications for background reading: an ongoing one while documents are read (with progress), and one when
 * a document is ready to check or was saved by itself. Tapping opens the right screen. Only counts, supplier
 * names and totals are shown — they stay on the lock screen as "private" (content hidden when locked).
 */
class ReadingNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val c = localized()
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, c.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULTS, c.getString(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun localized(): Context = AppSettings.wrapWithLanguage(context)

    private fun openIntent(route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The ongoing notification of the reading service. */
    fun progress(jobs: List<ImportJob>): Notification {
        val c = localized()
        val reading = jobs.firstOrNull { it.status == JobStatus.READING }
        val waiting = jobs.count { it.status == JobStatus.QUEUED }
        val p = reading?.progress
        val text = when {
            p == null -> c.getString(R.string.notif_starting)
            p.pass == 3 -> c.getString(R.string.notif_ai_page, p.page, p.of) +
                (if (p.aiStage == 1) " · " + c.getString(R.string.ai_writing, p.aiCount) else " · " + c.getString(R.string.ai_looking))
            else -> c.getString(R.string.notif_page, p.page, p.of)
        }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(c.resources.getQuantityString(R.plurals.notif_reading, 1 + waiting, 1 + waiting))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(null, 0))
        if (p != null && p.of > 0) {
            // Pages read so far (each page counts once per reading).
            b.setProgress(p.of, (p.page - 1).coerceAtLeast(0), p.pass == 3 && p.aiStage == 0)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    fun finished(job: ImportJob) {
        if (!canPost()) return
        val c = localized()
        val (title, text, route) = when (job.status) {
            JobStatus.SAVED -> Triple(
                c.getString(R.string.notif_saved),
                listOfNotNull(job.sellerName, job.totalCents?.let { ItalianNumbers.formatMoney(it, "EUR") }).joinToString(" · "),
                job.savedDocumentId?.let { "document/$it?auto=true" },
            )
            JobStatus.READY -> Triple(
                c.getString(R.string.notif_ready),
                listOfNotNull(job.sellerName, job.totalCents?.let { ItalianNumbers.formatMoney(it, "EUR") }).joinToString(" · ")
                    .ifEmpty { c.getString(R.string.notif_ready_text) },
                "review/${job.id}",
            )
            JobStatus.FAILED -> Triple(c.getString(R.string.notif_failed), job.error ?: "", null)
            else -> return
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(route, job.id.hashCode()))
            .build()
        manager.notify(job.id.hashCode(), n)
    }

    fun cancelFor(jobId: String) = manager.cancel(jobId.hashCode())

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_PROGRESS = "reading"
        const val CHANNEL_RESULTS = "results"
        const val PROGRESS_ID = 4711
    }
}
