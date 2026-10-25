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
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.PriceChange

/**
 * Notifications for background reading: an ongoing one while documents are read (with progress), and one when
 * a document is ready to check or was saved by itself. Tapping opens the right screen. Only counts, supplier
 * names and totals are shown — they stay on the lock screen as "private" (content hidden when locked). The invoices of
 * a file of many get no message each: one ongoing line for the file, and one result when it is read.
 */
class ReadingNotifier(private val context: Context) : Notifier {

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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PRICES, c.getString(R.string.notif_channel_prices), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** The app's language, made once per language (the progress notification is updated every second). */
    @Volatile private var cached: Pair<String?, Context>? = null

    private fun localized(): Context {
        val lang = context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE).getString("language", null)
        cached?.let { (l, c) -> if (l == lang) return c }
        return AppSettings.wrapWithLanguage(context).also { cached = lang to it }
    }

    private fun openIntent(route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The ongoing notification of the reading service: the document being read, or else the file of many invoices
     * being looked through ([scan]: its plan and where the look is). An invoice of a file of many ([plans]) shows the
     * file's count instead ("Reading 120 of 173 · 12 ready to check"), and a tap opens the next one to check, never
     * one of [rechecking] (the app is checking it again right now).
     */
    fun progress(
        jobs: List<ImportJob>,
        scan: Pair<String, BundleStore.ScanProgress>? = null,
        plans: List<BundlePlan> = emptyList(),
        rechecking: Set<String> = emptySet(),
    ): Notification {
        val c = localized()
        val reading = jobs.firstOrNull { it.status == JobStatus.READING }
        // The invoices of a file of many are counted by their file, not as documents waiting.
        val waiting = jobs.count { it.status == JobStatus.QUEUED && it.bundleId == null }
        if (reading == null && waiting == 0 && scan != null && jobs.none { it.status == JobStatus.QUEUED }) return scanning(c, scan.first, scan.second)
        val plan = (reading ?: jobs.firstOrNull { it.status == JobStatus.QUEUED })?.bundleId?.let { id -> plans.firstOrNull { it.id == id } }
        if (plan != null) return bundleProgress(c, plan, reading?.progress, rechecking)
        val p = reading?.progress
        val text = when {
            p == null -> c.getString(R.string.notif_starting)
            p.pass == 4 -> c.getString(R.string.ai_checking, p.page) + (com.kitchenreceipts.app.ocr.aiTopicText(c.resources, p.aiTopic)?.let { ": $it" } ?: "")
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

    /**
     * "Reading 120 of 173 · 12 ready to check", updated quietly; the page of the invoice being read below. A tap opens
     * the next invoice to check, or the file's list while none is ready.
     */
    private fun bundleProgress(c: Context, plan: BundlePlan, p: com.kitchenreceipts.app.ocr.ImportProgress?, rechecking: Set<String>): Notification {
        val n = BundleWizard.counts(plan)
        val next = BundleWizard.nextJob(plan, skip = rechecking)
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(c.getString(R.string.notif_bundle_progress, (n.read + 1).coerceAtMost(n.total), n.total, n.toCheck))
            .setContentText(bundleTitle(c.resources, plan))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(next?.let { "review/$it" } ?: bundleRoute(plan.id), 0))
        if (n.total > 0) b.setProgress(n.total, n.read.coerceIn(0, n.total), false) else b.setProgress(0, 0, true)
        if (p != null && p.of > 0) b.setSubText(c.getString(R.string.notif_page, p.page, p.of))
        return b.build()
    }

    /** "Looking through the file: page 120 of 344", with the invoices found so far; a tap opens the file's import. */
    private fun scanning(c: Context, planId: String, s: BundleStore.ScanProgress): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(c.getString(R.string.notif_bundle_scanning, s.page, s.of))
            .setContentText(c.getString(R.string.bundle_scan_found, s.found))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(bundleRoute(planId), 0))
            .setProgress(s.of.coerceAtLeast(1), s.page.coerceIn(0, s.of.coerceAtLeast(1)), s.of <= 0)
            .build()

    /** A long file was looked through: its invoices are ready to be sorted (a tap opens the file's import). */
    override fun bundleScanned(planId: String) {
        if (!canPost()) return
        val c = localized()
        result(BUNDLE_PREFIX + planId, c.getString(R.string.notif_bundle_scanned), "", bundleRoute(planId))
    }

    /** A long file holds no invoices the app can cut apart: the question waits in the app ("Read the first 20 pages"). */
    override fun bundleLong(planId: String, pages: Int) {
        if (!canPost()) return
        val c = localized()
        result(BUNDLE_PREFIX + planId, c.getString(R.string.import_long_title, pages), c.getString(R.string.import_long_body), bundleRoute(planId))
    }

    /** Android stopped the background work for today: one quiet message, and opening the app carries on. */
    fun bundlePaused() {
        if (!canPost()) return
        val c = localized()
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(c.getString(R.string.notif_bundle_paused))
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(null, PAUSED_ID))
            .build()
        manager.notify(PAUSED_ID, n)
    }

    /** The paused message goes once the work carries on. */
    override fun cancelPaused() = manager.cancel(PAUSED_ID)

    /**
     * The file was read: one result in place of the "file is ready" message (the file's name, then "Invoices read: 173 ·
     * Saved: 148 · To check: 25 · Not readable: 0"), a tap opens the file's list. When prices changed on some of its
     * invoices, one more message, which opens the list showing those.
     */
    override fun bundleFinished(plan: BundlePlan) {
        if (!canPost()) return
        val c = localized()
        val n = BundleWizard.counts(plan)
        val title = bundleTitle(c.resources, plan)
        result(BUNDLE_PREFIX + plan.id, title, c.getString(R.string.notif_bundle_done, n.read, n.saved, n.toCheck, n.failed), bundleRoute(plan.id))
        val prices = plan.invoices.count { it.pricesChanged }
        if (prices == 0) return
        val tag = PRICES_PREFIX + plan.id
        val b = NotificationCompat.Builder(context, CHANNEL_PRICES)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(c.resources.getQuantityString(R.plurals.notif_bundle_prices, prices, prices))
            .setContentText(title)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(bundleRoute(plan.id) + "?prices=true", tag.hashCode()))
        manager.notify(tag.hashCode(), b.build())
    }

    private fun result(tag: String, title: String, text: String, route: String?) {
        val b = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(route, tag.hashCode()))
        if (text.isNotEmpty()) b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        manager.notify(tag.hashCode(), b.build())
    }

    private fun bundleRoute(planId: String) = "bundle/$planId"

    override fun finished(job: ImportJob) {
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

    /**
     * Prices of a document read in the background that changed by 5% or more against the last purchase.
     * [saved]: the app saved the document itself (tap opens it); otherwise it waits to be checked (tap opens the check).
     */
    override fun priceChanges(job: ImportJob, changes: List<PriceChange>, saved: Boolean) {
        if (changes.isEmpty() || !canPost()) return
        val c = localized()
        val lines = changes.map { ch ->
            val sign = if (ch.percent.signum() > 0) "+" else ""
            c.getString(
                R.string.notif_price_line,
                ch.productName,
                sign + ItalianNumbers.formatDecimal(ch.percent, minScale = 1, maxScale = 1) + "%",
                ItalianNumbers.formatDecimal(ch.oldPrice, minScale = 2, maxScale = 4),
                ItalianNumbers.formatDecimal(ch.newPrice, minScale = 2, maxScale = 4),
                ch.unit,
            )
        }
        val title = c.resources.getQuantityString(R.plurals.notif_price_title, changes.size, changes.size)
        val sub = listOfNotNull(job.sellerName, if (saved) null else c.getString(R.string.notif_price_not_saved)).joinToString(" · ")
        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+" + (lines.size - 6))
        if (sub.isNotEmpty()) style.setBigContentTitle("$title · $sub")
        val route = if (saved) job.savedDocumentId?.let { "document/$it" } else "review/${job.id}"
        val n = NotificationCompat.Builder(context, CHANNEL_PRICES)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(route, ("price" + job.id).hashCode()))
            .build()
        manager.notify(("price" + job.id).hashCode(), n)
    }

    /** Dishes a new document's prices pushed over the food cost target (tap opens the dish, or the list). */
    fun dishesOverTarget(documentId: Long, alerts: List<com.kitchenreceipts.core.FoodCost.DishAlert>, target: java.math.BigDecimal) {
        if (alerts.isEmpty() || !canPost()) return
        val c = localized()
        fun pc(v: java.math.BigDecimal) = ItalianNumbers.formatDecimal(v, minScale = 1, maxScale = 1) + "%"
        val lines = alerts.map { a ->
            c.getString(
                R.string.notif_dish_line,
                a.cost.recipe.name,
                a.beforePercent?.let(::pc) ?: "–",
                pc(a.nowPercent),
                a.changed.joinToString(", "),
            )
        }
        val title = c.resources.getQuantityString(R.plurals.notif_dish_title, alerts.size, alerts.size, pc(target))
        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+" + (lines.size - 6))
        val route = alerts.singleOrNull()?.let { "recipe/${it.cost.recipe.id}" } ?: "food"
        val n = NotificationCompat.Builder(context, CHANNEL_PRICES)
            .setSmallIcon(R.drawable.ic_stat_reading)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(route, ("dish$documentId").hashCode()))
            .build()
        manager.notify(("dish$documentId").hashCode(), n)
    }

    fun cancelFor(jobId: String) = manager.cancel(jobId.hashCode())

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_PROGRESS = "reading"
        const val CHANNEL_RESULTS = "results"
        const val CHANNEL_PRICES = "prices"
        const val PROGRESS_ID = 4711
        const val PAUSED_ID = 4712
        private const val BUNDLE_PREFIX = "bundle:"
        private const val PRICES_PREFIX = "bundleprices:"
    }
}
