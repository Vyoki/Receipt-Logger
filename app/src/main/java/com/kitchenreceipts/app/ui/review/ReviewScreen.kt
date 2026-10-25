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
import com.kitchenreceipts.app.ui.components.CategoryPickerSheet
import com.kitchenreceipts.app.ui.components.CategoryPill
import com.kitchenreceipts.app.ui.components.FastScrollbar
import com.kitchenreceipts.app.ui.components.FastScrollbarDefaults
import com.kitchenreceipts.app.ui.components.JumpChip
import com.kitchenreceipts.app.ui.components.JumpChips
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import com.kitchenreceipts.core.SupplierCategory
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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

/**
 * The check of a document before it is saved, or the edit of a saved one. An invoice of a file of many is checked in
 * that file's chain: "Invoice 3 of 25 to check" under the top bar, "Check later", and after Save the next one opens
 * by itself ([onChain]: the next job, or null for the file's list).
 */
@Composable
fun ReviewScreen(
    documentId: Long?,
    jobId: String?,
    onBack: () -> Unit,
    onViewOriginal: () -> Unit,
    onSaved: (Long, Boolean) -> Unit,
    onChain: (next: String?, planId: String) -> Unit = { _, _ -> },
) {
    val vm = appViewModel(key = "review-${documentId ?: jobId}") { ReviewViewModel(it, documentId, jobId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val notFoodProducts by vm.notFoodProducts.collectAsStateWithLifecycle()
    val sellerNames by vm.sellerNames.collectAsStateWithLifecycle()
    val chain by vm.chain.collectAsStateWithLifecycle()

    var askDiscard by remember { mutableStateOf(false) }
    var pickerFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // The supplier's category tiles, with "For all invoices from this supplier" (the default) or "Just this invoice".
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    var categoryForAll by rememberSaveable { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // An invoice of a file of many goes on in its chain instead (below).
    LaunchedEffect(state.savedId) { if (vm.bundleId == null) state.savedId?.let { onSaved(it, state.autoSaved) } }

    // Which lines are shown in full (see core ReviewRows): those that needed a look when the screen opened stay open
    // while they are corrected; lines that check out fold into one row per run, opened with a tap.
    var keepOpen by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var initialKeys by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    var opened by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var openGroups by rememberSaveable { mutableStateOf(listOf<Long>()) }
    // A non-food bill's lines, folded into one row until tapped.
    var notFoodOpen by rememberSaveable { mutableStateOf(false) }
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
        add(TopRow(CATEGORY))
        add(TopRow(ITEMS_TITLE))
        if (s.draft.checks?.let { hasSomethingToSay(it) } == true) add(TopRow("checks"))
        if (s.draft.items.isEmpty()) add(TopRow("noItems"))
        // Electricity, fuel and other non-food bills: their lines in one row, so only the supplier, date, number and
        // totals are checked. Open on a tap, or when a line has an error to fix. Nothing about them is held differently.
        val notFood = s.supplierCategory?.goods == false && s.draft.items.isNotEmpty()
        val folded = notFood && !notFoodOpen && s.errors.keys.none { it.startsWith("item:") }
        if (notFood) add(TopRow(NOT_FOOD))
        if (!folded) {
            addAll(lineRows(s.draft, s.errors, keepOpen?.toSet(), initialKeys?.toSet(), opened.toSet(), openGroups.toSet()))
            add(TopRow("addItem"))
        }
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

    // The chain: after a save, "Saved ✓ · next: <supplier>" for about 2 s, then the next invoice (or the file's list).
    val savedNext = state.chainMove?.let { m -> stringResource(R.string.bundle_saved_next, m.nextSupplier ?: "…") }
    LaunchedEffect(state.chainMove) {
        val m = state.chainMove ?: return@LaunchedEffect
        if (m.saved && m.next != null) {
            launch { reviewSnackbar.showSnackbar(savedNext ?: "") }
            kotlinx.coroutines.delay(CHAIN_PAUSE_MS)
        }
        onChain(m.next, m.planId)
    }
    // "5 more from this supplier saved by the app", once, while this invoice is still to be checked.
    val rechecked by vm.rechecked.collectAsStateWithLifecycle()
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    LaunchedEffect(rechecked, state.savedId) {
        val r = rechecked ?: return@LaunchedEffect
        if (r.planId != vm.bundleId || state.savedId != null) return@LaunchedEffect
        vm.recheckedShown(r)
        reviewSnackbar.showSnackbar(res.getQuantityString(R.plurals.bundle_rechecked, r.count, r.count))
    }

    val title = stringResource(if (state.isNew) R.string.review_title else R.string.edit_title)
    val onPeek: (PeekRequest?) -> Unit = remember { { r -> if (r != null) peekRequest = r else if (enlarged == null) peekRequest = null } }
    // A replacement under every value the app could not settle (worked out by the view model as the operator edits).
    val replacements = state.replacements
    val toCheck = rows.count { r -> (r is CardRow && r.item.needsLook(state.errors, state.draft.lotsPrinted)) || (r is TopRow && r.key == HEADER && state.draft.headerNeedsLook(state.errors)) }

    // The fast scroller's bubble and TalkBack: what the row at a place is ("Document", "Line 34 of 80", "12 lines ✓").
    // Worked out here, read by the scroller only while it is dragged (no scroll position is read in this screen).
    val labelPages = stringResource(R.string.jump_pages)
    val labelTop = stringResource(R.string.jump_top)
    val labelDocument = stringResource(R.string.document_section)
    val labelLines = stringResource(R.string.jump_lines)
    val labelTotals = stringResource(R.string.jump_totals)
    val labelNext = stringResource(R.string.jump_next_check)
    val labelPrices = stringResource(R.string.price_changes)
    val labelText = stringResource(R.string.recognised_text)
    val labelBack = stringResource(R.string.jump_back)
    val lineNo = remember(state.draft.items) { state.draft.items.withIndex().associate { (i, item) -> item.key to i + 1 } }
    val lineCount = state.draft.items.size
    fun labelAt(i: Int): String? = when (val r = rows.getOrNull(i)) {
        null -> null
        is CardRow -> res.getString(R.string.scroll_line, lineNo[r.item.key] ?: 0, lineCount)
        is CompactRow -> res.getString(R.string.scroll_line, lineNo[r.item.key] ?: 0, lineCount)
        is GroupRow -> res.getQuantityString(R.plurals.scroll_lines_ok, r.keys.size, r.keys.size)
        is TopRow -> when (r.key) {
            "preview" -> labelPages
            HEADER, "kind", CATEGORY -> labelDocument
            ITEMS_TITLE, "checks", "noItems", NOT_FOOD, "addItem" -> labelLines
            "prices" -> labelPrices
            "recognised" -> labelText
            "bottomSpace" -> if (i > 0) labelAt(i - 1) else labelTop
            else -> labelTop
        }
    }

    // Jumps (the chips, the scroller and TalkBack's actions): afterwards, "Back to where you were" for 5 s, or as long
    // as the phone's accessibility settings ask for messages with a button (TalkBack users get longer).
    var backOffer by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val backOfferMs = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(BACK_OFFER_MS, containsText = true, containsControls = true) ?: BACK_OFFER_MS
    }
    fun here() = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    fun offerBack(from: Pair<Int, Int>, now: String?) {
        backOffer?.cancel()
        backOffer = scope.launch {
            val r = kotlinx.coroutines.withTimeoutOrNull(backOfferMs) {
                reviewSnackbar.showSnackbar(now ?: labelBack, actionLabel = labelBack, duration = androidx.compose.material3.SnackbarDuration.Indefinite)
            }
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) listState.animateScrollToItem(from.first, from.second)
        }
    }
    fun jumpTo(index: Int, totals: Boolean = false) {
        if (index !in rows.indices) return
        val from = here()
        val key = rows[index].key
        scope.launch {
            listState.animateScrollToItem(index)
            // Totals: the header card's lower part, where the amounts before and with VAT are.
            if (totals) {
                val info = listState.layoutInfo
                info.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
                    val extra = item.size - ((info.viewportEndOffset - info.viewportStartOffset) * 0.6f).toInt()
                    if (extra > 0) listState.animateScrollBy(extra.toFloat())
                }
            }
            flash = key to System.nanoTime()
            offerBack(from, if (totals) labelTotals else labelAt(index))
        }
    }
    fun jumpNext() {
        nextToCheck(rows, listState.firstVisibleItemIndex, state.draft, state.errors, HEADER)?.let { jumpTo(it) }
    }
    val jumpTotals = { jumpTo(rows.indexOfFirst { it.key == HEADER }, totals = true) }
    // Lines · Totals · Next to check, on a long document only (a fixed rule: the top of the screen never shifts).
    val showChips = !state.loading && state.fatal == null && state.draft.items.size >= JUMP_CHIPS_FROM_LINES
    val chips = buildList {
        add(JumpChip(labelLines, rows.indexOfFirst { it.key == ITEMS_TITLE }))
        add(JumpChip(labelTotals, CHIP_TOTALS))
        if (toCheck > 0) add(JumpChip(labelNext, CHIP_NEXT))
    }
    val onChip: (Int) -> Unit = { i ->
        when (i) {
            CHIP_TOTALS -> jumpTotals()
            CHIP_NEXT -> jumpNext()
            else -> jumpTo(i)
        }
    }
    val jumpActions = buildList {
        if (toCheck > 0) add(CustomAccessibilityAction(labelNext) { jumpNext(); true })
        add(CustomAccessibilityAction(labelTotals) { jumpTotals(); true })
    }
    var dragFrom by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    CompositionLocalProvider(LocalFieldPeek provides onPeek, LocalReplacements provides replacements) {
    Box(Modifier.fillMaxSize()) {
    AppScaffold(
        title = title,
        onBack = leave,
        snackbarHostState = reviewSnackbar,
        subBar = if (chain != null || showChips) ({
            Column {
                chain?.let { ch -> ChainBar(ch, canPutOff = !state.loading && state.fatal == null && state.savedId == null && state.chainMove == null, onCheckLater = vm::checkLater) }
                // Hidden while the keyboard is open (JumpChips), so they never take room from the field being typed in.
                if (showChips) JumpChips(chips, onChip, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }) else null,
        actions = {
            // Not once it is saved (the chain's pause before the next invoice): there is nothing left to throw away.
            if (state.isNew && state.fatal == null && !state.loading && state.savedId == null) {
                IconButton(onClick = { askDiscard = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.discard))
                }
            }
        },
        floatingActionButton = {
            // One tap to the next thing to check, instead of scrolling through the lines that are fine.
            AnimatedVisibility(
                visible = !state.loading && state.fatal == null && state.savedId == null && toCheck > 0,
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
            if (!state.loading && state.fatal == null && state.savedId == null) {
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
            // An invoice of a file of many is rebuilt from what was stored when opened (about a second).
            state.loading && vm.bundleId != null -> Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.CircularProgressIndicator()
                Text(stringResource(R.string.bundle_preparing_one), style = MaterialTheme.typography.titleMedium)
            }
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.fatal != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                EmptyState(stringResource(R.string.nothing_to_review))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.back)) }
            }
            // The scroller's track shrinks with the keyboard, like the list.
            else -> Box(Modifier.padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 24dp at the end: the ✓ and delete buttons stay clear of the scroller's thumb.
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp + FastScrollbarDefaults.EndGutter, top = 16.dp, bottom = 88.dp),
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
                        is TopRow -> Box(m) {
                            when (row.key) {
                                CATEGORY -> CategoryRow(state.supplierCategory, onClick = { pickCategory = true })
                                NOT_FOOD -> NotFoodLines(state.draft, open = notFoodOpen, onToggle = { notFoodOpen = !notFoodOpen })
                                else -> TopPart(row.key, state, vm, sellerNames, flashing?.second, onViewOriginal, onPickDate = { showDatePicker = true })
                            }
                        }
                    }
                }
            }
            // Above "Next to check" and the Save bar. A drag closes the photo strip and the keyboard (the jumps it
            // makes do not count as the operator scrolling the list), then offers the way back.
            FastScrollbar(
                listState,
                label = ::labelAt,
                bottomInset = 88.dp,
                onGrab = {
                    peekRequest = null
                    focusManager.clearFocus()
                    dragFrom = here()
                },
                onRelease = {
                    val from = dragFrom
                    dragFrom = null
                    val now = listState.firstVisibleItemIndex
                    if (from != null && now != from.first) offerBack(from, labelAt(now))
                },
                actions = jumpActions,
            )
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

    if (pickCategory) {
        CategoryPickerSheet(
            title = state.draft.seller.text.ifBlank { stringResource(R.string.seller) },
            selected = state.supplierCategory,
            onPick = { cat -> vm.chooseCategory(cat, categoryForAll); pickCategory = false },
            onDismiss = { pickCategory = false },
            note = if (categoryForAll || !vm.canChooseJustThis) stringResource(R.string.supcat_change_seller_note) else null,
            chosenBefore = state.categoryChosenBefore,
            extra = if (vm.canChooseJustThis) ({
                Column(Modifier.selectableGroup()) {
                    ScopeChoice(stringResource(R.string.supcat_scope_all), categoryForAll) { categoryForAll = true }
                    ScopeChoice(stringResource(R.string.supcat_scope_one), !categoryForAll) { categoryForAll = false }
                }
            }) else null,
        )
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
                notFood = notFoodProducts,
            )
        }
    }
}

/** The pause after "Saved ✓ · next: …" before the next invoice of the chain opens. */
private const val CHAIN_PAUSE_MS = 2_000L

/** How long "Back to where you were" stays after a jump (longer when the accessibility settings ask for it). */
private const val BACK_OFFER_MS = 5_000L

/** The jump chips show on documents of this many lines or more. */
private const val JUMP_CHIPS_FROM_LINES = 12

/** Chips whose place is worked out at the tap (not a row index). */
private const val CHIP_TOTALS = -1
private const val CHIP_NEXT = -2

/**
 * "Invoice 3 of 25 to check (+ 40 still being read)" under the top bar, and "Check later" on its own line below it,
 * so the line keeps the whole width (Italian, large text).
 */
@Composable
private fun ChainBar(chain: Chain, canPutOff: Boolean, onCheckLater: () -> Unit) {
    val at = chain.position ?: return
    var text = stringResource(R.string.bundle_position, at, chain.of)
    if (chain.stillReading > 0) text += " (" + pluralStringResource(R.plurals.bundle_position_more, chain.stillReading, chain.stillReading) + ")"
    Column(Modifier.fillMaxWidth()) {
        com.kitchenreceipts.app.ui.components.SubBarText(text)
        if (canPutOff) {
            TextButton(onClick = onCheckLater, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 6.dp).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.bundle_check_later))
            }
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
    c.vatProven || c.changed || c.packagesAgree || c.packagesCounted != null || c.pagesMissing.isNotEmpty()

/**
 * What the document's own sums prove about the reading: every VAT rate adds up, lines recovered or left out to make
 * them add up, packages against the printed total (packages are not lines), pages not photographed.
 */
@Composable
private fun ChecksPanel(c: Reconcile.Report) {
    val status = LocalStatusColors.current
    Panel {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.proof_title), fontWeight = FontWeight.SemiBold)
            if (c.vatProven) Text("✓ " + stringResource(R.string.proof_vat_proven), color = status.ok, style = MaterialTheme.typography.bodyMedium)
            if (c.recovered.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.proof_recovered, c.recovered.size, c.recovered.size), style = MaterialTheme.typography.bodyMedium)
                c.recovered.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (c.dropped.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.proof_dropped, c.dropped.size, c.dropped.size), style = MaterialTheme.typography.bodyMedium)
                c.dropped.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (c.ratesFound > 0) Text(pluralStringResource(R.plurals.proof_rates, c.ratesFound, c.ratesFound), style = MaterialTheme.typography.bodyMedium)
            c.packagesPrinted?.let { printed ->
                if (c.packagesAgree) {
                    Text("✓ " + stringResource(R.string.proof_packages_ok, printed), color = status.ok, style = MaterialTheme.typography.bodyMedium)
                } else c.packagesCounted?.let { counted ->
                    Text(stringResource(R.string.proof_packages_differ, counted, printed), color = status.uncertainBorder, style = MaterialTheme.typography.bodyMedium)
                }
            }
            c.pagesOf?.takeIf { c.pagesMissing.isNotEmpty() }?.let { of ->
                Text(
                    stringResource(R.string.proof_pages_missing, c.pagesMissing.joinToString(", "), of),
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
private const val CATEGORY = "category"
private const val ITEMS_TITLE = "itemsTitle"
/** The one row that holds a non-food bill's lines while they are folded. */
private const val NOT_FOOD = "notFood"

/** What the supplier sells, as a chip that opens the category tiles ("Not sorted yet" when there is none). */
@Composable
private fun CategoryRow(category: SupplierCategory?, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.supcat_label), style = MaterialTheme.typography.labelLarge)
        CategoryPill(category, onClick = onClick)
    }
}

/** "For all invoices from this supplier" / "Just this invoice": one of two, a 56dp row with a radio mark. */
@Composable
private fun ScopeChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The difference between the lines' amounts and the document's total (before VAT, or with it), in cents: 0 when they
 * add up (as the header's own check counts it), null when a line's amount or the total is not there.
 */
private fun linesDifference(d: DocumentDraft): Long? {
    val totals = d.items.map { ItalianNumbers.parseCents(it.lineTotal.text) }
    if (totals.isEmpty() || totals.any { it == null }) return null
    val sum = totals.sumOf { it!! }
    val diff = listOfNotNull(ItalianNumbers.parseCents(d.subtotal.text), ItalianNumbers.parseCents(d.total.text))
        .map { it - sum }.minByOrNull { kotlin.math.abs(it) } ?: return null
    return if (kotlin.math.abs(diff) <= maxOf(2, totals.size.toLong())) 0 else diff
}

/**
 * A non-food bill's lines folded into one row: "18 lines: kept apart from your food products", and whether they add
 * up to the total. A tap shows them (and folds them again).
 */
@Composable
private fun NotFoodLines(draft: DocumentDraft, open: Boolean, onToggle: () -> Unit) {
    val status = LocalStatusColors.current
    val n = draft.items.size
    val diff = remember(draft.items, draft.subtotal, draft.total) { linesDifference(draft) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "notFoodChevron")
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onToggle,
        interactionSource = source,
        modifier = Modifier.pressScale(source).fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = Palette.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.PhthaloBorder),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pluralStringResource(R.plurals.supcat_not_food_lines, n, n), style = MaterialTheme.typography.titleSmall)
                when {
                    diff == 0L -> Text(stringResource(R.string.supcat_lines_sum_ok), style = MaterialTheme.typography.bodyMedium, color = status.ok)
                    diff != null -> Text(
                        stringResource(R.string.supcat_lines_sum_off, fmtMoney(kotlin.math.abs(diff), draft.currency.text.ifBlank { "EUR" })),
                        style = MaterialTheme.typography.bodyMedium, color = status.uncertainBorder,
                    )
                }
                Text(
                    stringResource(if (open) R.string.lines_tap_to_fold else R.string.lines_tap_to_see),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 8.dp).graphicsLayer { rotationZ = turn })
        }
    }
}

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
        ITEMS_TITLE -> {
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
