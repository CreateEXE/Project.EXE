package com.projectexe.ai.engine.local

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Thin Kotlin facade over the C++ JNI shim in /cpp/exe_jni.cpp. */
class LlamaCpp(private val appCtx: Context) {

    companion object {
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

    @Volatile private var loadedPath: String? = null
    private val lock = Any()

    fun isModelLoaded() = loadedPath != null

    /** Resolve a content:// URI to a real path llama.cpp can mmap. */
    @Synchronized
    fun ensureModelLoaded(uri: Uri, ctxSize: Int): Result<Unit> {
        if (!nativeReady) return Result.failure(IllegalStateException(
            "Offline engine not available in this build (${nativeLoadError ?: "missing libexe_native"})."))
        return try {
            val file = materialize(uri)
            if (loadedPath == file.absolutePath) return Result.success(Unit)
            release()
            val ok = nativeLoad(file.absolutePath, ctxSize)
            if (!ok) return Result.failure(IllegalStateException("nativeLoad returned false"))
            loadedPath = file.absolutePath
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun release() {
        if (!nativeReady) return
        synchronized(lock) {
            try { nativeRelease() } catch (_: Throwable) {}
            loadedPath = null
        }
    }

    fun generate(prompt: String, maxTokens: Int = 256, temperature: Float = 0.8f): Result<String> {
        if (!nativeReady) return Result.failure(IllegalStateException("Offline engine unavailable."))
        if (loadedPath == null) return Result.failure(IllegalStateException("No model loaded."))
        return try {
            val out = nativeGenerate(prompt, maxTokens, temperature) ?: ""
            if (out.startsWith("ERROR:")) Result.failure(IllegalStateException(out.removePrefix("ERROR:").trim()))
            else Result.success(out.trim())
        } catch (e: Throwable) { Result.failure(e) }
    }

    private fun materialize(uri: Uri): File {
        if (uri.scheme == "file") return File(requireNotNull(uri.path))
        // For content:// URIs we copy on first use to a private file llama.cpp can mmap.
        val cache = File(appCtx.filesDir, "models").apply { mkdirs() }
        val dst   = File(cache, "active.gguf")
        appCtx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open GGUF URI: $uri" }
            FileOutputStream(dst).use { out -> input.copyTo(out, 1 shl 20) }
        }
        return dst
    }

    private external fun nativeLoad(path: String, nCtx: Int): Boolean
    private external fun nativeRelease()
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float): String?
}
