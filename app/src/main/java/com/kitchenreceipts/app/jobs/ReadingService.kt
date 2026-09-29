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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app alive (as a foreground service with a visible notification) while documents are read, so reading
 * continues when the operator switches to another app or turns the screen off. The reading itself happens in
 * [ImportQueue]; this service only shows its progress and stops when there is nothing left to read.
 */
class ReadingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as KitchenReceiptsApp).container
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ReadingNotifier.PROGRESS_ID, c.notifier.progress(c.importQueue.jobs.value), type)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            // At most one notification update per second (Android also rate-limits them).
            c.importQueue.jobs.sample(1000).collectLatest { jobs ->
                if (jobs.none { it.status == JobStatus.QUEUED || it.status == JobStatus.READING }) {
                    ServiceCompat.stopForeground(this@ReadingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (NotificationManagerCompat.from(this@ReadingService).areNotificationsEnabled()) {
                    runCatching { NotificationManagerCompat.from(this@ReadingService).notify(ReadingNotifier.PROGRESS_ID, c.notifier.progress(jobs)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits how long this kind of service may run per day: stop cleanly (reading goes on while the app is open). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts the service if something is waiting to be read. Call from the foreground (e.g. right after scanning). */
        fun ensureRunning(context: Context) {
            val c = (context.applicationContext as KitchenReceiptsApp).container
            if (!c.importQueue.busy) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadingService::class.java)) }
                .onFailure { c.log.error("readingService", it) }
        }
    }
}
