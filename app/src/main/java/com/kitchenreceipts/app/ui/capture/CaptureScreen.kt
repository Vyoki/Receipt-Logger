package com.kitchenreceipts.app.ui.capture

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.files.UnsupportedFileException
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.WarningCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class CaptureState(
    val busy: Boolean = false,
    val step: Step = Step.IDLE,
    val page: Int = 0,
    val pages: Int = 0,
    /** 2 = second, closer reading of an enhanced image (the first did not fully check out). */
    val pass: Int = 1,
    val error: String? = null,
    val ready: Boolean = false,
) {
    enum class Step { IDLE, STORING, READING }
}

class CaptureViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    private var job: Job? = null

    fun importUri(uri: Uri) = startImport("file") { c.fileStore.importUri(uri) }

    fun usePhotos(paths: List<String>) = startImport("camera:${paths.size}") {
        val files = paths.map(::File).filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) throw UnsupportedFileException("No photo to import")
        c.fileStore.storePhotos(files) { c.pageRenderer.decodeImage(it, 3000) }
    }

    private fun startImport(source: String, store: suspend () -> StoredFile) {
        if (_state.value.busy) return
        c.log.event("IMPORT_START", "source" to source)
        val t0 = System.currentTimeMillis()
        job = viewModelScope.launch {
            _state.value = CaptureState(busy = true, step = CaptureState.Step.STORING)
            var stored: StoredFile? = null
            try {
                val s = store()
                stored = s
                _state.update { it.copy(step = CaptureState.Step.READING) }
                val storeMs = System.currentTimeMillis() - t0
                val pending = c.importProcessor.process(s, c.ocrEngine, { page, of, pass ->
                    _state.update { it.copy(page = page, pages = of, pass = pass) }
                }, c.settings.parseOptions())
                c.log.event(
                    "IMPORT_DONE",
                    "type" to s.mimeType, "pages" to s.pageCount, "pagesRead" to pending.pagesRead,
                    "storeMs" to storeMs, "ocrMs" to pending.ocrMillis,
                    "ocrLines" to pending.rawLines.sumOf { it.size }, "ocrError" to pending.ocrError,
                    "reading" to pending.readingNote,
                )
                c.pendingImport = pending
                withContext(Dispatchers.IO) { c.fileStore.deleteCaptures() }
                _state.value = CaptureState(ready = true)
            } catch (e: CancellationException) {
                c.log.event("IMPORT_CANCELLED")
                stored?.let { c.fileStore.delete(it.relativePath) }
                _state.value = CaptureState()
                throw e
            } catch (e: Exception) {
                c.log.error("import", e)
                stored?.let { c.fileStore.delete(it.relativePath) }
                _state.value = CaptureState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun consumeReady() = _state.update { it.copy(ready = false) }
    fun clearError() = _state.update { it.copy(error = null) }
}

@Composable
fun CaptureScreen(onBack: () -> Unit, onReady: () -> Unit) {
    val container = appContainer()
    val vm = appViewModel { CaptureViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    // Photo paths survive rotation and process death while the camera app is open.
    var photos by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var launchError by rememberSaveable { mutableStateOf<String?>(null) }
    val noCameraMsg = stringResource(R.string.no_camera_app)

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPhoto
        pendingPhoto = null
        if (path != null) {
            if (ok && File(path).length() > 0) photos = photos + path else File(path).delete()
        }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }

    fun launchCamera() {
        val (file, uri) = container.fileStore.newCaptureTarget()
        pendingPhoto = file.absolutePath
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingPhoto = null
            launchError = noCameraMsg
        }
    }

    LaunchedEffect(state.ready) {
        if (state.ready) {
            vm.consumeReady()
            onReady()
        }
    }
    BackHandler(enabled = state.busy) { vm.cancel() }

    AppScaffold(title = stringResource(R.string.scan_document), onBack = if (state.busy) null else onBack) { padding ->
        if (state.busy) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text(
                    when (state.step) {
                        CaptureState.Step.READING ->
                            (if (state.pages > 1) stringResource(R.string.reading_page, state.page, state.pages)
                            else stringResource(R.string.reading_text)) +
                                (if (state.pass > 1) "\n" + stringResource(R.string.reading_second_pass) else "")
                        else -> stringResource(R.string.saving_original)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.cancel)) }
            }
            return@AppScaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val error = state.error ?: launchError
            if (error != null) WarningCard(listOf(error))

            if (photos.isEmpty()) {
                BigButton(stringResource(R.string.take_photo), Icons.Filled.CameraAlt, onClick = { launchError = null; vm.clearError(); launchCamera() })
                BigButton(
                    stringResource(R.string.import_file),
                    Icons.Filled.UploadFile,
                    onClick = { launchError = null; vm.clearError(); pickDocument.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) },
                    primary = false,
                )
                Text(stringResource(R.string.capture_tips), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.photos_taken, photos.size), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(photos, key = { _, p -> p }) { i, path ->
                        PhotoThumb(path, index = i + 1, onRemove = {
                            File(path).delete()
                            photos = photos - path
                        })
                    }
                }
                BigButton(stringResource(R.string.use_photos), null, onClick = { vm.usePhotos(photos) })
                BigButton(stringResource(R.string.add_page), Icons.Filled.AddAPhoto, onClick = { launchCamera() }, primary = false)
                Text(stringResource(R.string.multi_photo_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PhotoThumb(path: String, index: Int, onRemove: () -> Unit) {
    val renderer = appContainer().pageRenderer
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { renderer.decodeImage(File(path), 400) }.getOrNull() }
    }
    Card(Modifier.width(120.dp).height(160.dp)) {
        Box(Modifier.fillMaxSize()) {
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = stringResource(R.string.original_page, index), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$index", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
                FilledIconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_photo))
                }
            }
        }
    }
}
