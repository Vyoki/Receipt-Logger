package com.kitchenreceipts.app.ui.home

import androidx.compose.foundation.BorderStroke
import com.kitchenreceipts.app.ui.components.Button
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.theme.Palette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.ImportJob
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.app.jobs.bundleTitle
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.Motion
import com.kitchenreceipts.app.ui.theme.pressScale
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard

/**
 * Work in the background on the home screen, as items of its list (keyed: a change moves only its own card): one card
 * per document read on its own (as always), and one card per file of many invoices instead of one per invoice. A
 * file's counts come from its plan's outcomes, never from the job list. [onDiscard] asks before throwing a document
 * away (see [DiscardJobDialog]).
 */
fun LazyListScope.jobItems(
    jobs: List<ImportJob>,
    plans: List<BundlePlan>,
    scans: Map<String, BundleStore.ScanProgress>,
    onReview: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenBundle: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    items(plans.filter { it.status in ON_HOME }, key = { "plan-" + it.id }) { plan ->
        Box(Modifier.animateItem(placementSpec = Motion.place)) {
            BundleCard(plan, scans[plan.id], onOpen = { onOpenBundle(plan.id) }, onReview = onReview)
        }
    }
    // The invoices of a file of many are on their file's card.
    items(jobs.filter { it.bundleId == null }, key = { "job-" + it.id }) { job ->
        val queue = appContainer().importQueue
        Box(Modifier.animateItem(placementSpec = Motion.place)) {
            JobCard(
                job,
                onReview = { onReview(job.id) },
                onOpen = { job.savedDocumentId?.let(onOpenDocument); queue.dismiss(job.id) },
                onDismiss = { queue.dismiss(job.id) },
                onRetry = { queue.retry(job.id) },
                onDiscard = { onDiscard(job.id) },
            )
        }
    }
}

/** The files of many invoices with a card on the home screen: until the operator closes the import. */
private val ON_HOME = setOf(
    BundleStatus.SCANNING, BundleStatus.NOT_SPLITTABLE, BundleStatus.SORTING, BundleStatus.PREPARING,
    BundleStatus.QUEUED, BundleStatus.PAUSED, BundleStatus.DONE,
)

/** Discarding deletes the photo: asked first, as on the review screen (a wet finger hits the button next to it). */
@Composable
fun DiscardJobDialog(jobId: String?, onClose: () -> Unit) {
    val id = jobId ?: return
    val queue = appContainer().importQueue
    com.kitchenreceipts.app.ui.components.ConfirmDialog(
        title = stringResource(R.string.discard_title),
        text = stringResource(R.string.discard_text),
        confirmLabel = stringResource(R.string.discard),
        onConfirm = { onClose(); queue.discard(id) },
        onDismiss = onClose,
        dismissLabel = stringResource(R.string.keep),
    )
}

/**
 * One file of many invoices: "Invoices 01/09–30/09 · 173", a progress bar and one action: carry on with the import,
 * "25 to check →" (the next invoice of the chain), or a word that Android paused the reading. A tap on the card opens
 * the file's list.
 */
@Composable
private fun BundleCard(plan: BundlePlan, scan: BundleStore.ScanProgress?, onOpen: () -> Unit, onReview: (String) -> Unit) {
    val store = appContainer().bundleStore
    val res = LocalContext.current.resources
    val n = BundleWizard.counts(plan)
    val wizard = plan.status in WIZARD
    val accent = when {
        plan.status == BundleStatus.DONE -> Palette.Ok
        n.toCheck > 0 || wizard -> Palette.Orange
        else -> Palette.PhthaloBorder
    }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(
        onClick = onOpen,
        interactionSource = source,
        modifier = Modifier.pressScale(source).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Palette.Panel, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, accent),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        plan.status == BundleStatus.DONE -> Icons.Filled.CheckCircle
                        n.toCheck > 0 -> Icons.Filled.RateReview
                        else -> Icons.Filled.FolderCopy
                    },
                    contentDescription = null,
                    tint = accent,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(bundleTitle(res, plan, scan?.found), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val line = when {
                        wizard -> stringResource(R.string.bundle_continue)
                        plan.status == BundleStatus.PAUSED -> stringResource(R.string.bundle_paused)
                        plan.status == BundleStatus.DONE -> stringResource(R.string.bundle_all_done)
                        else -> stringResource(R.string.bundle_card_progress, n.read, n.total)
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = if (wizard || plan.status == BundleStatus.PAUSED) Palette.Orange else Palette.TextDim)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.TextDim)
            }
            when {
                plan.status == BundleStatus.SCANNING && scan != null && scan.of > 0 ->
                    LinearProgressIndicator(progress = { scan.page.toFloat() / scan.of }, modifier = Modifier.fillMaxWidth())
                plan.status == BundleStatus.SCANNING || plan.status == BundleStatus.PREPARING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                (plan.status == BundleStatus.QUEUED || plan.status == BundleStatus.PAUSED) && n.total > 0 ->
                    LinearProgressIndicator(progress = { n.read.toFloat() / n.total }, modifier = Modifier.fillMaxWidth())
            }
            // One action: the next invoice to check (the chain), in plan order.
            if (!wizard && n.toCheck > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { store.next(plan.id)?.let(onReview) ?: onOpen() },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(pluralStringResource(R.plurals.bundle_card_to_check, n.toCheck, n.toCheck)) }
                }
            }
        }
    }
}

/** The import is still in the wizard: looked through, asked, sorted or cut. */
private val WIZARD = setOf(BundleStatus.SCANNING, BundleStatus.NOT_SPLITTABLE, BundleStatus.SORTING, BundleStatus.PREPARING)

@Composable
private fun JobCard(job: ImportJob, onReview: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    // Black panel, border in the colour of the state: green while reading, orange to check, red when it failed.
    val accent = when (job.status) {
        JobStatus.READY -> Palette.Orange
        JobStatus.FAILED -> Palette.Error
        JobStatus.SAVED -> Palette.Ok
        else -> Palette.PhthaloBorder
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Palette.Panel, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, accent),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (job.status) {
                        JobStatus.QUEUED, JobStatus.READING -> Icons.Filled.HourglassTop
                        JobStatus.READY -> Icons.Filled.RateReview
                        JobStatus.SAVED -> Icons.Filled.CheckCircle
                        JobStatus.FAILED -> Icons.Filled.ErrorOutline
                    },
                    contentDescription = null,
                    tint = accent,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title(job), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle(job), style = MaterialTheme.typography.bodySmall, color = if (job.status == JobStatus.READING) Palette.Orange else Palette.TextDim)
                }
            }
            if (job.status == JobStatus.READING) {
                val p = job.progress
                if (p != null && p.of > 0 && !(p.pass == 3 && p.aiStage == 0)) {
                    LinearProgressIndicator(progress = { ((p.page - 1).toFloat() + if (p.pass == 3 && p.aiStage == 1) 0.5f else 0f) / p.of }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when (job.status) {
                    JobStatus.QUEUED, JobStatus.READING -> TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.job_stop)) }
                    JobStatus.READY -> {
                        TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discard)) }
                        Button(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.job_check)) }
                    }
                    JobStatus.SAVED -> {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.job_hide)) }
                        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.job_open)) }
                    }
                    JobStatus.FAILED -> {
                        TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discard)) }
                        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.job_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun title(job: ImportJob): String = when (job.status) {
    JobStatus.QUEUED -> stringResource(R.string.job_waiting)
    JobStatus.READING -> stringResource(R.string.job_reading)
    JobStatus.READY -> stringResource(R.string.job_ready)
    JobStatus.SAVED -> stringResource(R.string.job_saved)
    JobStatus.FAILED -> stringResource(R.string.job_failed)
}

@Composable
private fun subtitle(job: ImportJob): String {
    val pages = androidx.compose.ui.res.pluralStringResource(R.plurals.pages_count, job.file.pageCount, job.file.pageCount)
    val p = job.progress
    return when (job.status) {
        JobStatus.READING -> when {
            p == null -> pages
            p.pass == 4 -> stringResource(R.string.ai_checking, p.page) +
                (com.kitchenreceipts.app.ocr.aiTopicText(androidx.compose.ui.platform.LocalContext.current.resources, p.aiTopic)?.let { ": $it" } ?: "")
            p.pass == 3 -> stringResource(R.string.ai_reading_page, p.page, p.of) + " · " +
                (if (p.aiStage == 0) stringResource(R.string.ai_looking) else stringResource(R.string.ai_writing, p.aiCount))
            p.pass == 2 -> stringResource(R.string.reading_page, p.page, p.of) + " · " + stringResource(R.string.job_second_look)
            else -> stringResource(R.string.reading_page, p.page, p.of)
        }
        JobStatus.READY, JobStatus.SAVED -> listOfNotNull(job.sellerName, job.totalCents?.let { fmtMoney(it) }, pages).joinToString(" · ")
        JobStatus.FAILED -> if (job.error == com.kitchenreceipts.app.jobs.ImportQueue.STOPPED) stringResource(R.string.job_stopped_twice) else job.error ?: pages
        JobStatus.QUEUED -> pages
    }
}
