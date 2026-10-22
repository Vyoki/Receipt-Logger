package com.kitchenreceipts.app.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.DocumentPages
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ViewerFile(val path: String, val mime: String, val pages: Int)

/**
 * Full-screen original with pinch-to-zoom, opened at [initialPage] (from 0). [documentId] null = the document being
 * read ([jobId]), or the file of an import of many invoices ([bundleId], its plan).
 */
@Composable
fun ViewerScreen(documentId: Long?, jobId: String? = null, bundleId: String? = null, initialPage: Int = 0, onBack: () -> Unit) {
    val c = appContainer()
    val file by produceState<Result<ViewerFile?>?>(null, documentId, jobId, bundleId) {
        value = Result.success(
            when {
                documentId != null -> c.repository.documentOnce(documentId)?.let { ViewerFile(it.filePath, it.mimeType, it.pageCount) }
                bundleId != null -> c.bundleStore.plan(bundleId)?.file?.takeIf { it.isNotBlank() }
                    ?.takeIf { withContext(Dispatchers.IO) { c.fileStore.file(it).exists() } }
                    ?.let { ViewerFile(it, FileStore.MIME_PDF, c.bundleStore.plan(bundleId)?.pageCount ?: 1) }
                else -> jobId?.let { c.importQueue.job(it) }?.file?.let { ViewerFile(it.relativePath, it.mimeType, it.pageCount) }
            },
        )
    }
    AppScaffold(title = stringResource(R.string.original_document), onBack = onBack) { padding ->
        val result = file
        val f = result?.getOrNull()
        if (result == null) {
            LoadingBox(Modifier.padding(padding))
        } else if (f == null) {
            EmptyState(stringResource(R.string.document_not_found), Modifier.padding(padding))
        } else {
            DocumentPages(f.path, f.mime, f.pages, Modifier.fillMaxSize().padding(padding), zoomable = true, initialPage = initialPage)
        }
    }
}
