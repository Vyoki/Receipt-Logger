package com.kitchenreceipts.app.jobs

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.kitchenreceipts.app.settings.AppSettings

/**
 * How long reading takes on this phone, for the honest "About 1 h 20 min" before a file of many invoices is read:
 * pages × the seconds per page, plus the AI's seconds per document when it helps. Both start from a plain guess and
 * follow what each reading really took (kept in the settings).
 */
object ReadingTime {

    const val START_PER_PAGE = 2f
    const val START_AI_PER_JOB = 60f

    /** Above this, "Plug in the charger" is shown when the phone is not charging. */
    const val LONG_SECONDS = 20 * 60L

    /** How much one new reading moves the average (the rest is what was measured before). */
    private const val WEIGHT = 0.2f

    /** Seconds to read [pages] pages in [documents] documents; [ai]: the AI reader helps with each. */
    fun seconds(settings: AppSettings, pages: Int, documents: Int, ai: Boolean): Long {
        val perPage = settings.readSecondsPerPage.takeIf { it.isFinite() && it > 0f } ?: START_PER_PAGE
        val perJob = settings.aiSecondsPerJob.takeIf { it.isFinite() && it >= 0f } ?: START_AI_PER_JOB
        return (pages * perPage + if (ai) documents * perJob else 0f).toLong()
    }

    /** One document was read: [pages] pages in [millis], with or without the AI. */
    fun record(settings: AppSettings, pages: Int, millis: Long, ai: Boolean) {
        if (pages <= 0 || millis <= 0) return
        val total = millis / 1000f
        val perPage = settings.readSecondsPerPage
        if (!ai) {
            settings.readSecondsPerPage = mix(perPage, total / pages, START_PER_PAGE)
        } else {
            // What the pages alone would have taken is known from the readings without the AI.
            settings.aiSecondsPerJob = mix(settings.aiSecondsPerJob, (total - pages * perPage).coerceAtLeast(0f), START_AI_PER_JOB)
        }
    }

    private fun mix(old: Float, sample: Float, start: Float): Float {
        val base = old.takeIf { it.isFinite() && it >= 0f } ?: start
        return if (!sample.isFinite()) base else base * (1 - WEIGHT) + sample * WEIGHT
    }

    /**
     * True when the phone is on a charger, false when not, null when Android does not say. From the battery's last
     * broadcast, which Android keeps for anyone to read: no permission and no receiver left behind.
     */
    fun charging(context: Context): Boolean? = runCatching {
        val battery = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return null
        battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 }?.let { it != 0 }
    }.getOrNull()
}
