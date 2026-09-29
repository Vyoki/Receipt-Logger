package com.kitchenreceipts.app.ai

/** Kotlin side of app/src/main/cpp/jni_bridge.cpp (llama.cpp + libmtmd, CPU only, fully on the phone). */
object NativeAi {

    fun interface Listener {
        /** stage 0 = reading the image, 1 = writing the answer (count = pieces written). Return false to stop. */
        fun onProgress(stage: Int, count: Int): Boolean
    }

    /** null when the native library could not be loaded (e.g. an unsupported processor). */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("receipt_ai")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic external fun nativeLoad(backendDir: String, model: String, mmproj: String, threads: Int, errorOut: Array<String?>): Long

    /** Returns [text, error ("" / "cancelled" / message), stats]. */
    @JvmStatic external fun nativeGenerate(
        handle: Long, rgb: ByteArray, width: Int, height: Int, text: String, grammar: String,
        maxTokens: Int, nCtx: Int, listener: Listener?,
    ): Array<String>

    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeCheckGrammar(handle: Long, grammar: String): String
}
