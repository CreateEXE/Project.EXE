package com.android.exe

import android.util.Log
import kotlinx.coroutines.*

object LlamaManager {
    private const val TAG = "LlamaManager"
    
    external fun loadModel(modelPath: String): Boolean
    external fun inferenceNative(prompt: String, nPredict: Int): String
    external fun unloadModel()
    external fun getModelInfo(): String

    // Status callback interface
    interface InferenceListener {
        fun onInferenceStart(prompt: String)
        fun onInferenceProgress(tokensGenerated: Int)
        fun onInferenceComplete(output: String)
        fun onInferenceError(error: String)
    }

    private var inferenceListener: InferenceListener? = null
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun setInferenceListener(listener: InferenceListener?) {
        inferenceListener = listener
        Log.d(TAG, "Inference listener ${if (listener != null) "set" else "cleared"}")
    }

    /**
     * Run inference asynchronously with callbacks.
     * Allows the UI to show progress while the model is generating text.
     */
    fun runInferenceAsync(
        prompt: String,
        maxTokens: Int = 128,
        onComplete: (String) -> Unit = {}
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                Log.d(TAG, "🤔 Inference starting: \"${prompt.take(40)}...\"")
                inferenceListener?.onInferenceStart(prompt)

                withContext(Dispatchers.Default) {
                    val output = inferenceNative(prompt, maxTokens)
                    
                    Log.d(TAG, "✅ Inference complete: ${output.length} chars")
                    inferenceListener?.onInferenceComplete(output)
                    onComplete(output)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Inference error: ${e.message}", e)
                inferenceListener?.onInferenceError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Synchronous inference (blocking)
     * Use only if you're already on a background thread
     */
    fun runInference(prompt: String, maxTokens: Int = 128): String {
        return try {
            Log.d(TAG, "🤔 Synchronous inference: \"${prompt.take(40)}...\"")
            inferenceListener?.onInferenceStart(prompt)
            
            val output = inferenceNative(prompt, maxTokens)
            
            Log.d(TAG, "✅ Inference complete: ${output.length} chars")
            inferenceListener?.onInferenceComplete(output)
            output
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference error: ${e.message}", e)
            inferenceListener?.onInferenceError(e.message ?: "Unknown error")
            "Error: ${e.message}"
        }
    }

    /**
     * Cancel any in-flight inference
     */
    fun cancelInference() {
        currentJob?.cancel()
        Log.d(TAG, "Inference cancelled")
    }

    fun loadModelWithCallback(
        modelPath: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        Log.d(TAG, "📦 Loading model: $modelPath")
        Thread {
            try {
                val success = loadModel(modelPath)
                if (success) {
                    Log.i(TAG, "✅ Model loaded successfully")
                    val info = try { getModelInfo() } catch (_: Exception) { "unknown" }
                    Log.d(TAG, "Model info: $info")
                    onSuccess()
                } else {
                    Log.e(TAG, "❌ Model load returned false")
                    onError("Failed to load model (returned false)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Model load exception: ${e.message}", e)
                onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    fun unloadModelSafely() {
        try {
            Log.d(TAG, "Unloading model...")
            unloadModel()
            Log.d(TAG, "✅ Model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unloading model: ${e.message}", e)
        }
    }

    fun isLibraryLoaded(): Boolean {
        return try {
            getModelInfo()
            true
        } catch (_: Exception) {
            false
        }
    }

    init {
        try {
            System.loadLibrary("llama")
            Log.i(TAG, "✅ Llama native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load llama library: ${e.message}", e)
        }
    }
}
