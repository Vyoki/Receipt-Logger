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
    /** Documents corrected by hand, kept on the phone to be shared in one go (see ProblemReports). */
    val problems = com.kitchenreceipts.app.diagnostics.ProblemReports(context)
    val pageRenderer = PageRenderer(fileStore)
    val repository = ReceiptRepository(database, fileStore)
    /** Delivery notes against invoices, agreed prices, credits owed, expiry dates and lots. */
    val checks = com.kitchenreceipts.app.data.ChecksRepository(database)
    /** Food cost of dishes and months, and the usual order to each supplier. */
    val food = com.kitchenreceipts.app.data.FoodRepository(database)
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
        // The PDF text reader needs its font tables from the app's files.
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }
        // Documents of a supplier seen before are read with what its confirmed documents taught (phone only).
        settings.layoutLookup = { key -> learning.layout(key) }
        runCatching { learning.applyCategories() }
        repository.onSharedVatNumber = { vat ->
            log.event("OWN_VAT_DETECTED", "vat" to vat)
            if (settings.ownVatNumber.isBlank()) settings.ownVatNumber = vat
        }
    }

    /** Swap this line to change OCR engine. */
    val ocrEngine: OcrEngine = MlKitOcrEngine()

    val appScope = CoroutineScope(SupervisorJob())

    val notifier = ReadingNotifier(context)
    /** Public knowledge that comes into the phone (known suppliers); see the network setting. */
    val knowledge = com.kitchenreceipts.app.knowledge.KnowledgeStore(context, settings, log)
    /** Product lookup online (Open Food Facts): only product words go out, only when the operator allows it. */
    val productLookup = com.kitchenreceipts.app.knowledge.ProductLookupClient(settings, log)
    val preparer = DraftPreparer(repository, settings, log, learning) { knowledge.pack.value }

    /** Documents read in the background, one after another; survives restarts. */
    val importQueue = ImportQueue(
        context, appScope, importProcessor, ocrEngine, settings, ::aiUse, preparer, fileStore, log, notifier,
    )

    /** The OCR reading of every saved document, and the re-check of all of them after each update. */
    val readings = com.kitchenreceipts.app.diagnostics.ReadingArchive(context)
    val readingChecker = com.kitchenreceipts.app.diagnostics.ReadingChecker(context, repository, readings, settings, problems, log) { importQueue.busy }

    init {
        importQueue.onAutoSaved = { id, pending -> readings.save(id, pending.rawLines); checkDishes(id) }
    }

    /**
     * After a document is saved (by hand or by the app): a notification for the dishes its prices pushed over the
     * food cost target. Follows the price notification setting.
     */
    fun checkDishes(documentId: Long) {
        if (!settings.priceAlerts) return
        appScope.launch {
            runCatching {
                val target = settings.foodCostTarget
                val alerts = food.dishAlerts(documentId, target)
                if (alerts.isNotEmpty()) {
                    log.event("DISH_ALERT", "doc" to documentId, "dishes" to alerts.size)
                    notifier.dishesOverTarget(documentId, alerts, target)
                }
            }.onFailure { log.error("dishAlerts", it) }
        }
    }

    /** Backup and restore of everything on this phone, to a file the operator keeps. */
    val backup = com.kitchenreceipts.app.backup.BackupManager(this)

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
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()?.let { com.kitchenreceipts.app.ocr.appVersion = it }
        c.log.event(
            "APP_START",
            "version" to runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
            "android" to android.os.Build.VERSION.SDK_INT,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "language" to c.settings.language,
        )
        // Documents that were being read when the app was closed: finished ones come back ready to check,
        // unfinished ones are read again.
        // After a restore: once the restored data opens, the data set aside before it can go.
        container.appScope.launch { runCatching { c.backup.afterStart() }.onFailure { c.log.error("afterRestore", it) } }
        // Documents saved before kinds were read get theirs (once), and delivery notes are matched to invoices.
        container.appScope.launch {
            runCatching { c.checks.backfill() }
                .onSuccess { n -> if (n > 0) c.log.event("KINDS_READ", "documents" to n) }
                .onFailure { c.log.error("kindsBackfill", it) }
        }
        runCatching { c.importQueue.restore() }.onFailure { c.log.error("restoreQueue", it) }
        container.appScope.launch {
            runCatching { container.repository.cleanupOrphanFiles(c.importQueue.filePaths()) }
        }
        // A new version reads every saved document again and compares (on the phone; see ReadingChecker).
        if (c.readingChecker.due()) container.appScope.launch {
            kotlinx.coroutines.delay(20_000) // after start-up and any pending reading have had the phone first
            while (c.importQueue.busy) kotlinx.coroutines.delay(30_000)
            c.readingChecker.run()
        }
        // Automatic network mode: the public knowledge pack, about once a week, on Wi-Fi only.
        if (c.knowledge.autoCheckDue()) container.appScope.launch {
            kotlinx.coroutines.delay(10_000)
            runCatching { c.knowledge.download() }
        }
        if (!c.settings.categoriesRegrouped) {
            container.appScope.launch {
                runCatching { container.repository.regroupGuessedCategories() }
                    .onSuccess { n -> c.settings.categoriesRegrouped = true; c.log.event("CATEGORIES_REGROUPED", "products" to n) }
                    .onFailure { c.log.error("regroupCategories", it) }
            }
        }
    }
}
