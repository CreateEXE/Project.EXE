package com.android.exe

import android.util.Log

object LlamaManager {
    private const val TAG = "LlamaManager"
    
    external fun loadModel(modelPath: String): Boolean
    external fun inferenceNative(prompt: String, nPredict: Int): String
    external fun unloadModel()
    external fun getModelInfo(): String

    fun runInference(prompt: String, maxTokens: Int = 128): String {
        return try {
            inferenceNative(prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            "Error: ${e.message}"
        }
    }

    init {
        try {
            System.loadLibrary("llama")
            Log.d(TAG, "Llama library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load llama library", e)
        }
    }
}
