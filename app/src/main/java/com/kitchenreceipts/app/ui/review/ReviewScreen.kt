@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.review

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
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.ReceiptParser
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.Units
import com.kitchenreceipts.core.VatBasis
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

    // The part of the photo behind the field being checked (tap a field to see it).
    var peekRequest by remember { mutableStateOf<PeekRequest?>(null) }
    var peekImage by remember { mutableStateOf<PeekImage?>(null) }
    var peekClosed by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<Pair<String, PeekImage>?>(null) }
    LaunchedEffect(peekRequest) {
        val r = peekRequest
        if (r == null) { peekImage = null; return@LaunchedEffect }
        peekClosed = false
        peekImage = runCatching { vm.peek(r.source, r.value) }.getOrNull()
    }

    // Going back keeps a new document waiting on the home screen; throwing it away is its own button.
    val leave: () -> Unit = onBack

    val title = stringResource(if (state.isNew) R.string.review_title else R.string.edit_title)
    val onPeek: (PeekRequest?) -> Unit = remember { { r -> if (r != null) peekRequest = r else if (enlarged == null) peekRequest = null } }
    CompositionLocalProvider(LocalFieldPeek provides onPeek) {
    Box(Modifier.fillMaxSize()) {
    AppScaffold(
        title = title,
        onBack = leave,
        actions = {
            if (state.isNew && state.fatal == null && !state.loading) {
                IconButton(onClick = { askDiscard = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.discard))
                }
            }
        },
        bottomBar = {
            if (!state.loading && state.fatal == null) {
                SaveBar(
                    uncertain = state.draft.uncertainCount,
                    errors = state.errors.size,
                    saving = state.saving,
                    onSave = {
                        vm.requestSave()
                        scope.launch { listState.animateScrollToItem(0) }
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("preview") {
                    val path = state.filePath
                    val mime = state.mimeType
                    if (path != null && mime != null) {
                        Panel {
                            DocumentPages(path, mime, state.pageCount, Modifier.fillMaxWidth().height(260.dp), onClick = onViewOriginal)
                            TextButton(onClick = onViewOriginal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.OpenInFull, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.view_original_full))
                            }
                        }
                    }
                }
                item("status") { StatusBanner(state) }
                state.draft.aiCheck?.takeIf { it.disagreements.isEmpty() && state.isNew }?.let { c ->
                    item("aiCheck") {
                        Text(
                            pluralStringResource(R.plurals.ai_check_ok, c.checked, c.checked),
                            color = LocalStatusColors.current.ok,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (state.isNew && state.reviewReasons.isNotEmpty()) {
                    item("reasons") { ReasonsCard(state) }
                }
                if (state.priceChanges.isNotEmpty()) {
                    item("prices") { PriceChangesCard(state.priceChanges) }
                }
                state.recognisedText?.let { t -> item("recognised") { RecognisedTextCard(t) } }
                if (state.errors.isNotEmpty()) {
                    item("errors") { WarningCard(listOf(stringResource(R.string.fix_errors, state.errors.size))) }
                }
                state.saveError?.let { err -> item("saveError") { WarningCard(listOf(stringResource(R.string.save_failed, err))) } }
                if (state.draft.warnings.isNotEmpty()) {
                    item("warnings") { WarningCard(state.draft.warnings.map { warningText(it) }) }
                }

                item("header") {
                    HeaderSection(
                        draft = state.draft,
                        errors = state.errors,
                        sellerNames = sellerNames,
                        vm = vm,
                        onPickDate = { showDatePicker = true },
                    )
                }

                item("itemsTitle") {
                    SectionTitle(pluralStringResource(R.plurals.items_title, state.draft.items.size, state.draft.items.size))
                }
                if (state.draft.items.isEmpty()) {
                    item("noItems") { EmptyState(stringResource(R.string.no_items_hint)) }
                }
                items(state.draft.items, key = { it.key }) { item ->
                    ItemCard(
                        item = item,
                        lotsPrinted = state.draft.lotsPrinted,
                        errors = state.errors,
                        onChange = { f, t -> vm.setItem(item.key, f, t) },
                        onConfirm = { f -> vm.confirmItem(item.key, f) },
                        onUseComputed = { vm.useComputedTotal(item.key) },
                        onPickProduct = { pickerFor = item.key },
                        onRemove = { vm.removeItem(item.key) },
                        onPickChoice = { ch -> vm.pickChoice(item.key, ch) },
                    )
                }
                item("addItem") {
                    OutlinedButton(onClick = vm::addItem, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_item))
                    }
                }
                item("bottomSpace") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    val shown = peekImage
    val req = peekRequest
    if (shown != null && req != null && !peekClosed) {
        FieldPeekCard(
            label = req.label,
            img = shown,
            onEnlarge = { enlarged = req.label to shown },
            onClose = { peekClosed = true },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
        )
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
            title = stringResource(if (c.duplicates.isNotEmpty()) R.string.possible_duplicate_title else R.string.unchecked_title),
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
                                (d.row.documentNumber?.let { " · n. $it" } ?: "") + " · ${fmtMoney(d.row.totalCents)}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(d.match.reasons.map { duplicateReasonText(it) }.joinToString("; "), style = MaterialTheme.typography.bodyMedium)
                    }
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
            pickerFor = null
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
                c.disagreements.forEach { Text("   $it", style = MaterialTheme.typography.bodyMedium) }
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

@Composable
private fun StatusBanner(state: ReviewState) {
    val lines = buildList {
        if (state.isNew) {
            when {
                state.ocrError != null -> add(stringResource(R.string.ocr_failed, state.ocrError))
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
                stringResource(R.string.recognised_supplier, r.match.name, how, r.documentCount) +
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
        val typed = DuplicateDetector.normalizeSeller(draft.seller.text)
        val matches = if (typed == null) emptyList() else sellerNames.filter {
            val n = DuplicateDetector.normalizeSeller(it) ?: ""
            it != draft.seller.text && (n.contains(typed) || typed.contains(n)) && n.isNotEmpty()
        }.take(3)
        if (matches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { name -> AssistChip(onClick = { vm.setHeader(HeaderField.SELLER, name) }, label = { Text(name) }) }
            }
        }

        HeaderInput(draft, HeaderField.DATE, R.string.date, errors, vm, FieldKind.DATE, placeholder = "gg/mm/aaaa", trailing = {
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

@Composable
private fun ItemCard(
    item: LineItemDraft,
    lotsPrinted: Boolean,
    errors: Map<String, com.kitchenreceipts.core.ErrorCode>,
    onChange: (ItemField, String) -> Unit,
    onConfirm: (ItemField) -> Unit,
    onUseComputed: () -> Unit,
    onPickProduct: () -> Unit,
    onRemove: () -> Unit,
    onPickChoice: (LineChoice) -> Unit = {},
) {
    val status = LocalStatusColors.current
    Panel {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ItemInput(item, errors, onChange, onConfirm, ItemField.DESCRIPTION, R.string.description_on_document, Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_item))
                }
            }
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
                label = { Text(productLabel, maxLines = 1) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemInput(item, errors, onChange, onConfirm, ItemField.QUANTITY, R.string.quantity, Modifier.weight(1f), FieldKind.DECIMAL)
                ItemInput(item, errors, onChange, onConfirm, ItemField.UNIT, R.string.unit, Modifier.weight(1f))
            }
            if (item.unit.isMissing || Units.normalizeKnown(item.unit.text) == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("kg", "pz", "conf", "l").forEach { u ->
                        AssistChip(onClick = { onChange(ItemField.UNIT, u) }, label = { Text(u) })
                    }
                }
            }
            // "4 pz × 500 g = 2 kg": what the packs hold, for inventory and price comparisons.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemInput(item, errors, onChange, onConfirm, ItemField.PACK_SIZE, R.string.pack_size, Modifier.weight(1f), FieldKind.TEXT, optional = true)
                item.packTotal()?.let { (amount, u) ->
                    Text(
                        stringResource(R.string.pack_total, ItalianNumbers.formatDecimal(amount, maxScale = 3), u),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
            val printed = ItalianNumbers.parseCents(item.lineTotal.text)
            val q = ItalianNumbers.parse(item.quantity.text)
            val p = ItalianNumbers.parse(item.unitPrice.text)
            if (computed != null && printed == null) {
                AssistChip(onClick = onUseComputed, label = { Text(stringResource(R.string.use_computed_total, ItalianNumbers.formatCents(computed))) })
            } else if (q != null && p != null && printed != null && !ReceiptParser.matches(q, p, printed)) {
                Text(
                    stringResource(R.string.line_mismatch, ItalianNumbers.formatCents(computed ?: 0)),
                    color = status.uncertainBorder,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A document that prints no lots at all: an empty lot is normal, not something to fill in.
                ItemInput(
                    item, errors, onChange, onConfirm, ItemField.LOT, R.string.lot, Modifier.weight(1f), FieldKind.CODE,
                    missingHint = stringResource(R.string.lot_missing_hint), optional = !lotsPrinted,
                )
                ItemInput(item, errors, onChange, onConfirm, ItemField.VAT_RATE, R.string.vat_rate, Modifier.weight(0.6f), FieldKind.DECIMAL, optional = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemInput(item, errors, onChange, onConfirm, ItemField.PACKAGES, R.string.packages, Modifier.weight(0.6f), FieldKind.CODE, optional = true)
                ItemInput(item, errors, onChange, onConfirm, ItemField.EXPIRY, R.string.expiry, Modifier.weight(1f), kind = FieldKind.DATE, optional = true)
            }
        }
    }
}

@Composable
private fun SaveBar(uncertain: Int, errors: Int, saving: Boolean, onSave: () -> Unit) {
    // Black bar with a hairline on top: the save button stands out on its own.
    Surface(color = MaterialTheme.colorScheme.background) { Column {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                when {
                    errors > 0 -> Text(pluralStringResource(R.plurals.errors_count, errors, errors), color = MaterialTheme.colorScheme.error)
                    uncertain > 0 -> Text(pluralStringResource(R.plurals.values_to_check_short, uncertain, uncertain), color = LocalStatusColors.current.uncertainBorder)
                    else -> Text(stringResource(R.string.all_checked), color = LocalStatusColors.current.ok)
                }
            }
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
            }
        }
    } }
}

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
    )
}
