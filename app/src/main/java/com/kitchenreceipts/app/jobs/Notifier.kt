package com.kitchenreceipts.app.jobs

import android.content.res.Resources
import com.kitchenreceipts.app.R
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.PriceChange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the background reading tells the operator through notifications (see [ReadingNotifier]). A document read on its
 * own gets its own messages, as always; a file of many invoices gets one result for the whole file. An interface, so a
 * test can count what would be posted.
 */
interface Notifier {
    /** A document read on its own is ready to check, saved by the app, or could not be read. */
    fun finished(job: ImportJob)

    /** Prices of a document read on its own changed by 5% or more against the last purchase. */
    fun priceChanges(job: ImportJob, changes: List<PriceChange>, saved: Boolean)

    /** A long file was looked through: its invoices are ready to be sorted. */
    fun bundleScanned(planId: String)

    /** A long file holds no invoices the app can cut apart: the question waits in the app. */
    fun bundleLong(planId: String, pages: Int)

    /**
     * Every invoice of the file was read: one result ("Invoices read · Saved · To check · Not readable"), and at most
     * one more when prices changed on some of them.
     */
    fun bundleFinished(plan: BundlePlan)

    /** The "reading paused" message goes once the work carries on. */
    fun cancelPaused()
}

/**
 * The file's name for the operator: "Invoices 01/09–30/09 · 173", from the dates printed on its invoices, or
 * "Invoices imported on 22/10/2026 · 173" when none was read. [found]: the invoices found so far while it is looked
 * through. A file with no invoice found yet (a scanned one waiting for the answer, a look-through just started) is
 * named by its pages: "File imported on 22/10/2026 · 344 pages".
 */
fun bundleTitle(res: Resources, plan: BundlePlan, found: Int? = null): String {
    val reading = plan.status in setOf(BundleStatus.PREPARING, BundleStatus.QUEUED, BundleStatus.PAUSED, BundleStatus.DONE)
    val invoices = plan.invoices.filter { it.isInvoice && !it.creditNote && (!reading || it.outcome != InvoiceOutcome.LEFT_OUT) }
    val count = when {
        plan.status == BundleStatus.SCANNING -> found ?: 0
        reading -> BundleWizard.counts(plan).total
        else -> invoices.size
    }
    val dates = invoices.mapNotNull { it.date }
    val first = dates.minOrNull()
    val last = dates.maxOrNull()
    if (first == null || last == null) {
        val day = ItalianDates.format(Instant.ofEpochMilli(plan.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate())
        val unread = plan.status == BundleStatus.NOT_SPLITTABLE || (plan.status == BundleStatus.SCANNING && count == 0)
        if (unread && plan.pageCount > 0) {
            return res.getString(R.string.bundle_card_title_pages, day, res.getQuantityString(R.plurals.pages_count, plan.pageCount, plan.pageCount))
        }
        return res.getString(R.string.bundle_card_title_nodate, day, count)
    }
    return res.getString(R.string.bundle_card_title, SHORT_DATE.format(first), SHORT_DATE.format(last), count)
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
