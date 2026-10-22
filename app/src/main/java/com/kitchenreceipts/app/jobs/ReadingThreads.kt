package com.kitchenreceipts.app.jobs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads for the background reading, at Android's background priority: the screen the operator is using (checking
 * the invoice before, typing a correction) gets the processor first, and the reading takes what is left. On an idle
 * phone the reading is as fast as before. The OCR and the AI model run on their own threads, as before.
 */
object ReadingThreads {
    val dispatcher: CoroutineDispatcher by lazy {
        val n = AtomicInteger()
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "reading-${n.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
