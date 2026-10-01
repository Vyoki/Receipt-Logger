@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.reports

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.reports.ReportSender
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.periodKindLabel
import com.kitchenreceipts.app.ui.periodLabel
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.PeriodKind
import kotlinx.coroutines.launch
import java.time.LocalDate

/** "Report for the owner": pick the period, one tap builds the PDF and opens WhatsApp / Mail / ... */
@Composable
fun BossReportCard() {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(Period.of(LocalDate.now(), PeriodKind.MONTH)) }
    var language by remember { mutableStateOf(c.settings.reportLanguage) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.rep_failed)
    val noApp = stringResource(R.string.no_viewer_app)

    fun make(preview: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val r = ReportSender.build(context.applicationContext, c.repository, period, language, c.settings.ownBusinessName)
                c.log.event("REPORT", "period" to period.label(), "preview" to preview, "docs" to r.report.documentCount, "language" to language)
                try {
                    context.startActivity(if (preview) ReportSender.viewIntent(context, r) else ReportSender.shareIntent(context, r))
                } catch (_: ActivityNotFoundException) {
                    error = noApp
                }
            } catch (e: Exception) {
                c.log.error("report", e)
                error = failed
            } finally {
                busy = false
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, com.kitchenreceipts.app.ui.theme.Palette.PhthaloBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.rep_card_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.rep_card_hint), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val kinds = listOf(PeriodKind.WEEK, PeriodKind.MONTH, PeriodKind.QUARTER)
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = period.kind == k,
                        onClick = { period = Period.of(LocalDate.now(), k) },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(periodKindLabel(k)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { period = period.previous() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_period))
                }
                Text(periodLabel(period), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { period = period.next() },
                    enabled = period.next().start <= LocalDate.now(),
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rep_language), style = MaterialTheme.typography.bodyMedium)
                listOf(AppSettings.Language.ITALIAN to "Italiano", AppSettings.Language.ENGLISH to "English").forEach { (l, label) ->
                    FilterChip(language == l, { language = l; c.settings.reportLanguage = l }, label = { Text(label) })
                }
            }
            BigButton(
                text = stringResource(if (busy) R.string.rep_building else R.string.rep_send),
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = { make(preview = false) },
            )
            BigButton(stringResource(R.string.rep_preview), Icons.Filled.PictureAsPdf, primary = false, onClick = { make(preview = true) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
