@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.kitchenreceipts.app.jobs

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kitchenreceipts.app.KitchenReceiptsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app alive (as a foreground service with a visible notification) while documents are read, or a file of
 * many invoices is looked through, so the work continues when the operator switches to another app or turns the
 * screen off. The reading itself happens in [ImportQueue] and the looking through in [BundleStore] (started from
 * here, and carried on from its last saved page); this service shows their progress and stops when nothing is left.
 */
class ReadingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as KitchenReceiptsApp).container
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(
            this, ReadingNotifier.PROGRESS_ID,
            c.notifier.progress(c.importQueue.jobs.value, c.bundleStore.scans.value.entries.firstOrNull()?.toPair(), c.bundleStore.plans.value), type,
        )
        // Files being looked through when the app was closed carry on from their last saved page.
        c.bundleStore.resumeScans()
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            // At most one notification update per second (Android also rate-limits them). A file of many invoices is
            // counted from its plan ("Reading 120 of 173 · 12 ready to check").
            combine(c.importQueue.jobs, c.bundleStore.scans, c.bundleStore.plans) { jobs, scans, plans -> Triple(jobs, scans, plans) }
                .sample(1000).collectLatest { (jobs, scans, plans) ->
                    if (jobs.none { it.status == JobStatus.QUEUED || it.status == JobStatus.READING } && !c.bundleStore.busy) {
                        ServiceCompat.stopForeground(this@ReadingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (NotificationManagerCompat.from(this@ReadingService).areNotificationsEnabled()) {
                        val scan = scans.entries.firstOrNull()?.toPair()
                        runCatching { NotificationManagerCompat.from(this@ReadingService).notify(ReadingNotifier.PROGRESS_ID, c.notifier.progress(jobs, scan, plans)) }
                    }
                }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 limits how long this kind of service may run per day: stop cleanly (reading goes on while the app is
     * open). A file being looked through stops at its last saved page, and the files being read show as paused; one
     * quiet message says so. Opening the app carries on (see [ensureRunning]).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val c = (application as KitchenReceiptsApp).container
        val paused = runCatching { c.bundleStore.pause() }.getOrDefault(false)
        if (paused || c.importQueue.busy) {
            c.log.event("READING_PAUSED", "bundles" to paused, "reading" to c.importQueue.busy)
            c.notifier.bundlePaused()
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * Starts the service if something is waiting to be read or looked through. Call from the foreground (e.g. right
         * after scanning, or when the app opens: what Android paused carries on).
         */
        fun ensureRunning(context: Context) {
            val c = (context.applicationContext as KitchenReceiptsApp).container
            c.bundleStore.resume()
            if (!c.importQueue.busy && !c.bundleStore.busy) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadingService::class.java)) }
                .onFailure { c.log.error("readingService", it) }
        }
    }
}
