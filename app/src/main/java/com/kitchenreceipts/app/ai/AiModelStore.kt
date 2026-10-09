package com.kitchenreceipts.app.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A model the app knows how to use, with where to download its two files. */
data class AiModelOption(
    val id: String,
    val label: String,
    val approxGb: String,
    val modelUrl: String,
    val mmprojUrl: String,
    /** Phone memory needed to run it comfortably. */
    val minRamGb: Int,
)

/**
 * The AI model files, kept in the app's private storage (never shared, never backed up).
 * They are downloaded by the phone's browser (the app itself has no internet permission) and then
 * copied in with the file picker.
 */
class AiModelStore(private val context: Context) {

    private val dir = File(context.filesDir, "ai").apply { mkdirs() }
    val modelFile = File(dir, "model.gguf")
    val mmprojFile = File(dir, "mmproj.gguf")
    private val nameFile = File(dir, "name.txt")

    val installed: Boolean get() = modelFile.length() > 0 && mmprojFile.length() > 0
    val installedName: String? get() = if (installed) nameFile.takeIf { it.exists() }?.readText()?.trim() else null
    val sizeBytes: Long get() = modelFile.length() + mmprojFile.length()

    /** The phone has an arm64 or x86_64 processor, enough memory, and the native reader loaded. */
    fun deviceSupport(option: AiModelOption = OPTIONS.first()): DeviceSupport {
        val abiOk = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = (mi.totalMem / (1024.0 * 1024 * 1024))
        return DeviceSupport(abiOk && NativeAi.available, ramGb, ramGb + 0.5 >= option.minRamGb)
    }

    data class DeviceSupport(val nativeOk: Boolean, val ramGb: Double, val enoughRam: Boolean)

    /**
     * Copies the two picked files in. Which one is the vision part ("mmproj") is told by its name, or failing
     * that by size (it is the smaller one). Both must be GGUF files.
     */
    suspend fun import(uris: List<Uri>, onProgress: (copied: Long, total: Long) -> Unit): String = withContext(Dispatchers.IO) {
        require(uris.size == 2) { "Pick both files: the model and the vision (mmproj) file" }
        val cr = context.contentResolver
        data class Picked(val uri: Uri, val name: String, val size: Long)
        val picked = uris.map { uri ->
            var name = uri.lastPathSegment ?: ""
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            Picked(uri, name, size)
        }
        for (p in picked) {
            cr.openInputStream(p.uri)?.use { s ->
                val magic = ByteArray(4)
                if (s.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                    throw IOException("\"${p.name}\" is not a GGUF model file")
                }
            } ?: throw IOException("Cannot open \"${p.name}\"")
        }
        val mm = picked.firstOrNull { it.name.contains("mmproj", ignoreCase = true) }
            ?: picked.minByOrNull { if (it.size < 0) Long.MAX_VALUE else it.size }!!
        val model = picked.first { it !== mm }
        val total = picked.sumOf { maxOf(0L, it.size) }
        var copied = 0L
        val tmpModel = File(dir, "model.part")
        val tmpMm = File(dir, "mmproj.part")
        try {
            for ((p, target) in listOf(model to tmpModel, mm to tmpMm)) {
                cr.openInputStream(p.uri)!!.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            onProgress(copied, total)
                        }
                    }
                }
            }
            modelFile.delete(); mmprojFile.delete()
            if (!tmpModel.renameTo(modelFile) || !tmpMm.renameTo(mmprojFile)) throw IOException("Could not store the model")
            val label = model.name.removeSuffix(".gguf")
            nameFile.writeText(label)
            label
        } finally {
            tmpModel.delete(); tmpMm.delete()
        }
    }

    fun remove() {
        modelFile.delete(); mmprojFile.delete(); nameFile.delete()
    }

    companion object {
        private const val HF = "https://huggingface.co/Qwen"
        private const val TRAINED = "https://huggingface.co/ArdentSun/kitchen-reader-2b"
        const val TRAINED_ID = "kitchen-2b"
        /** Checked in CI on a synthetic invoice: both read every line right when given the OCR text; 2B takes about half the time. */
        val OPTIONS = listOf(
            // Qwen3-VL 2B trained on the app's own questions (invented documents only): see tools/training.
            AiModelOption(
                id = TRAINED_ID, label = "Kitchen reader 2B", approxGb = "1.6",
                modelUrl = "$TRAINED/resolve/main/kitchen-reader-2b-Q4_K_M.gguf?download=true",
                mmprojUrl = "$TRAINED/resolve/main/mmproj-kitchen-reader-2b-Q8_0.gguf?download=true",
                minRamGb = 6,
            ),
            AiModelOption(
                id = "qwen3vl-2b", label = "Qwen3-VL 2B", approxGb = "1.5",
                modelUrl = "$HF/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-Q4_K_M.gguf?download=true",
                mmprojUrl = "$HF/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf?download=true",
                minRamGb = 6,
            ),
            AiModelOption(
                id = "qwen3vl-4b", label = "Qwen3-VL 4B", approxGb = "3",
                modelUrl = "$HF/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3VL-4B-Instruct-Q4_K_M.gguf?download=true",
                mmprojUrl = "$HF/Qwen3-VL-4B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf?download=true",
                minRamGb = 8,
            ),
        )
    }
}
