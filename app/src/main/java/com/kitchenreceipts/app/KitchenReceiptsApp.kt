package com.kitchenreceipts.app

import android.app.Application
import android.content.Context
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.ocr.OcrEngine
import com.kitchenreceipts.app.ocr.PendingImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container: small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    val log = AppLog(context, settings)
    val database: AppDatabase = AppDatabase.build(context)
    val fileStore = FileStore(context)
    val pageRenderer = PageRenderer(fileStore)
    val repository = ReceiptRepository(database, fileStore)
    val importProcessor = ImportProcessor(pageRenderer)

    /** Swap this line to change OCR engine. */
    val ocrEngine: OcrEngine = MlKitOcrEngine()

    /** Hand-over from the capture screen to the review screen (in memory; nothing is saved yet). */
    @Volatile var pendingImport: PendingImport? = null

    val appScope = CoroutineScope(SupervisorJob())

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
        container.appScope.launch {
            runCatching { container.repository.cleanupOrphanFiles() }
        }
    }
}
