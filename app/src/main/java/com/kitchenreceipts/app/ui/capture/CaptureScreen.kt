package com.kitchenreceipts.app.ui.capture

import com.kitchenreceipts.app.ui.components.Panel

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.core.content.ContextCompat
import com.kitchenreceipts.app.jobs.ReadingService
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
import com.kitchenreceipts.app.ui.components.OutlinedButton
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
    val error: String? = null,
    /** The document was handed to the background reader. */
    val queued: Boolean = false,
)

class CaptureViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** A PDF (it has all its pages already) goes straight to the reader. */
    fun importPdf(uri: Uri) = queue("file") { c.fileStore.importUri(uri) }

    /** All the pages collected in the tray become one document, read once they are all there. */
    fun usePages(paths: List<String>) = queue("pages:${paths.size}") {
        val files = paths.map(::File).filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) throw UnsupportedFileException("No photo to import")
        c.fileStore.storePhotos(files) { c.pageRenderer.decodeImage(it, 3000) }
    }

    /** Copies pictures picked from the gallery into the tray (the picker's access does not last). */
    suspend fun copyPicked(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                val (file, _) = c.fileStore.newCaptureTarget()
                c.appContext.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                file.absolutePath
            }.onFailure { c.log.error("pickImage", it) }.getOrNull()
        }
    }

    private fun queue(source: String, store: suspend () -> StoredFile) {
        if (_state.value.busy) return
        c.log.event("IMPORT_START", "source" to source)
        viewModelScope.launch {
            _state.value = CaptureState(busy = true)
            try {
                val stored = store()
                c.importQueue.enqueue(stored, source)
                withContext(Dispatchers.IO) { c.fileStore.deleteCaptures() }
                _state.value = CaptureState(queued = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.log.error("import", e)
                _state.value = CaptureState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun consumeQueued() = _state.update { it.copy(queued = false) }
    fun clearError() = _state.update { it.copy(error = null) }
}

@Composable
fun CaptureScreen(onBack: () -> Unit, onQueued: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm = appViewModel { CaptureViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Page paths survive rotation and process death while the camera app is open.
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
    // Photos already on the phone: the system photo picker (no storage permission), several at once.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch { photos = photos + vm.copyPicked(uris) }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPdf(uri)
    }
    // Android 13+: ask once to show "ready" / "saved" notifications for background reading.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    LaunchedEffect(state.queued) {
        if (state.queued) {
            vm.consumeQueued()
            photos = emptyList()
            ReadingService.ensureRunning(context)
            onQueued()
        }
    }

    AppScaffold(title = stringResource(R.string.scan_document), onBack = if (state.busy) null else onBack) { padding ->
        if (state.busy) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.saving_original), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
                    stringResource(R.string.pick_photos),
                    Icons.Filled.PhotoLibrary,
                    onClick = { launchError = null; vm.clearError(); pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    primary = false,
                )
                BigButton(
                    stringResource(R.string.import_pdf),
                    Icons.Filled.UploadFile,
                    onClick = { launchError = null; vm.clearError(); ensureNotificationPermission(); pickPdf.launch(arrayOf("application/pdf")) },
                    primary = false,
                )
                Text(stringResource(R.string.capture_tips), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
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
                BigButton(
                    pluralStringResource(R.plurals.read_pages, photos.size, photos.size),
                    null,
                    onClick = { ensureNotificationPermission(); vm.usePages(photos) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(stringResource(R.string.add_page), Icons.Filled.AddAPhoto, onClick = { launchCamera() }, primary = false, modifier = Modifier.weight(1f))
                    BigButton(
                        stringResource(R.string.add_from_gallery),
                        Icons.Filled.PhotoLibrary,
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(stringResource(R.string.multi_photo_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
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
    Panel(Modifier.width(120.dp).height(160.dp)) {
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
