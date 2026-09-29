package com.kitchenreceipts.app.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.DocumentPages
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox

private data class ViewerFile(val path: String, val mime: String, val pages: Int)

/** Full-screen original with pinch-to-zoom. [documentId] null = the document being read ([jobId]). */
@Composable
fun ViewerScreen(documentId: Long?, jobId: String? = null, onBack: () -> Unit) {
    val c = appContainer()
    val file by produceState<Result<ViewerFile?>?>(null, documentId) {
        value = Result.success(if (documentId == null) {
            jobId?.let { c.importQueue.job(it) }?.file?.let { ViewerFile(it.relativePath, it.mimeType, it.pageCount) }
        } else {
            c.repository.documentOnce(documentId)?.let { ViewerFile(it.filePath, it.mimeType, it.pageCount) }
        })
    }
    AppScaffold(title = stringResource(R.string.original_document), onBack = onBack) { padding ->
        val result = file
        val f = result?.getOrNull()
        if (result == null) {
            LoadingBox(Modifier.padding(padding))
        } else if (f == null) {
            EmptyState(stringResource(R.string.document_not_found), Modifier.padding(padding))
        } else {
            DocumentPages(f.path, f.mime, f.pages, Modifier.fillMaxSize().padding(padding), zoomable = true)
        }
    }
}
