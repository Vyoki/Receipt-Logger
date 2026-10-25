package com.kitchenreceipts.app.ui.bundle

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.ReadingTime
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.Annotation
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.components.SubBarText
import com.kitchenreceipts.app.ui.components.WarningCard
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.supplierCategoryIcon
import com.kitchenreceipts.app.ui.supplierCategoryLabel
import com.kitchenreceipts.app.ui.theme.Motion
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.LeftOut
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.SupplierChoice
import kotlinx.coroutines.delay

/**
 * The import of a file of many invoices ("bundle/<planId>"), one screen that follows the plan: looking through the
 * file, the question on a long file, then the wizard's steps (what the file holds, where each invoice starts, the new
 * suppliers, Read), preparing, being read, and done. Only the steps with something to decide are shown and numbered.
 * Every choice is saved at once: leaving and coming back carries on where it was. Back goes one step back; on the
 * first step it asks whether to continue later or cancel the import. While the file is read, and once it is done, the
 * page lists its invoices grouped by supplier ([pricesOnly]: only those whose prices changed).
 */
@Composable
fun BundleScreen(
    planId: String,
    pricesOnly: Boolean = false,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onReview: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    /** The file's page viewer, at a page (from 0). */
    onSeePages: (Int) -> Unit,
) {
    val vm = appViewModel(key = "bundle-$planId") { BundleViewModel(it, planId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf<Ask?>(null) }
    val back: () -> Unit = {
        if (!vm.back()) {
            if (s.page == BundlePage.Found) asking = Ask.LEAVE else onBack()
        }
    }
    BackHandler(onBack = back)
    var onlyPrices by rememberSaveable { mutableStateOf(pricesOnly) }
    val readingJob by vm.readingJob.collectAsStateWithLifecycle()
    val rechecking by vm.rechecking.collectAsStateWithLifecycle()

    // "5 more from this supplier saved by the app", once.
    val snackbar = remember { SnackbarHostState() }
    val rechecked by vm.rechecked.collectAsStateWithLifecycle()
    val res = LocalContext.current.resources
    LaunchedEffect(rechecked) {
        val r = rechecked?.takeIf { it.planId == planId } ?: return@LaunchedEffect
        vm.recheckedShown(r)
        snackbar.showSnackbar(res.getQuantityString(R.plurals.bundle_rechecked, r.count, r.count))
    }

    val plan = s.plan
    AppScaffold(
        title = stringResource(R.string.bundle_title),
        onBack = back,
        snackbarHostState = snackbar,
        subBar = plan?.let { p -> stepLine(p, s.page)?.let { line -> { SubBarText(line) } } },
        bottomBar = { if (plan != null) StepButton(plan, s.page, vm) },
    ) { padding ->
        AnimatedContent(
            targetState = s,
            contentKey = { it.page },
            transitionSpec = {
                // A step forward comes in from the right, a step back from the left.
                val forward = targetState.page.order >= initialState.page.order
                (fadeIn(Motion.enter()) + slideInHorizontally(Motion.enter()) { if (forward) it / 5 else -it / 5 }) togetherWith fadeOut(Motion.exit())
            },
            label = "bundleStep",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { st ->
            val p = st.plan
            when (val page = st.page) {
                BundlePage.Pages -> if (p != null) PageCheck(p, vm, onSeePages)
                is BundlePage.Supplier -> if (p != null) SupplierStep(p, page.at, vm)
                // Being read, or read: the file's invoices by supplier under the counts.
                BundlePage.Reading, BundlePage.Done -> if (p != null) FileList(
                    p, onlyPrices, readingJob, rechecking,
                    onShowAll = { onlyPrices = false }, onReview = onReview, onOpenDocument = onOpenDocument, onRetry = vm::retry,
                ) {
                    st.error?.let { WarningCard(listOf(stringResource(it))) }
                    if (page == BundlePage.Reading) Reading(p, st.seconds, vm, onReview, onHome, onStop = { asking = Ask.STOP_READING })
                    else Done(p, vm, onClose = {
                        // What would not be imported is said first; otherwise it closes at once.
                        val n = BundleWizard.counts(p)
                        if (n.stopped > 0 || n.failed > 0) asking = Ask.CLOSE else { vm.close(); onHome() }
                    }, onHome)
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    st.error?.let { WarningCard(listOf(stringResource(it))) }
                    if (p == null || page == BundlePage.Missing) {
                        WarningCard(listOf(stringResource(R.string.bundle_file_missing)))
                        BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
                    } else {
                        when (page) {
                            BundlePage.Scanning -> Scanning(p, st.scan, onHome, onStop = { asking = Ask.STOP_SCAN })
                            BundlePage.AskLong -> AskLong(
                                p.pageCount,
                                onRead = { vm.readAsOne(); onHome() },
                                onDont = { vm.cancel(); onHome() },
                            )
                            BundlePage.Found -> Found(p, vm, onOpenDocument, onCancel = { asking = Ask.CANCEL })
                            BundlePage.Read -> ReadStep(p, st.seconds)
                            BundlePage.Preparing -> Preparing(onHome)
                            else -> Ended(p, vm, onHome)
                        }
                    }
                }
            }
        }
    }

    when (asking) {
        Ask.STOP_SCAN -> ConfirmDialog(
            title = stringResource(R.string.bundle_stop_title),
            text = stringResource(R.string.bundle_stop_body),
            confirmLabel = stringResource(R.string.bundle_stop_confirm),
            dismissLabel = stringResource(R.string.bundle_keep_looking),
            onConfirm = { asking = null; vm.cancel(); onHome() },
            onDismiss = { asking = null },
        )
        // Back on the first step: the choices are kept either way, unless the import is cancelled (asked again).
        Ask.LEAVE -> ConfirmDialog(
            title = stringResource(R.string.bundle_leave_title),
            text = stringResource(R.string.bundle_leave_body),
            confirmLabel = stringResource(R.string.bundle_leave_later),
            dismissLabel = stringResource(R.string.bundle_cancel_import),
            onConfirm = { asking = null; onHome() },
            onDismiss = { asking = Ask.CANCEL },
            onDismissRequest = { asking = null },
        )
        Ask.CANCEL -> ConfirmDialog(
            title = stringResource(R.string.bundle_cancel_title),
            text = stringResource(R.string.bundle_cancel_body),
            confirmLabel = stringResource(R.string.bundle_cancel_import),
            dismissLabel = stringResource(R.string.bundle_cancel_keep),
            onConfirm = { asking = null; vm.cancel(); onHome() },
            onDismiss = { asking = null },
        )
        Ask.STOP_READING -> {
            // Only the ones not read yet stop: those read stay to check.
            val n = plan?.let { BundleWizard.counts(it).reading } ?: 0
            ConfirmDialog(
                title = stringResource(R.string.bundle_stop_reading_title),
                text = stringResource(R.string.bundle_stop_reading_body),
                confirmLabel = pluralStringResource(R.plurals.bundle_stop_reading_confirm, n, n),
                dismissLabel = stringResource(R.string.bundle_keep_reading),
                onConfirm = { asking = null; vm.stopReading() },
                onDismiss = { asking = null },
            )
        }
        Ask.CLOSE -> {
            val n = plan?.let { BundleWizard.counts(it) }
            val parts = listOfNotNull(
                n?.stopped?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bundle_close_not_read, it, it) },
                n?.failed?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bundle_close_not_readable, it, it) },
            )
            ConfirmDialog(
                title = stringResource(R.string.bundle_close_title),
                text = stringResource(R.string.bundle_close_body, parts.joinToString(", ")),
                confirmLabel = stringResource(R.string.bundle_close),
                dismissLabel = stringResource(R.string.bundle_close_keep),
                onConfirm = { asking = null; vm.close(); onHome() },
                onDismiss = { asking = null },
            )
        }
        null -> Unit
    }
}

private enum class Ask { STOP_SCAN, LEAVE, CANCEL, STOP_READING, CLOSE }

/** "Step 2 of 3 · Suppliers" on the wizard's steps; nothing elsewhere. */
@Composable
private fun stepLine(plan: BundlePlan, page: BundlePage): String? {
    val step = when (page) {
        BundlePage.Found -> BundleWizard.Step.FOUND
        BundlePage.Pages -> BundleWizard.Step.PAGES
        is BundlePage.Supplier -> BundleWizard.Step.SUPPLIERS
        BundlePage.Read -> BundleWizard.Step.READ
        else -> return null
    }
    val n = BundleWizard.numberOf(plan, step) ?: return null
    val name = stringResource(
        when (step) {
            BundleWizard.Step.FOUND, BundleWizard.Step.PAGES -> R.string.bundle_step_found
            BundleWizard.Step.SUPPLIERS -> R.string.bundle_step_suppliers
            BundleWizard.Step.READ -> R.string.bundle_step_read
        },
    )
    return stringResource(R.string.bundle_step, n, BundleWizard.steps(plan).size, name)
}

private class StepAction(val text: String, val enabled: Boolean, val onClick: () -> Unit)

/** The step's main button, kept in view at the bottom. */
@Composable
private fun StepButton(plan: BundlePlan, page: BundlePage, vm: BundleViewModel) {
    val a = when (page) {
        BundlePage.Found -> {
            val n = BundleWizard.toSort(plan).size
            StepAction(if (n > 0) pluralStringResource(R.plurals.bundle_next_sort, n, n) else stringResource(R.string.bundle_next_read), true) { vm.next() }
        }
        BundlePage.Pages -> StepAction(stringResource(R.string.back), true) { vm.back() }
        is BundlePage.Supplier -> {
            // Suppliers left without a category, or put off: decided later, in Review or on Sellers.
            val later = BundleWizard.newSuppliers(plan).count { it.include && (it.category == null || it.category == SupplierChoice.LATER) }
            StepAction(if (later > 0) pluralStringResource(R.plurals.bundle_next_later, later, later) else stringResource(R.string.bundle_next_read), true) { vm.toRead() }
        }
        BundlePage.Read -> {
            val n = BundleWizard.toRead(plan).size
            StepAction(pluralStringResource(R.plurals.bundle_read_button, n, n), n > 0) { vm.read() }
        }
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                BigButton(a.text, null, onClick = a.onClick, enabled = a.enabled)
            }
        }
    }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/** One fact of the file, with a small symbol. */
@Composable
internal fun FactLine(text: String, icon: ImageVector = Icons.Filled.Info, tint: androidx.compose.ui.graphics.Color = Palette.TextDim) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Counts by supplier category key (null: not sorted or decided later), in the tiles' order, each with its symbol. */
@Composable
private fun CategoryCounts(byCategory: Map<String?, Int>) {
    (SupplierCategory.entries.map { it.key } + listOf<String?>(null)).forEach { key ->
        val n = byCategory[key] ?: 0
        val c = SupplierCategory.fromKey(key)
        if (n > 0) FactLine(stringResource(R.string.bundle_done_line, supplierCategoryLabel(c), n), supplierCategoryIcon(c), Palette.PhthaloBright)
    }
}

// ---------------------------------------------------------------------- looking through the file

@Composable
private fun Scanning(plan: BundlePlan, scan: BundleStore.ScanProgress?, onHome: () -> Unit, onStop: () -> Unit) {
    val s = scan ?: BundleStore.ScanProgress(plan.scanned, plan.pageCount, 0)
    Heading(stringResource(R.string.bundle_scan_title))
    LinearProgressIndicator(progress = { if (s.of <= 0) 0f else s.page.toFloat() / s.of }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.bundle_scan_progress, s.page, s.of), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.bundle_scan_found, s.found), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.bundle_scan_leave_ok), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
    BigButton(stringResource(R.string.bundle_stop), null, onClick = onStop, primary = false)
}

// ---------------------------------------------------------------------- step 1: what the file holds

@Composable
private fun Found(plan: BundlePlan, vm: BundleViewModel, onOpenDocument: (Long) -> Unit, onCancel: () -> Unit) {
    val f = BundleWizard.found(plan)
    val nextImport = plan.invoices.count { it.leftOut == LeftOut.NEXT_IMPORT }
    Heading(
        stringResource(
            R.string.bundle_found_title,
            pluralStringResource(R.plurals.bundle_invoices, f.invoices, f.invoices),
            pluralStringResource(R.plurals.bundle_suppliers, f.suppliers, f.suppliers),
        ),
    )
    if (f.first != null && f.last != null) {
        Text(stringResource(R.string.bundle_dates, fmtDate(f.first), fmtDate(f.last)), style = MaterialTheme.typography.titleMedium)
    }
    // Each row only when it has something to say.
    val rows = listOf(f.alreadySaved, f.creditNotes, f.otherBuyer).count { it > 0 }
    Column(
        Modifier.fillMaxWidth().semantics { collectionInfo = CollectionInfo(rows, 1) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (f.alreadySaved > 0) FactLine(pluralStringResource(R.plurals.bundle_already_saved, f.alreadySaved, f.alreadySaved), Icons.Filled.CheckCircle, Palette.Ok)
        if (f.creditNotes > 0) FactLine(pluralStringResource(R.plurals.bundle_credit_notes_left, f.creditNotes, f.creditNotes))
        if (f.otherBuyer > 0) FactLine(pluralStringResource(R.plurals.bundle_other_buyer, f.otherBuyer, f.otherBuyer))
    }
    if (f.unknownPages > 0 || plan.invoices.any { !it.isInvoice }) UnknownPages(plan, vm)
    if (f.needLook > 0) {
        ClickCard(onClick = vm::openPages) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.bundle_need_look, f.needLook, f.needLook), style = MaterialTheme.typography.titleMedium, color = Palette.Orange)
                    Text(stringResource(R.string.bundle_check_starts), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
    if (nextImport > 0) WarningCard(listOf(stringResource(R.string.bundle_too_many, f.invoices, BundleWizard.MAX_INVOICES)))
    plan.sameFileDocumentId?.let { SameFileWarning(it, vm, onOpenDocument) }
    // The page check is there for any file of pages, also when nothing was flagged.
    if (f.needLook == 0 && plan.file.isNotBlank()) {
        TextButton(onClick = vm::openPages, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bundle_check_starts)) }
    }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bundle_cancel_import)) }
}

/** "3 pages are not invoices the app recognises": read them as one document, or leave them out (the default). */
@Composable
private fun UnknownPages(plan: BundlePlan, vm: BundleViewModel) {
    val unknown = plan.invoices.filter { !it.isInvoice }
    val pages = unknown.sumOf { it.pageCount }
    val read = unknown.isNotEmpty() && unknown.all { it.include }
    Panel {
        Column(Modifier.padding(16.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pluralStringResource(R.plurals.bundle_unknown_pages, pages, pages), style = MaterialTheme.typography.titleMedium)
            Choice(stringResource(R.string.bundle_unknown_read_one), selected = read) { vm.readUnknown(true) }
            Choice(stringResource(R.string.bundle_unknown_leave), selected = !read) { vm.readUnknown(false) }
        }
    }
}

/** One of two answers: a 56dp row with a radio mark. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Palette.PhthaloBright else Palette.TextDim,
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** This very file was saved before as one document: reading it again counts its invoices twice (the owner decides). */
@Composable
private fun SameFileWarning(documentId: Long, vm: BundleViewModel, onOpenDocument: (Long) -> Unit) {
    val same by produceState<BundleViewModel.SameFile?>(null, documentId) { value = vm.sameFile(documentId) }
    val s = same ?: return
    WarningCard(listOf(stringResource(R.string.bundle_same_file_saved, fmtDate(s.savedOn), s.lines)))
    TextButton(onClick = { onOpenDocument(s.id) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bundle_open_old)) }
}

// ---------------------------------------------------------------------- the last step: Read

@Composable
private fun ReadStep(plan: BundlePlan, seconds: Long) {
    val sum = BundleWizard.summary(plan)
    val est = BundleWizard.estimate(plan)
    val charging = rememberCharging()
    Heading(stringResource(R.string.bundle_read_summary_title))
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CategoryCounts(sum.byCategory)
            val left = sum.leftOut.values.sum()
            if (left > 0) Text(pluralStringResource(R.plurals.bundle_left_out, left, left), style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            if (sum.creditNotes > 0) Text(pluralStringResource(R.plurals.bundle_credit_notes_left, sum.creditNotes, sum.creditNotes), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
    }
    if (plan.invoices.any { it.leftOut == LeftOut.NEXT_IMPORT }) {
        WarningCard(listOf(stringResource(R.string.bundle_too_many, plan.invoices.count { it.isInvoice }, BundleWizard.MAX_INVOICES)))
    }
    // An honest estimate: at least these will need the operator.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (est.atLeast > 0) {
            Text(pluralStringResource(R.plurals.bundle_estimate_checks, est.atLeast, est.atLeast), style = MaterialTheme.typography.titleMedium)
            if (est.newSuppliers > 0) Bullet(pluralStringResource(R.plurals.bundle_est_new_suppliers, est.newSuppliers, est.newSuppliers))
            if (est.notGoods > 0) Bullet(pluralStringResource(R.plurals.bundle_est_bills, est.notGoods, est.notGoods))
            if (est.flagged > 0) Bullet(pluralStringResource(R.plurals.bundle_est_flagged, est.flagged, est.flagged))
        }
        Text(stringResource(R.string.bundle_estimate_rest), style = MaterialTheme.typography.bodyLarge)
    }
    Text(stringResource(R.string.bundle_time, duration(seconds)), style = MaterialTheme.typography.titleMedium)
    if (seconds > ReadingTime.LONG_SECONDS && charging == false) Annotation(stringResource(R.string.bundle_charge))
}

/**
 * True when the phone is on a charger, false when not, null when Android does not say; looked at again now and then
 * (the charger may be plugged in while the screen is open).
 */
@Composable
private fun rememberCharging(): Boolean? {
    val context = LocalContext.current
    val charging by produceState<Boolean?>(null) {
        while (true) {
            value = ReadingTime.charging(context)
            delay(10_000)
        }
    }
    return charging
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
}

/** "1 h 20 min", "25 min": rounded to 5 minutes once it is long, never less than a minute. */
@Composable
private fun duration(seconds: Long): String {
    val min = ((seconds + 59) / 60).coerceAtLeast(1)
    val rounded = if (min > 10) (min + 4) / 5 * 5 else min
    return if (rounded < 60) stringResource(R.string.bundle_duration_min, rounded.toInt())
    else stringResource(R.string.bundle_duration_h_min, (rounded / 60).toInt(), (rounded % 60).toInt())
}

// ---------------------------------------------------------------------- after Read

@Composable
private fun Preparing(onHome: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(56.dp))
    }
    Heading(stringResource(R.string.bundle_splitting))
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome, primary = false)
}

/**
 * Being read (or paused by Android): the counts from the plan's outcomes, the time still to go (from what reading
 * takes on this phone), and the next invoice to check.
 */
@Composable
private fun Reading(plan: BundlePlan, secondsLeft: Long, vm: BundleViewModel, onReview: (String) -> Unit, onHome: () -> Unit, onStop: () -> Unit) {
    val n = BundleWizard.counts(plan)
    if (n.reading == 0 && n.stopped > 0) {
        // Stopped by the operator, with some still to check.
        Stopped(n.stopped)
    } else {
        Heading(pluralStringResource(R.plurals.bundle_queued_title, n.total, n.total))
        Text(stringResource(R.string.bundle_queued_body), style = MaterialTheme.typography.bodyLarge)
    }
    LinearProgressIndicator(progress = { if (n.total <= 0) 1f else n.read.toFloat() / n.total }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.bundle_card_progress, n.read, n.total), style = MaterialTheme.typography.titleMedium)
    if (plan.status == BundleStatus.PAUSED) Annotation(stringResource(R.string.bundle_paused))
    if (n.reading > 0 && secondsLeft > 0) {
        Text(stringResource(R.string.bundle_time_left, duration(secondsLeft)), style = MaterialTheme.typography.bodyLarge)
        if (secondsLeft > ReadingTime.LONG_SECONDS && rememberCharging() == false) Annotation(stringResource(R.string.bundle_charge))
    }
    // Greyed until the first invoice to check has been read; it lights up by itself. In plan order, never one the app
    // is checking again.
    val next = if (n.toCheck > 0) vm.nextToCheck() else null
    BigButton(stringResource(R.string.bundle_check_next), null, onClick = { next?.let(onReview) }, enabled = next != null)
    if (n.toCheck > 0) {
        Text(pluralStringResource(R.plurals.bundle_card_to_check, n.toCheck, n.toCheck), style = MaterialTheme.typography.bodyMedium, color = Palette.Orange)
    }
    if (n.stopped > 0 && n.reading == 0) BigButton(pluralStringResource(R.plurals.bundle_read_rest, n.stopped, n.stopped), null, onClick = vm::readRest, primary = false)
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome, primary = false)
    if (n.reading > 0) {
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bundle_stop_reading)) }
    }
}

/** "Stopped: 108 not read yet", in orange: the file is not all done. */
@Composable
private fun Stopped(count: Int) {
    Text(
        pluralStringResource(R.plurals.bundle_stopped, count, count),
        style = MaterialTheme.typography.titleLarge,
        color = Palette.Orange,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Done(plan: BundlePlan, vm: BundleViewModel, onClose: () -> Unit, onHome: () -> Unit) {
    val n = BundleWizard.counts(plan)
    // Stopped before every invoice was read: said as it is, never as all done.
    if (n.stopped > 0) Stopped(n.stopped) else Heading(stringResource(R.string.bundle_all_done))
    Text(stringResource(R.string.notif_bundle_done, n.read, n.saved, n.toCheck, n.failed), style = MaterialTheme.typography.titleMedium)
    // What was saved, by what each supplier sells ("Food: 120", "Electricity, gas, water, phone: 6").
    val byCategory = BundleWizard.savedByCategory(plan)
    if (byCategory.isNotEmpty()) {
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { CategoryCounts(byCategory) }
        }
    }
    if (n.stopped > 0) BigButton(pluralStringResource(R.plurals.bundle_read_rest, n.stopped, n.stopped), null, onClick = vm::readRest, primary = false)
    Text(stringResource(R.string.bundle_close_note), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    BigButton(stringResource(R.string.bundle_close), null, onClick = onClose)
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome, primary = false)
}

/** Closed or cancelled: read as one document, all done, or nothing more to do. */
@Composable
private fun Ended(plan: BundlePlan, vm: BundleViewModel, onHome: () -> Unit) {
    when {
        vm.isOneDocument(plan) -> Text(stringResource(R.string.bundle_one_document), style = MaterialTheme.typography.titleMedium)
        plan.status == BundleStatus.CLOSED -> BundleWizard.counts(plan).stopped.let { stopped ->
            if (stopped > 0) Stopped(stopped) else Heading(stringResource(R.string.bundle_all_done))
        }
        else -> Unit
    }
    BigButton(stringResource(R.string.bundle_home), null, onClick = onHome)
}
