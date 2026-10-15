@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.review

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.kitchenreceipts.app.ui.components.Panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import com.kitchenreceipts.app.ui.components.FieldPeekCard
import com.kitchenreceipts.app.ui.components.FieldPeekDialog
import com.kitchenreceipts.app.ui.components.LocalFieldPeek
import com.kitchenreceipts.app.ui.components.PeekImage
import com.kitchenreceipts.app.ui.components.PeekRequest
import com.kitchenreceipts.core.LineChoice
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import com.kitchenreceipts.app.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kitchenreceipts.app.ui.components.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.DocumentPages
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.FieldKind
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.RecognisedTextCard
import com.kitchenreceipts.app.ui.components.ReviewField
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.components.WarningCard
import com.kitchenreceipts.app.ui.components.PriceChangesCard
import com.kitchenreceipts.app.ui.categoryLabel
import com.kitchenreceipts.app.ui.reviewReasonText
import com.kitchenreceipts.app.ui.aiDisagreementText
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.ProductSource
import com.kitchenreceipts.app.ui.duplicateReasonText
import com.kitchenreceipts.app.ui.errorText
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.app.ui.warningText
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DuplicateDetector
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Reconcile
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.ReceiptParser
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.Units
import com.kitchenreceipts.core.VatBasis
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.app.ui.theme.pressScale
import com.kitchenreceipts.core.CardRow
import com.kitchenreceipts.core.CompactRow
import com.kitchenreceipts.core.GroupRow
import com.kitchenreceipts.core.ReviewRow
import com.kitchenreceipts.core.TopRow
import com.kitchenreceipts.core.headerNeedsLook
import com.kitchenreceipts.core.lineRows
import com.kitchenreceipts.core.needsLook
import com.kitchenreceipts.core.nextToCheck
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun ReviewScreen(documentId: Long?, jobId: String?, onBack: () -> Unit, onViewOriginal: () -> Unit, onSaved: (Long, Boolean) -> Unit) {
    val vm = appViewModel(key = "review-${documentId ?: jobId}") { ReviewViewModel(it, documentId, jobId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val sellerNames by vm.sellerNames.collectAsStateWithLifecycle()

    var askDiscard by remember { mutableStateOf(false) }
    var pickerFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, state.autoSaved) } }

    // Which lines are shown in full (see core ReviewRows): those that needed a look when the screen opened stay open
    // while they are corrected; lines that check out fold into one row per run, opened with a tap.
    var keepOpen by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var initialKeys by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var opened by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var openGroups by rememberSaveable { mutableStateOf(listOf<Long>()) }
    LaunchedEffect(state.loading) {
        if (!state.loading && keepOpen == null) {
            val d = state.draft
            keepOpen = d.items.filter { it.needsLook(state.errors, d.lotsPrinted) }.map { it.key }
            initialKeys = d.items.map { it.key }
        }
    }
    fun rowsOf(s: ReviewState): List<ReviewRow> = buildList {
        add(TopRow("preview")); add(TopRow("status"))
        if (s.draft.aiCheck?.let { it.disagreements.isEmpty() && s.isNew } == true) add(TopRow("aiCheck"))
        if (s.reviewReasons.isNotEmpty()) add(TopRow("reasons"))
        if (s.errors.isNotEmpty()) add(TopRow("errors"))
        if (s.saveError != null) add(TopRow("saveError"))
        if (s.draft.warnings.isNotEmpty()) add(TopRow("warnings"))
        add(TopRow(HEADER))
        if (s.isNew) add(TopRow("kind"))
        add(TopRow("itemsTitle"))
        if (s.draft.checks?.let { hasSomethingToSay(it) } == true) add(TopRow("checks"))
        if (s.draft.items.isEmpty()) add(TopRow("noItems"))
        addAll(lineRows(s.draft, s.errors, keepOpen?.toSet(), initialKeys?.toSet(), opened.toSet(), openGroups.toSet()))
        add(TopRow("addItem"))
        // Background information after the lines: what to check comes first.
        if (s.priceChanges.isNotEmpty()) add(TopRow("prices"))
        if (s.recognisedText != null) add(TopRow("recognised"))
        add(TopRow("bottomSpace"))
    }
    val rows = rowsOf(state)

    // "Next to check" and Save bring a card into view and make it blink once, so the eye finds it.
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun goTo(index: Int, key: String) {
        scope.launch {
            listState.animateScrollToItem(index)
            flash = key to System.nanoTime()
        }
    }

    // The part of the photo behind the field being checked (tap a field to see it).
    var peekRequest by remember { mutableStateOf<PeekRequest?>(null) }
    var peekImage by remember { mutableStateOf<PeekImage?>(null) }
    var peekClosed by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<Pair<String, PeekImage>?>(null) }
    // The value's place could not be found on the photo: said (with the original one tap away), not left blank.
    var peekMissing by remember { mutableStateOf(false) }
    LaunchedEffect(peekRequest) {
        val r = peekRequest
        peekMissing = false
        if (r == null) { peekImage = null; return@LaunchedEffect }
        peekClosed = false
        peekImage = runCatching { vm.peek(r.source, r.value) }.getOrNull()
        peekMissing = peekImage == null && state.filePath != null && (r.value.isNotBlank() || !r.source.isNullOrBlank())
    }
    // Scrolling the form by hand means the operator moved on: close the photo strip (and the keyboard), so it never
    // stays on screen over other fields. Scrolls the screen makes by itself (to show the focused field) do not count.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start && (peekRequest != null || peekImage != null)) {
                peekRequest = null
                focusManager.clearFocus()
            }
        }
    }

    // Going back keeps a new document waiting on the home screen, with the corrections made so far; throwing it away
    // is its own button. Leaving the edit of a saved document with changes asks first.
    var askLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (!state.isNew && vm.hasEdits) askLeave = true else onBack() }
    BackHandler(enabled = !state.isNew && !state.loading && state.savedId == null) { leave() }
    if (askLeave) {
        ConfirmDialog(
            title = stringResource(R.string.leave_edit_title),
            text = stringResource(R.string.leave_edit_text),
            confirmLabel = stringResource(R.string.discard),
            onConfirm = { askLeave = false; vm.dropEdits(); onBack() },
            onDismiss = { askLeave = false },
            dismissLabel = stringResource(R.string.keep_editing),
        )
    }
    val reviewSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val restoredText = stringResource(R.string.draft_restored)
    // Said once: not again after turning the phone or coming back from the original.
    LaunchedEffect(state.restored) { if (state.restored) { vm.restoredShown(); reviewSnackbar.showSnackbar(restoredText) } }

    val title = stringResource(if (state.isNew) R.string.review_title else R.string.edit_title)
    val onPeek: (PeekRequest?) -> Unit = remember { { r -> if (r != null) peekRequest = r else if (enlarged == null) peekRequest = null } }
    // A replacement under every value the app could not settle (worked out by the view model as the operator edits).
    val replacements = state.replacements
    val toCheck = rows.count { r -> (r is CardRow && r.item.needsLook(state.errors, state.draft.lotsPrinted)) || (r is TopRow && r.key == HEADER && state.draft.headerNeedsLook(state.errors)) }
    CompositionLocalProvider(LocalFieldPeek provides onPeek, LocalReplacements provides replacements) {
    Box(Modifier.fillMaxSize()) {
    AppScaffold(
        title = title,
        onBack = leave,
        snackbarHostState = reviewSnackbar,
        actions = {
            if (state.isNew && state.fatal == null && !state.loading) {
                IconButton(onClick = { askDiscard = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.discard))
                }
            }
        },
        floatingActionButton = {
            // One tap to the next thing to check, instead of scrolling through the lines that are fine.
            AnimatedVisibility(
                visible = !state.loading && state.fatal == null && toCheck > 0,
                enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
            ) {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = {
                        nextToCheck(rows, listState.firstVisibleItemIndex, state.draft, state.errors, HEADER)?.let { i -> goTo(i, rows[i].key) }
                    },
                    containerColor = LocalStatusColors.current.uncertainBorder,
                    contentColor = com.kitchenreceipts.app.ui.theme.Palette.Black,
                    icon = { Icon(Icons.Filled.KeyboardDoubleArrowDown, contentDescription = null) },
                    text = {
                        androidx.compose.animation.AnimatedContent(toCheck, label = "toCheck") { n ->
                            Text(stringResource(R.string.next_to_check, n), fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!state.loading && state.fatal == null) {
                SaveBar(
                    uncertain = state.draft.uncertainCount,
                    open = openReasons(state.reviewReasons).size,
                    errors = state.errors.size,
                    saving = state.saving,
                    onSave = {
                        vm.requestSave()
                        // To the first row with something to fix (the header or a line), once the list shows the errors.
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            val s = vm.state.value
                            if (s.errors.isEmpty()) return@launch
                            val now = rowsOf(s)
                            val i = if (s.errors.keys.any { !it.startsWith("item:") }) now.indexOfFirst { it.key == HEADER }
                            else now.indexOfFirst { r -> r is CardRow && s.errors.keys.any { it.startsWith("item:${r.item.key}.") } }
                            if (i >= 0) goTo(i, now[i].key)
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.fatal != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                EmptyState(stringResource(R.string.nothing_to_review))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.back)) }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
                    val m = Modifier.animateItem(placementSpec = com.kitchenreceipts.app.ui.theme.Motion.place)
                    val flashing = flash?.takeIf { it.first == row.key }
                    when (row) {
                        is CardRow -> {
                            val item = row.item
                            val needs = item.needsLook(state.errors, state.draft.lotsPrinted)
                            ItemCard(
                                item = item,
                                lotsPrinted = state.draft.lotsPrinted,
                                errors = state.errors,
                                needsLook = needs,
                                flash = flashing?.second,
                                onChange = { f, t -> vm.setItem(item.key, f, t) },
                                onConfirm = { f -> vm.confirmItem(item.key, f) },
                                onUseComputed = { vm.useComputedTotal(item.key) },
                                onPickProduct = { pickerFor = item.key },
                                onRemove = { vm.removeItem(item.key) },
                                onPickChoice = { ch -> vm.pickChoice(item.key, ch) },
                                // A line that checks out folds back into the lines that are fine.
                                onFold = if (needs) null else ({ keepOpen = keepOpen.orEmpty() - item.key; opened = opened - item.key; initialKeys = initialKeys.orEmpty() + item.key }),
                                modifier = m,
                            )
                        }
                        is GroupRow -> CheckedGroup(row, onToggle = {
                            val first = row.keys.first()
                            openGroups = if (row.open) openGroups - first else openGroups + first
                        }, modifier = m)
                        is CompactRow -> CompactLine(row.item, onOpen = { opened = opened + row.item.key }, modifier = m)
                        is TopRow -> Box(m) { TopPart(row.key, state, vm, sellerNames, flashing?.second, onViewOriginal, onPickDate = { showDatePicker = true }) }
                    }
                }
            }
        }
    }
    val shown = peekImage
    val req = peekRequest
    AnimatedVisibility(
        visible = shown != null && req != null && !peekClosed,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 3 },
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
    ) {
        // The last picture stays while it fades out.
        val last = remember { mutableStateOf<Pair<String, PeekImage>?>(null) }
        if (shown != null && req != null) last.value = req.label to shown
        last.value?.let { (label, img) ->
            FieldPeekCard(
                label = label,
                img = img,
                onEnlarge = { enlarged = label to img },
                onClose = { peekClosed = true },
            )
        }
    }
    AnimatedVisibility(
        visible = peekMissing && peekRequest != null && !peekClosed,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.small,
            color = Palette.Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, Palette.HairlineStrong),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.peek_not_found), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { peekClosed = true; onViewOriginal() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.peek_open_original)) }
            }
        }
    }
    }
    }
    enlarged?.let { (label, img) -> FieldPeekDialog(label, img) { enlarged = null } }

    // ---------------------------------------------------------------- dialogs & sheets

    if (askDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_title),
            text = stringResource(R.string.discard_text),
            confirmLabel = stringResource(R.string.discard),
            onConfirm = { askDiscard = false; vm.discard(); onBack() },
            onDismiss = { askDiscard = false },
            dismissLabel = stringResource(R.string.keep_editing),
        )
    }

    state.confirm?.let { c ->
        ConfirmDialog(
            title = stringResource(
                when {
                    c.duplicates.isNotEmpty() -> R.string.possible_duplicate_title
                    c.reasons.isNotEmpty() -> R.string.not_adding_up_title
                    else -> R.string.unchecked_title
                },
            ),
            text = "",
            confirmLabel = stringResource(R.string.save_anyway),
            onConfirm = vm::confirmSave,
            onDismiss = vm::dismissConfirm,
            dismissLabel = stringResource(R.string.go_back_and_check),
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.duplicates.take(3).forEach { d ->
                        Text(
                            "• ${d.row.sellerName} · ${fmtDate(d.row.documentDate)}" +
                                (d.row.documentNumber?.let { " · " + stringResource(R.string.doc_number_short, it) } ?: "") + " · ${fmtMoney(d.row.totalCents)}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(d.match.reasons.map { duplicateReasonText(it) }.joinToString("; "), style = MaterialTheme.typography.bodyMedium)
                    }
                    c.reasons.forEach { Text("• " + reviewReasonText(it)) }
                    if (c.uncertainCount > 0) {
                        Text(pluralStringResource(R.plurals.unchecked_values, c.uncertainCount, c.uncertainCount))
                    }
                }
            },
        )
    }

    if (showDatePicker) {
        val initial = ItalianDates.parse(state.draft.date.text)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        vm.setDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = pickerState) }
    }

    pickerFor?.let { key ->
        val item = state.draft.items.firstOrNull { it.key == key }
        if (item == null) {
            LaunchedEffect(key) { pickerFor = null } // the line was removed: close the picker after this frame
        } else {
            ProductPickerSheet(
                description = item.description.text,
                currentProductId = item.productId,
                products = products,
                onPick = { vm.assignProduct(key, it); pickerFor = null },
                onCreate = { name -> vm.createProductAndAssign(key, name); pickerFor = null },
                onClear = { vm.assignProduct(key, null); pickerFor = null },
                onDismiss = { pickerFor = null },
            )
        }
    }
}

@Composable
private fun ReasonsCard(state: ReviewState) {
    val status = LocalStatusColors.current
    Surface(color = status.uncertainContainer, contentColor = status.onUncertain, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, com.kitchenreceipts.app.ui.theme.Palette.OrangeDim)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(R.string.reasons_title), fontWeight = FontWeight.SemiBold)
            state.reviewReasons.forEach { Text("• " + reviewReasonText(it), style = MaterialTheme.typography.bodyMedium) }
            state.draft.aiCheck?.takeIf { it.disagreements.isNotEmpty() }?.let { c ->
                Text(stringResource(R.string.ai_check_disagree, c.disagreements.size, c.checked), style = MaterialTheme.typography.bodyMedium)
                // Worded in the operator's language (older readings without details keep the log's wording).
                if (c.details.isNotEmpty()) c.details.forEach { Text("   " + aiDisagreementText(it), style = MaterialTheme.typography.bodyMedium) }
                else c.disagreements.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
            }
            // Which VAT group does not add up, and by how much: that is where the misread line is.
            state.draft.vatGroupProblems.forEach { g ->
                Text(
                    "   " + stringResource(
                        R.string.vat_group_line,
                        ItalianNumbers.formatDecimal(g.ratePercent),
                        ItalianNumbers.formatCents(g.linesCents),
                        ItalianNumbers.formatCents(g.printedCents),
                        ItalianNumbers.formatCents(kotlin.math.abs(g.differenceCents)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun hasSomethingToSay(c: Reconcile.Report) =
    c.vatProven || c.changed || c.packagesPrinted != null || c.pagesMissing.isNotEmpty()

/**
 * What the document's own sums prove about the reading: every VAT rate adds up, lines recovered or left out to make
 * them add up, packages against the printed total (packages are not lines), pages not photographed.
 */
@Composable
private fun ChecksPanel(c: Reconcile.Report) {
    val status = LocalStatusColors.current
    Panel {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.checks_title), fontWeight = FontWeight.SemiBold)
            if (c.vatProven) Text("✓ " + stringResource(R.string.checks_vat_proven), color = status.ok, style = MaterialTheme.typography.bodyMedium)
            if (c.recovered.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.checks_recovered, c.recovered.size, c.recovered.size), style = MaterialTheme.typography.bodyMedium)
                c.recovered.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (c.dropped.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.checks_dropped, c.dropped.size, c.dropped.size), style = MaterialTheme.typography.bodyMedium)
                c.dropped.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (c.ratesFound > 0) Text(pluralStringResource(R.plurals.checks_rates, c.ratesFound, c.ratesFound), style = MaterialTheme.typography.bodyMedium)
            c.packagesPrinted?.let { printed ->
                if (c.packagesAgree) {
                    Text("✓ " + stringResource(R.string.checks_packages_ok, printed), color = status.ok, style = MaterialTheme.typography.bodyMedium)
                } else c.packagesCounted?.let { counted ->
                    Text(stringResource(R.string.checks_packages_differ, counted, printed), color = status.uncertainBorder, style = MaterialTheme.typography.bodyMedium)
                }
            }
            c.pagesOf?.takeIf { c.pagesMissing.isNotEmpty() }?.let { of ->
                Text(
                    stringResource(R.string.checks_pages_missing, c.pagesMissing.joinToString(", "), of),
                    color = if (c.vatProven) MaterialTheme.colorScheme.onSurfaceVariant else status.uncertainBorder,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(state: ReviewState) {
    val lines = buildList {
        if (state.isNew) {
            when {
                // The technical cause stays in the reading report (Recognised text), not in the operator's way.
                state.ocrError != null -> add(stringResource(R.string.ocr_failed))
                state.pagesRead > 0 && state.draft.items.isEmpty() && state.draft.total.isMissing ->
                    add(stringResource(R.string.ocr_nothing_found))
            }
            if (state.pageCount > state.pagesRead && state.ocrError == null && state.pagesRead > 0) {
                add(stringResource(R.string.ocr_pages_limited, state.pagesRead, state.pageCount))
            }
        }
    }
    if (lines.isNotEmpty()) WarningCard(lines)
    state.recognition?.let { r ->
        val how = stringResource(
            when (r.match.reason) {
                SellerMatchReason.VAT_NUMBER -> R.string.recognised_by_vat
                SellerMatchReason.NAME_ALIAS -> R.string.recognised_by_alias
                SellerMatchReason.LAYOUT -> R.string.recognised_by_layout
            },
        )
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, com.kitchenreceipts.app.ui.theme.Palette.PhthaloBorder)) {
            Text(
                pluralStringResource(R.plurals.recognised_supplier, r.documentCount, r.match.name, how, r.documentCount) +
                    (if (r.usualVatBasis != null) " " + stringResource(R.string.usual_vat_basis, vatBasisLabel(r.usualVatBasis)) else ""),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val uncertain = state.draft.uncertainCount
    if (uncertain > 0) {
        val status = LocalStatusColors.current
        Surface(color = status.uncertainContainer, contentColor = status.onUncertain, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, com.kitchenreceipts.app.ui.theme.Palette.OrangeDim)) {
            Text(
                pluralStringResource(R.plurals.values_to_check, uncertain, uncertain),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (state.engineName != null && state.ocrError == null) {
        Text(
            stringResource(R.string.read_by, state.engineName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun KindRow(kind: com.kitchenreceipts.core.DocKind?, onPick: (com.kitchenreceipts.core.DocKind?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.kind_title), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                com.kitchenreceipts.core.DocKind.INVOICE to R.string.kind_invoice,
                com.kitchenreceipts.core.DocKind.DELIVERY_NOTE to R.string.kind_delivery_note,
                com.kitchenreceipts.core.DocKind.RECEIPT to R.string.kind_receipt,
                com.kitchenreceipts.core.DocKind.CREDIT_NOTE to R.string.kind_credit_note,
            ).forEach { (k, label) ->
                androidx.compose.material3.FilterChip(
                    selected = kind == k,
                    onClick = { onPick(if (kind == k) null else k) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (kind == null) Text(stringResource(R.string.kind_unknown), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.TextDim)
        if (kind == com.kitchenreceipts.core.DocKind.CREDIT_NOTE) {
            Text(stringResource(R.string.kind_credit_note_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
        }
    }
}

@Composable
private fun HeaderSection(
    draft: DocumentDraft,
    errors: Map<String, com.kitchenreceipts.core.ErrorCode>,
    sellerNames: List<String>,
    vm: ReviewViewModel,
    onPickDate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.document_section))

        HeaderInput(draft, HeaderField.SELLER, R.string.seller, errors, vm)
        // Existing sellers that match what was typed: one tap to use the same spelling.
        // (A doubtful supplier name gets its replacements under the field itself.)
        val matches = remember(draft.seller.text, draft.seller.uncertain, sellerNames) {
            val typed = DuplicateDetector.normalizeSeller(draft.seller.text)
            if (typed == null || draft.seller.uncertain) emptyList() else sellerNames.filter {
                val n = DuplicateDetector.normalizeSeller(it) ?: ""
                it != draft.seller.text && (n.contains(typed) || typed.contains(n)) && n.isNotEmpty()
            }.take(3)
        }
        if (matches.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { name -> AssistChip(onClick = { vm.setHeader(HeaderField.SELLER, name) }, label = { Text(name) }) }
            }
        }

        HeaderInput(draft, HeaderField.DATE, R.string.date, errors, vm, FieldKind.DATE, placeholder = stringResource(R.string.date_placeholder), trailing = {
            IconButton(onClick = onPickDate, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.pick_date))
            }
        })
        HeaderInput(draft, HeaderField.NUMBER, R.string.document_number, errors, vm, FieldKind.CODE)

        Text(stringResource(R.string.prices_are), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        val status = LocalStatusColors.current
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(VatBasis.EXCLUSIVE, VatBasis.INCLUSIVE, VatBasis.UNKNOWN)
            options.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = draft.vatBasis == v,
                    onClick = { vm.setVatBasis(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(vatBasisLabel(v), maxLines = 2) }
            }
        }
        if (draft.vatBasisUncertain) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vat_basis_guessed), color = status.onUncertain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::confirmVatBasis, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text(stringResource(R.string.confirm_value))
                }
            }
        }

        HeaderInput(draft, HeaderField.CURRENCY, R.string.currency, errors, vm, FieldKind.CODE, placeholder = "EUR")
        if (draft.currency.isMissing) {
            AssistChip(onClick = { vm.setHeader(HeaderField.CURRENCY, "EUR") }, label = { Text(stringResource(R.string.set_eur)) })
        }
        HeaderInput(draft, HeaderField.SUBTOTAL, R.string.subtotal, errors, vm, FieldKind.DECIMAL)
        HeaderInput(draft, HeaderField.VAT, R.string.vat_amount, errors, vm, FieldKind.DECIMAL)
        HeaderInput(draft, HeaderField.TOTAL, R.string.total, errors, vm, FieldKind.DECIMAL)

        // Live cross-check to catch misread amounts quickly.
        val totals = draft.items.map { ItalianNumbers.parseCents(it.lineTotal.text) }
        if (totals.isNotEmpty() && totals.all { it != null }) {
            val sum = totals.sumOf { it!! }
            val sub = ItalianNumbers.parseCents(draft.subtotal.text)
            val tot = ItalianNumbers.parseCents(draft.total.text)
            val ok = listOfNotNull(sub, tot).any { kotlin.math.abs(it - sum) <= maxOf(2, totals.size.toLong()) }
            val known = sub != null || tot != null
            Text(
                stringResource(if (!known || ok) R.string.lines_sum else R.string.lines_sum_mismatch, fmtMoney(sum, draft.currency.text.ifBlank { "EUR" })),
                color = if (!known || ok) MaterialTheme.colorScheme.onSurfaceVariant else status.uncertainBorder,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Key of the header's row (supplier, date, number, totals). */
private const val HEADER = "header"

/** The fixed parts of the review list, by row key (see [ReviewRow]). */
@Composable
private fun TopPart(
    key: String,
    state: ReviewState,
    vm: ReviewViewModel,
    sellerNames: List<String>,
    flash: Long?,
    onViewOriginal: () -> Unit,
    onPickDate: () -> Unit,
) {
    when (key) {
        "preview" -> {
            val path = state.filePath
            val mime = state.mimeType
            if (path != null && mime != null) {
                Panel {
                    DocumentPages(path, mime, state.pageCount, Modifier.fillMaxWidth().height(220.dp), onClick = onViewOriginal)
                    TextButton(onClick = onViewOriginal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.view_original_full))
                    }
                }
            }
        }
        "status" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { StatusBanner(state) }
        "aiCheck" -> state.draft.aiCheck?.let { c ->
            Text(pluralStringResource(R.plurals.ai_check_ok, c.checked, c.checked), color = LocalStatusColors.current.ok, style = MaterialTheme.typography.bodyMedium)
        }
        "reasons" -> ReasonsCard(state)
        "checks" -> state.draft.checks?.let { ChecksPanel(it) }
        "errors" -> WarningCard(listOf(stringResource(R.string.fix_errors, state.errors.size)))
        "saveError" -> state.saveError?.let { err -> WarningCard(listOf(stringResource(R.string.save_failed, err))) }
        "warnings" -> WarningCard(state.draft.warnings.map { warningText(it) })
        HEADER -> FlashPanel(flash = flash, needsLook = state.draft.headerNeedsLook(state.errors)) {
            Box(Modifier.padding(12.dp)) { HeaderSection(state.draft, state.errors, sellerNames, vm, onPickDate) }
        }
        // What kind of document this is decides where it counts (a credit note is subtracted from spending):
        // shown before saving, one tap to change.
        "kind" -> KindRow(state.kind, vm::setKind)
        "itemsTitle" -> {
            val n = state.draft.items.size
            val open = state.draft.items.count { it.needsLook(state.errors, state.draft.lotsPrinted) }
            Column {
                SectionTitle(pluralStringResource(R.plurals.items_title, n, n))
                if (n > 0) {
                    Text(
                        if (open == 0) stringResource(R.string.lines_all_fine) else pluralStringResource(R.plurals.lines_to_check, open, open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (open == 0) LocalStatusColors.current.ok else LocalStatusColors.current.uncertainBorder,
                    )
                }
            }
        }
        "noItems" -> EmptyState(stringResource(R.string.no_items_hint))
        "addItem" -> OutlinedButton(onClick = vm::addItem, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_item))
        }
        "prices" -> PriceChangesCard(state.priceChanges)
        "recognised" -> state.recognisedText?.let { t -> RecognisedTextCard(t) }
        else -> Spacer(Modifier.height(8.dp))
    }
}

/**
 * A card that shows whether it needs a look (orange edge) and blinks once when "Next to check" or Save brings it
 * into view ([flash] changes each time).
 */
@Composable
private fun FlashPanel(flash: Long?, needsLook: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == null) return@LaunchedEffect
        glow.snapTo(1f)
        glow.animateTo(0f, androidx.compose.animation.core.tween(durationMillis = 1400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val status = LocalStatusColors.current
    val edge by androidx.compose.animation.animateColorAsState(
        if (needsLook) Palette.OrangeDim else Palette.Hairline, androidx.compose.animation.core.tween(400), label = "edge",
    )
    val border = androidx.compose.ui.graphics.lerp(edge, Palette.PhthaloBright, glow.value)
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(com.kitchenreceipts.app.ui.theme.Motion.size),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Palette.Panel, contentColor = MaterialTheme.colorScheme.onSurface),
        border = androidx.compose.foundation.BorderStroke(if (glow.value > 0f) 2.dp else 1.dp, border),
    ) {
        Box(Modifier.fillMaxWidth().drawBehind { if (glow.value > 0f) drawRect(status.ok.copy(alpha = 0.10f * glow.value)) }) { content() }
    }
}

/** Lines in a row that check out, folded: "12 lines check out · 234,50 €", a tap shows them. */
@Composable
private fun CheckedGroup(row: GroupRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val turn by androidx.compose.animation.core.animateFloatAsState(if (row.open) 180f else 0f, label = "chevron")
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onToggle,
        interactionSource = source,
        modifier = modifier.pressScale(source).fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = Palette.Phthalo.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.PhthaloBorder.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = status.ok)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.lines_check_out, row.keys.size, row.keys.size), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (row.open) R.string.lines_tap_to_fold else R.string.lines_tap_to_see),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.totalCents?.let { Text(ItalianNumbers.formatCents(it), style = MaterialTheme.typography.titleSmall) }
            Icon(
                Icons.Filled.ExpandMore, contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).graphicsLayer { rotationZ = turn },
            )
        }
    }
}

/** One line that checks out, in one row: name, "2 pz × 5,00", lot, amount. A tap opens it in full. */
@Composable
private fun CompactLine(item: LineItemDraft, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        interactionSource = source,
        modifier = modifier.pressScale(source).fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp),
        shape = MaterialTheme.shapes.small,
        color = Palette.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Hairline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.description.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val detail = listOfNotNull(
                    listOf(item.quantity.text, item.unit.text).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
                        ?.let { q -> item.unitPrice.text.takeIf { it.isNotBlank() }?.let { "$q × $it" } ?: q },
                    item.lot.text.takeIf { it.isNotBlank() }?.let { stringResource(R.string.lots_lot, it) },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(item.lineTotal.text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ItemCard(
    item: LineItemDraft,
    lotsPrinted: Boolean,
    errors: Map<String, com.kitchenreceipts.core.ErrorCode>,
    needsLook: Boolean,
    flash: Long?,
    onChange: (ItemField, String) -> Unit,
    onConfirm: (ItemField) -> Unit,
    onUseComputed: () -> Unit,
    onPickProduct: () -> Unit,
    onRemove: () -> Unit,
    onPickChoice: (LineChoice) -> Unit = {},
    /** Folds a line that checks out back into the lines that are fine; null while it needs a look. */
    onFold: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    FlashPanel(flash = flash, needsLook = needsLook, modifier = modifier) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A line checked: said, with one tap to fold it away.
        AnimatedVisibility(visible = onFold != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = status.ok, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.line_checks_out), color = status.ok, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onFold?.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.line_fold)) }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            ItemInput(item, errors, onChange, onConfirm, ItemField.DESCRIPTION, R.string.description_on_document, Modifier.weight(1f))
            IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_item))
            }
        }
        if (item.isCharge) {
            // A discount or charge: part of the document's total, never a product (no quantity, unit or price history).
            Text(stringResource(R.string.charge_line_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemInput(item, errors, onChange, onConfirm, ItemField.LINE_TOTAL, R.string.line_total, Modifier.weight(1f), FieldKind.DECIMAL)
                ItemInput(item, errors, onChange, onConfirm, ItemField.VAT_RATE, R.string.vat_rate, Modifier.weight(0.6f), FieldKind.DECIMAL, optional = true)
            }
            TextButton(onClick = onPickProduct, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.charge_line_is_product)) }
        } else {
        // Product link: chosen by the app only when safe (remembered / recognised / new), always shown and changeable.
        val linkedName = item.productName
        val newName = item.newProductName
        val productLabel = when {
            linkedName != null -> stringResource(R.string.product_is, linkedName)
            newName != null -> stringResource(R.string.new_product_is, newName)
            else -> stringResource(R.string.assign_product)
        }
        AssistChip(
            onClick = onPickProduct,
            label = { Text(productLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        item.productSource?.let { src ->
            Text(
                stringResource(
                    when (src) {
                        ProductSource.REMEMBERED -> R.string.product_remembered
                        ProductSource.RECOGNISED -> R.string.product_recognised
                        ProductSource.NEW -> R.string.product_new
                    },
                ) + ((linkedName ?: newName)?.let { " · " + categoryLabel(Categories.guess(it)) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (newName != null) {
            item.newProductBrand?.let { b -> Text(stringResource(R.string.brand_is, b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Abbreviations the app could not write out: the operator's name for them is learned on save.
            if (item.nameUnknown.isNotEmpty()) {
                Text(stringResource(R.string.name_unknown, item.nameUnknown.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = status.uncertainBorder)
            }
        }
        // What the page's own codes say about the line ("Storage: F (fresco)"), read from its legend.
        if (item.marks.isNotEmpty()) {
            Text(marksLine(item.marks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemInput(item, errors, onChange, onConfirm, ItemField.QUANTITY, R.string.quantity, Modifier.weight(1f), FieldKind.DECIMAL)
            ItemInput(item, errors, onChange, onConfirm, ItemField.UNIT, R.string.unit, Modifier.weight(0.8f))
        }
        if (item.unit.isMissing || Units.normalizeKnown(item.unit.text) == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("kg", "pz", "conf", "l").forEach { u ->
                    AssistChip(onClick = { onChange(ItemField.UNIT, u) }, label = { Text(u) })
                }
            }
        }
        // Two wide fields: a value with a ✓ to tap and its note under it stay readable on a phone.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemInput(item, errors, onChange, onConfirm, ItemField.UNIT_PRICE, R.string.unit_price, Modifier.weight(1f), FieldKind.DECIMAL)
            ItemInput(item, errors, onChange, onConfirm, ItemField.LINE_TOTAL, R.string.line_total, Modifier.weight(1f), FieldKind.DECIMAL)
        }
        // The numbers add up in more than one way: one tap picks the right reading (remembered for this supplier).
        if (item.choices.size >= 2) {
            Text(stringResource(R.string.choice_title), style = MaterialTheme.typography.bodyMedium, color = status.uncertainBorder)
            item.choices.forEachIndexed { i, ch ->
                val qty = ItalianNumbers.formatDecimal(ch.quantity, maxScale = 4) + (ch.unit?.let { " $it" } ?: "")
                val text = stringResource(
                    R.string.choice_option, qty, ItalianNumbers.formatDecimal(ch.unitPrice, minScale = 2, maxScale = 4),
                    ItalianNumbers.formatCents(ch.lineTotalCents),
                ) + if (i == 0 && ItalianNumbers.parse(item.quantity.text)?.compareTo(ch.quantity) == 0) " · " + stringResource(R.string.choice_suggested) else ""
                OutlinedButton(onClick = { onPickChoice(ch) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
            }
        }
        // qty x price hint: offered, never applied without a tap.
        val computed = item.computedTotalCents()
        if (item.addsUp() == false) {
            Text(
                stringResource(R.string.line_mismatch, ItalianNumbers.formatCents(computed ?: 0)),
                color = status.uncertainBorder,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (computed != null) {
                AssistChip(
                    onClick = onUseComputed,
                    label = { Text(stringResource(R.string.use_computed_total, ItalianNumbers.formatCents(computed))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A document that prints no lots at all: an empty lot is normal, not something to fill in.
            ItemInput(
                item, errors, onChange, onConfirm, ItemField.LOT, R.string.lot, Modifier.weight(1f), FieldKind.CODE,
                missingHint = stringResource(R.string.lot_missing_hint), optional = !lotsPrinted,
            )
            ItemInput(item, errors, onChange, onConfirm, ItemField.VAT_RATE, R.string.vat_rate, Modifier.weight(0.6f), FieldKind.DECIMAL, optional = true)
        }
        // Pack size, discount, packages and expiry: shown when they hold something or need a look, else one tap away.
        val extras = listOf(ItemField.PACK_SIZE, ItemField.DISCOUNT, ItemField.PACKAGES, ItemField.EXPIRY)
        val used = extras.any { f -> item.field(f).let { !it.isMissing || it.uncertain } || errors.containsKey(ReviewViewModel.itemErrorKey(item.key, f.key)) }
        var more by rememberSaveable(item.key) { mutableStateOf(false) }
        AnimatedVisibility(visible = used || more) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // "4 pz × 500 g = 2 kg": what the packs hold, for inventory and price comparisons.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ItemInput(item, errors, onChange, onConfirm, ItemField.PACK_SIZE, R.string.pack_size, Modifier.weight(1f), FieldKind.TEXT, optional = true)
                    Box(Modifier.weight(1f)) {
                        item.packTotal()?.let { (amount, u) ->
                            Text(stringResource(R.string.pack_total, ItalianNumbers.formatDecimal(amount, maxScale = 3), u), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The discount printed on the line ("30", "10+5"): quantity x price less it is the amount.
                    ItemInput(item, errors, onChange, onConfirm, ItemField.DISCOUNT, R.string.discount_short, Modifier.weight(1f), FieldKind.CODE, optional = true)
                    ItemInput(item, errors, onChange, onConfirm, ItemField.PACKAGES, R.string.packages, Modifier.weight(1f), FieldKind.CODE, optional = true)
                }
                ItemInput(item, errors, onChange, onConfirm, ItemField.EXPIRY, R.string.expiry, Modifier.fillMaxWidth(), kind = FieldKind.DATE, optional = true)
            }
        }
        if (!used) {
            val turn by androidx.compose.animation.core.animateFloatAsState(if (more) 180f else 0f, label = "more")
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (more) R.string.fewer_details else R.string.more_details))
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
        }
        }
    }
    }
}

@Composable
private fun SaveBar(uncertain: Int, open: Int, errors: Int, saving: Boolean, onSave: () -> Unit) {
    // Black bar with a hairline on top: the save button stands out on its own.
    Surface(color = MaterialTheme.colorScheme.background) { Column {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The count changes as the operator checks values: it slides to the new one.
            val label = when {
                errors > 0 -> 0 to errors
                uncertain > 0 -> 1 to uncertain
                // "All checked" only when nothing else is wrong either (the lines add up, date and total are there...).
                open > 0 -> 2 to open
                else -> 3 to 0
            }
            androidx.compose.animation.AnimatedContent(
                label,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut())
                },
                label = "saveStatus",
            ) { (kind, n) ->
                when (kind) {
                    0 -> Text(pluralStringResource(R.plurals.errors_count, n, n), color = MaterialTheme.colorScheme.error)
                    1 -> Text(pluralStringResource(R.plurals.values_to_check_short, n, n), color = LocalStatusColors.current.uncertainBorder)
                    2 -> Text(pluralStringResource(R.plurals.things_to_look_at, n, n), color = LocalStatusColors.current.uncertainBorder)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = LocalStatusColors.current.ok, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.all_checked), color = LocalStatusColors.current.ok)
                    }
                }
            }
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.heightIn(min = 56.dp)) {
                if (saving) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Filled.Save, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
            }
        }
    } }
}
/** Replacements of the doubtful values on screen, by field key (see core Replacements). */
// Not static: a change of the replacements recomposes only the fields that read them, not the whole screen.
private val LocalReplacements = androidx.compose.runtime.compositionLocalOf<Map<String, List<String>>> { emptyMap() }

@Composable
private fun HeaderInput(
    draft: DocumentDraft,
    f: HeaderField,
    label: Int,
    errors: Map<String, com.kitchenreceipts.core.ErrorCode>,
    vm: ReviewViewModel,
    kind: FieldKind = FieldKind.TEXT,
    placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ReviewField(
        label = stringResource(label),
        field = draft.header(f),
        onChange = { vm.setHeader(f, it) },
        onConfirm = { vm.confirmHeader(f) },
        kind = kind,
        error = errors[f.key]?.let { errorText(it) },
        placeholder = placeholder,
        trailing = trailing,
        replacements = LocalReplacements.current[com.kitchenreceipts.core.Replacements.header(f.key)].orEmpty(),
    )
}

@Composable
private fun ItemInput(
    item: LineItemDraft,
    errors: Map<String, com.kitchenreceipts.core.ErrorCode>,
    onChange: (ItemField, String) -> Unit,
    onConfirm: (ItemField) -> Unit,
    field: ItemField,
    label: Int,
    modifier: Modifier = Modifier,
    kind: FieldKind = FieldKind.TEXT,
    missingHint: String? = null,
    optional: Boolean = false,
) {
    ReviewField(
        label = stringResource(label),
        field = item.field(field),
        onChange = { onChange(field, it) },
        onConfirm = { onConfirm(field) },
        modifier = modifier,
        kind = kind,
        error = errors[ReviewViewModel.itemErrorKey(item.key, field.key)]?.let { errorText(it) },
        missingHint = missingHint,
        highlightMissing = !optional,
        replacements = LocalReplacements.current[com.kitchenreceipts.core.Replacements.item(item.key, field.key)].orEmpty(),
    )
}

/** "Conservazione: F (fresco) · Gruppo: Merce non alimentare": the page's codes for a line, in the app's language. */
@Composable
internal fun marksLine(marks: List<com.kitchenreceipts.core.ItemMark>): String {
    val kinds = mapOf("storage" to stringResource(R.string.mark_storage), "group" to stringResource(R.string.mark_group), "code" to stringResource(R.string.mark_code))
    return marks.joinToString(" · ") { m -> (kinds[m.kind] ?: m.kind.replaceFirstChar { it.uppercase() }) + ": " + (m.code?.let { "$it (${m.meaning})" } ?: m.meaning) }
}
