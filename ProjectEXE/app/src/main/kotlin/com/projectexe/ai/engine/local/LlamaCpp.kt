package com.projectexe.ai.engine.local

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Thin Kotlin facade over the C++ JNI shim in /cpp/exe_jni.cpp.
 *
 * Two slots are supported (0 and 1) so the dual-agent pipeline can keep
 * Persona + Factual GGUFs loaded simultaneously.
 */
class LlamaCpp(private val appCtx: Context) {

    companion object {
        const val SLOT_PERSONA = 0
        const val SLOT_FACTUAL = 1

        private const val TAG = "EXE.LlamaCpp"
        @Volatile private var nativeReady = false
        @Volatile private var nativeLoadError: String? = null
        init {
            try { System.loadLibrary("exe_native"); nativeReady = true }
            catch (t: Throwable) {
                nativeLoadError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Native lib not loaded: $nativeLoadError")
            }
        }
        fun nativeAvailable() = nativeReady
        fun nativeLoadFailure() = nativeLoadError
    }

    private val loadedPaths = arrayOfNulls<String>(2)
    private val locks = Array(2) { Any() }

    fun isModelLoaded(slot: Int) = slot in 0..1 && loadedPaths[slot] != null

    fun ensureModelLoaded(slot: Int, uri: Uri, ctxSize: Int): Result<Unit> {
        if (slot !in 0..1) return Result.failure(IllegalArgumentException("Bad slot $slot"))
        if (!nativeReady) return Result.failure(IllegalStateException(
            "Offline engine not available in this build (${nativeLoadError ?: "missing libexe_native"})."))
        return try {
            synchronized(locks[slot]) {
                val file = materialize(slot, uri)
                if (loadedPaths[slot] == file.absolutePath) return Result.success(Unit)
                releaseLocked(slot)
                val ok = nativeLoad(slot, file.absolutePath, ctxSize)
                if (!ok) return Result.failure(IllegalStateException("nativeLoad returned false"))
                loadedPaths[slot] = file.absolutePath
                Result.success(Unit)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    fun release(slot: Int) {
        if (slot !in 0..1 || !nativeReady) return
        synchronized(locks[slot]) { releaseLocked(slot) }
    }

    private fun releaseLocked(slot: Int) {
        try { nativeRelease(slot) } catch (_: Throwable) {}
        loadedPaths[slot] = null
    }

    fun generate(slot: Int, prompt: String, maxTokens: Int = 256, temperature: Float = 0.8f): Result<String> {
        if (slot !in 0..1) return Result.failure(IllegalArgumentException("Bad slot $slot"))
        if (!nativeReady) return Result.failure(IllegalStateException("Offline engine unavailable."))
        if (loadedPaths[slot] == null) return Result.failure(IllegalStateException("No model loaded in slot $slot."))
        return try {
            val out = nativeGenerate(slot, prompt, maxTokens, temperature) ?: ""
            if (out.startsWith("ERROR:")) Result.failure(IllegalStateException(out.removePrefix("ERROR:").trim()))
            else Result.success(out.trim())
        } catch (e: Throwable) { Result.failure(e) }
    }

    private fun materialize(slot: Int, uri: Uri): File {
        if (uri.scheme == "file") return File(requireNotNull(uri.path))
        val cache = File(appCtx.filesDir, "models").apply { mkdirs() }
        val dst   = File(cache, "slot$slot.gguf")
        appCtx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open GGUF URI: $uri" }
            FileOutputStream(dst).use { out -> input.copyTo(out, 1 shl 20) }
        }
        return dst
    }

    private external fun nativeLoad(slot: Int, path: String, nCtx: Int): Boolean
    private external fun nativeRelease(slot: Int)
    private external fun nativeGenerate(slot: Int, prompt: String, maxTokens: Int, temperature: Float): String?
}
