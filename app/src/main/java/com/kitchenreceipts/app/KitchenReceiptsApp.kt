package com.kitchenreceipts.app

import android.app.Application
import android.content.Context
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.ai.AiModelStore
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.app.ocr.AiUse
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.ocr.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.ImportQueue
import com.kitchenreceipts.app.jobs.ReadingNotifier
import com.kitchenreceipts.app.learning.LearningStore
import com.kitchenreceipts.core.SupplierMemory

/** Manual dependency container: small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = AppSettings(context)
    val log = AppLog(context, settings)
    val database: AppDatabase = AppDatabase.build(context)
    val fileStore = FileStore(context)
    val pageRenderer = PageRenderer(fileStore)
    val repository = ReceiptRepository(database, fileStore)
    val importProcessor = ImportProcessor(pageRenderer)
    val aiModels = AiModelStore(context)
    /** What the app learned from the operator's choices and confirmed lines, per supplier (phone only). */
    val learning = LearningStore(context)
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    /** How the AI reader should help with the next import, from the settings and the installed model (null = not at all). */
    fun aiUse(): AiUse? {
        val mode = settings.aiMode
        if (mode == AppSettings.AiMode.OFF || !aiModels.installed || !aiModels.deviceSupport().nativeOk) return null
        return AiUse(
            reader = { AiPageReader.open(aiModels, nativeLibDir) },
            always = mode == AppSettings.AiMode.ALWAYS,
            examples = { parsed, text ->
                learning.examples(SupplierMemory.key(text, settings.ownVatNumber.ifBlank { null }, parsed.sellerName?.value))
            },
        )
    }

    init {
        repository.onSharedVatNumber = { vat ->
            log.event("OWN_VAT_DETECTED", "vat" to vat)
            if (settings.ownVatNumber.isBlank()) settings.ownVatNumber = vat
        }
    }

    /** Swap this line to change OCR engine. */
    val ocrEngine: OcrEngine = MlKitOcrEngine()

    val appScope = CoroutineScope(SupervisorJob())

    val notifier = ReadingNotifier(context)
    val preparer = DraftPreparer(repository, settings, log, learning)

    /** Documents read in the background, one after another; survives restarts. */
    val importQueue = ImportQueue(
        context, appScope, importProcessor, ocrEngine, settings, ::aiUse, preparer, fileStore, log, notifier,
    )

    /** A screen to open, e.g. from a notification tap ("review/<job>", "document/<id>"). */
    val pendingRoute = MutableStateFlow<String?>(null)

    /** App-lock state for this process: unlocked once per launch, locked again after 3 minutes in background. */
    @Volatile var unlocked: Boolean = false
    @Volatile var lastBackgroundAt: Long = 0L
}

class KitchenReceiptsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container
        // Record crashes in the local log before Android shows "app has stopped".
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            c.log.crashSync(e)
            previous?.uncaughtException(thread, e)
        }
        c.log.event(
            "APP_START",
            "version" to runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
            "android" to android.os.Build.VERSION.SDK_INT,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "language" to c.settings.language,
        )
        // Documents that were being read when the app was closed: finished ones come back ready to check,
        // unfinished ones are read again.
        runCatching { c.importQueue.restore() }.onFailure { c.log.error("restoreQueue", it) }
        container.appScope.launch {
            runCatching { container.repository.cleanupOrphanFiles(c.importQueue.filePaths()) }
        }
    }
}
