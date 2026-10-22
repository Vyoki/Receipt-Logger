package com.kitchenreceipts.app.ui.bundle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.ReadingService
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.WarningCard
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.LeftOut
import kotlinx.coroutines.launch

/**
 * The import of a file of many invoices ("bundle/<planId>"): where it is, and the few answers it needs now (stop a
 * look through, the question on a long file, read, cancel, close). The full wizard (what was found, the page check,
 * the suppliers, the Read step) replaces the middle of this screen.
 */
@Composable
fun BundleScreen(planId: String, onBack: () -> Unit, onHome: () -> Unit, onReview: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val store = c.bundleStore
    val plans by store.plans.collectAsStateWithLifecycle()
    val scans by store.scans.collectAsStateWithLifecycle()
    val preparing by store.preparing.collectAsStateWithLifecycle()
    val missing by store.missing.collectAsStateWithLifecycle()
    val plan = plans.firstOrNull { it.id == planId }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<Ask?>(null) }
    // The work goes on when the screen is left (a cut takes a few seconds): it runs in the app's scope.
    fun act(block: suspend BundleStore.() -> Unit) {
        error = null
        c.appScope.launch { runCatching { store.block() }.onFailure { c.log.error("bundleAction", it) } }
    }

    AppScaffold(title = stringResource(R.string.bundle_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { WarningCard(listOf(it)) }
            when {
                plan == null || planId in missing -> {
                    WarningCard(listOf(stringResource(R.string.bundle_file_missing)))
                    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
                }
                planId in preparing || plan.status == BundleStatus.PREPARING -> {
                    CircularProgressIndicator(Modifier.size(56.dp))
                    Heading(stringResource(R.string.bundle_splitting))
                }
                plan.status == BundleStatus.SCANNING -> {
                    val s = scans[planId] ?: BundleStore.ScanProgress(plan.scanned, plan.pageCount, 0)
                    Heading(stringResource(R.string.bundle_scan_title))
                    LinearProgressIndicator(progress = { if (s.of <= 0) 0f else s.page.toFloat() / s.of }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.bundle_scan_progress, s.page, s.of), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.bundle_scan_found, s.found), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.bundle_scan_leave_ok), style = MaterialTheme.typography.bodyMedium)
                    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
                    BigButton(stringResource(R.string.bundle_stop), null, onClick = { asking = Ask.STOP_SCAN }, primary = false)
                }
                plan.status == BundleStatus.NOT_SPLITTABLE -> AskLong(
                    plan.pageCount,
                    onRead = {
                        act { readAsOne(planId) }
                        ReadingService.ensureRunning(context)
                        onHome()
                    },
                    onDont = { act { cancel(planId) }; onHome() },
                )
                plan.status == BundleStatus.SORTING -> Found(plan, onRead = {
                    error = null
                    c.appScope.launch {
                        when (val r = store.confirm(planId)) {
                            is BundleStore.Prepared.Queued -> ReadingService.ensureRunning(context)
                            is BundleStore.Prepared.Failed -> error = context.getString(r.reason.text)
                        }
                    }
                }, onCancel = { asking = Ask.CANCEL })
                plan.status == BundleStatus.CLOSED || plan.status == BundleStatus.CANCELLED -> {
                    if (store.isOneDocument(plan)) Text(stringResource(R.string.bundle_one_document), style = MaterialTheme.typography.titleMedium)
                    else if (plan.status == BundleStatus.CLOSED) Heading(stringResource(R.string.bundle_all_done))
                    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
                }
                else -> Reading(plan, onReview = onReview, onClose = { act { close(planId) }; onHome() }, onHome = onHome)
            }
        }
    }

    when (asking) {
        Ask.STOP_SCAN -> ConfirmDialog(
            title = stringResource(R.string.bundle_stop_title),
            text = stringResource(R.string.bundle_stop_body),
            confirmLabel = stringResource(R.string.bundle_stop_confirm),
            dismissLabel = stringResource(R.string.bundle_keep_looking),
            onConfirm = { asking = null; act { cancel(planId) }; onHome() },
            onDismiss = { asking = null },
        )
        Ask.CANCEL -> ConfirmDialog(
            title = stringResource(R.string.bundle_cancel_title),
            text = stringResource(R.string.bundle_cancel_body),
            confirmLabel = stringResource(R.string.bundle_cancel_import),
            dismissLabel = stringResource(R.string.bundle_cancel_keep),
            onConfirm = { asking = null; act { cancel(planId) }; onHome() },
            onDismiss = { asking = null },
        )
        null -> Unit
    }
}

private enum class Ask { STOP_SCAN, CANCEL }

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/** What the file holds and what is left out, with Read (the wizard's steps come here). */
@Composable
private fun Found(plan: BundlePlan, onRead: () -> Unit, onCancel: () -> Unit) {
    val f = BundleWizard.found(plan)
    val toRead = BundleWizard.toRead(plan).size
    val nextImport = plan.invoices.count { it.leftOut == LeftOut.NEXT_IMPORT }
    Heading(
        stringResource(
            R.string.bundle_found_title,
            pluralStringResource(R.plurals.bundle_invoices, f.invoices, f.invoices),
            pluralStringResource(R.plurals.bundle_suppliers, f.suppliers, f.suppliers),
        ),
    )
    if (f.first != null && f.last != null) Text(stringResource(R.string.bundle_dates, fmtDate(f.first), fmtDate(f.last)), style = MaterialTheme.typography.bodyLarge)
    val rows = buildList {
        if (f.alreadySaved > 0) add(pluralStringResource(R.plurals.bundle_already_saved, f.alreadySaved, f.alreadySaved))
        if (f.creditNotes > 0) add(pluralStringResource(R.plurals.bundle_credit_notes_left, f.creditNotes, f.creditNotes))
        if (nextImport > 0) add(stringResource(R.string.bundle_too_many, f.invoices, BundleWizard.MAX_INVOICES))
    }
    rows.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    BigButton(pluralStringResource(R.plurals.bundle_read_button, toRead, toRead), null, onClick = onRead, enabled = toRead > 0)
    BigButton(stringResource(R.string.bundle_cancel_import), null, onClick = onCancel, primary = false)
}

/** Being read, paused or done: the counts from the plan's outcomes, and the next invoice to check. */
@Composable
private fun Reading(plan: BundlePlan, onReview: (String) -> Unit, onClose: () -> Unit, onHome: () -> Unit) {
    val n = BundleWizard.counts(plan)
    val done = plan.status == BundleStatus.DONE
    Heading(if (done) stringResource(R.string.bundle_all_done) else pluralStringResource(R.plurals.bundle_queued_title, n.total, n.total))
    LinearProgressIndicator(progress = { if (n.total <= 0) 1f else n.read.toFloat() / n.total }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.bundle_card_progress, n.read, n.total), style = MaterialTheme.typography.titleMedium)
    when (plan.status) {
        BundleStatus.PAUSED -> Text(stringResource(R.string.bundle_paused), style = MaterialTheme.typography.bodyLarge)
        BundleStatus.QUEUED -> Text(stringResource(R.string.bundle_queued_body), style = MaterialTheme.typography.bodyLarge)
        else -> Unit
    }
    val next = BundleWizard.nextToCheck(plan)?.let { plan.invoices[it].jobId }
    if (n.toCheck > 0 && next != null) {
        BigButton(pluralStringResource(R.plurals.bundle_card_to_check, n.toCheck, n.toCheck), null, onClick = { onReview(next) })
    }
    if (done) {
        Text(stringResource(R.string.bundle_close_note), style = MaterialTheme.typography.bodyMedium)
        BigButton(stringResource(R.string.bundle_close), null, onClick = onClose)
    }
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome, primary = false)
}
