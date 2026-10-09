package com.kitchenreceipts.app.ui.home

import androidx.compose.foundation.BorderStroke
import com.kitchenreceipts.app.ui.components.Button
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.theme.Palette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.jobs.ImportJob
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.fmtMoney

/** Documents being read in the background, and those ready to check or saved by themselves. */
@Composable
fun JobsSection(onReview: (String) -> Unit, onOpenDocument: (Long) -> Unit) {
    val queue = appContainer().importQueue
    val jobs by queue.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        jobs.forEach { job ->
            JobCard(
                job,
                onReview = { onReview(job.id) },
                onOpen = { job.savedDocumentId?.let(onOpenDocument); queue.dismiss(job.id) },
                onDismiss = { queue.dismiss(job.id) },
                onRetry = { queue.retry(job.id) },
                onDiscard = { queue.discard(job.id) },
            )
        }
    }
}

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
        JobStatus.FAILED -> job.error ?: pages
        JobStatus.QUEUED -> pages
    }
}
