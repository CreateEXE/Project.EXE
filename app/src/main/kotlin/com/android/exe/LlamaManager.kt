package com.android.exe

object LlamaManager {
    
    external fun loadModel(modelPath: String): Boolean
    external fun inferenceNative(prompt: String, nPredict: Int): String
    external fun unloadModel()
    external fun getModelInfo(): String

    fun runInference(prompt: String, maxTokens: Int = 128): String {
        return try {
            inferenceNative(prompt, maxTokens)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("llama")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }
}
