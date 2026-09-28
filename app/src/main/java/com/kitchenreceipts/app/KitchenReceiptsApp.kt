package com.kitchenreceipts.app

import android.app.Application
import android.content.Context
import com.kitchenreceipts.app.data.AppDatabase
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
}

class KitchenReceiptsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            runCatching { container.repository.cleanupOrphanFiles() }
        }
    }
}
