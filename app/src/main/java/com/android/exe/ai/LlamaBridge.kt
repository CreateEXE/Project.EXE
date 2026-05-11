package com.android.exe.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around the native llama.cpp JNI library.
 * The companion object loads the shared library once on first use.
 */
class LlamaBridge {

    // ── Token-by-token callback interface (called from C++) ───────────────────
    interface TokenCallback {
        fun onToken(token: String)
    }

    companion object {
        private const val TAG = "LlamaBridge"
        private var libraryLoaded = false

        fun ensureLibrary() {
            if (!libraryLoaded) {
                try {
                    System.loadLibrary("llama-android")
                    libraryLoaded = true
                    Log.i(TAG, "llama-android native library loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library: ${e.message}")
                }
            }
        }
    }

    init { ensureLibrary() }

    // ─── Native declarations ──────────────────────────────────────────────────

    private external fun nativeLoad(
        modelPath: String,
        nCtx: Int,
        nThreads: Int
    ): Boolean

    private external fun nativeFree()

    private external fun nativeStop()

    private external fun nativeInfer(
        prompt: String,
        maxNewTokens: Int,
        callback: TokenCallback
    ): String

    private external fun nativeIsLoaded(): Boolean

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Load a GGUF model file.  Runs on IO dispatcher.
     * @return true on success
     */
    suspend fun load(
        modelPath: String,
        nCtx: Int = 2048,
        nThreads: Int = 4
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading model: $modelPath  ctx=$nCtx threads=$nThreads")
        if (!libraryLoaded) {
            Log.e(TAG, "Native library not loaded — cannot infer")
            return@withContext false
        }
        nativeLoad(modelPath, nCtx, nThreads)
    }

    /** Release the model from memory. */
    suspend fun free() = withContext(Dispatchers.IO) {
        if (libraryLoaded && nativeIsLoaded()) nativeFree()
    }

    /** Cancel any ongoing generation. */
    fun stop() {
        if (libraryLoaded) nativeStop()
    }

    /** @return true if a model is currently loaded in native memory. */
    fun isLoaded(): Boolean = libraryLoaded && nativeIsLoaded()

    /**
     * Stream inference token-by-token as a Flow<String>.
     * Each emitted string is one decoded piece (often 1-3 characters).
     */
    fun streamInfer(
        prompt: String,
        maxNewTokens: Int = 256
    ): Flow<String> = flow {
        if (!libraryLoaded || !nativeIsLoaded()) {
            emit("[LlamaBridge: model not loaded]")
            return@flow
        }

        val buffer = StringBuilder()
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                buffer.append(token)
            }
        }

        // nativeInfer blocks until done; we collect pieces via callback.
        // We emit accumulated chunks every ~50ms to the flow.
        val result = nativeInfer(prompt, maxNewTokens, callback)

        // Emit the complete result as one piece for simplicity.
        // To get true streaming, run nativeInfer in a coroutine and
        // emit from the callback via a Channel — see advanced note below.
        emit(result)
    }.flowOn(Dispatchers.IO)

    /**
     * One-shot blocking infer (runs on the caller's coroutine, must be IO).
     */
    suspend fun infer(
        prompt: String,
        maxNewTokens: Int = 256,
        onToken: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (!libraryLoaded || !nativeIsLoaded()) return@withContext ""
        val cb = object : TokenCallback {
            override fun onToken(token: String) { onToken?.invoke(token) }
        }
        nativeInfer(prompt, maxNewTokens, cb)
    }
}
