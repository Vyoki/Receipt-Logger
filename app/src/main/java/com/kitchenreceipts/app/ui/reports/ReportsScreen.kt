package com.kitchenreceipts.app.ui.reports

import com.kitchenreceipts.app.ui.components.Panel

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.fmtMonth
import com.kitchenreceipts.core.CsvFormat
import com.kitchenreceipts.core.MonthlySellerRow
import com.kitchenreceipts.core.ReportCsv
import com.kitchenreceipts.core.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class ExportKind { MONTHLY, PURCHASES }

class ReportsViewModel(private val repo: ReceiptRepository, private val log: com.kitchenreceipts.app.diagnostics.AppLog) : ViewModel() {
    val rows: StateFlow<List<MonthlySellerRow>?> = repo.reportDocuments().map { Reports.monthlyBySeller(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** null = idle; true/false = last export result. */
    val exportResult = MutableStateFlow<Boolean?>(null)

    fun export(uri: Uri, kind: ExportKind, format: CsvFormat, resolver: ContentResolver) = viewModelScope.launch {
        val ok = try {
            val csv = when (kind) {
                ExportKind.MONTHLY -> ReportCsv.monthlySeller(rows.value ?: Reports.monthlyBySeller(emptyList()), format)
                ExportKind.PURCHASES -> ReportCsv.purchases(repo.purchaseExportRows(), format)
            }
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write")
            }
            log.event("EXPORT", "kind" to kind, "format" to format)
            true
        } catch (e: Exception) {
            log.error("export", e)
            false
        }
        exportResult.value = ok
    }
}

@Composable
fun ReportsScreen(onBack: () -> Unit) {
    val vm = appViewModel { ReportsViewModel(it.repository, it.log) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var format by remember { mutableStateOf(CsvFormat.ITALIAN_EXCEL) }
    var pendingKind by remember { mutableStateOf(ExportKind.MONTHLY) }
    val snackbar = remember { SnackbarHostState() }
    val okMsg = stringResource(R.string.export_done)
    val failMsg = stringResource(R.string.export_failed)

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.export(uri, pendingKind, format, resolver)
    }
    LaunchedEffect(exportResult) {
        exportResult?.let {
            snackbar.showSnackbar(if (it) okMsg else failMsg)
            vm.exportResult.value = null
        }
    }

    AppScaffold(title = stringResource(R.string.monthly_reports), onBack = onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("boss") { BossReportCard() }
            item("office") { OfficeCopyCard() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(format == CsvFormat.ITALIAN_EXCEL, { format = CsvFormat.ITALIAN_EXCEL }, label = { Text(stringResource(R.string.csv_italian)) })
                        FilterChip(format == CsvFormat.STANDARD, { format = CsvFormat.STANDARD }, label = { Text(stringResource(R.string.csv_standard)) })
                    }
                    val today = LocalDate.now()
                    BigButton(stringResource(R.string.export_monthly), Icons.Filled.FileDownload, onClick = {
                        pendingKind = ExportKind.MONTHLY
                        createFile.launch("spese_mensili_$today.csv")
                    })
                    BigButton(stringResource(R.string.export_purchases), Icons.Filled.FileDownload, primary = false, onClick = {
                        pendingKind = ExportKind.PURCHASES
                        createFile.launch("acquisti_$today.csv")
                    })
                    Text(stringResource(R.string.report_totals_note), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
                }
            }
            val data = rows
            if (data != null && data.isEmpty()) item { EmptyState(stringResource(R.string.no_documents_yet)) }
            val byMonth = data.orEmpty().groupBy { it.month }
            byMonth.forEach { (month, sellers) ->
                item(key = "m-$month") {
                    Panel {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(month?.let { fmtMonth(it) } ?: stringResource(R.string.no_date), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Text(fmtMoney(sellers.sumOf { it.totalCents }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            sellers.forEach { r -> SellerMonthRow(r) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SellerMonthRow(r: MonthlySellerRow) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(r.sellerName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(fmtMoney(r.totalCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
            pluralStringResource(R.plurals.documents_count, r.documentCount, r.documentCount) + " · " +
                pluralStringResource(R.plurals.lines_count, r.lineItemCount, r.lineItemCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.documentsMissingTotal > 0) {
            Text(
                pluralStringResource(R.plurals.missing_totals, r.documentsMissingTotal, r.documentsMissingTotal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
