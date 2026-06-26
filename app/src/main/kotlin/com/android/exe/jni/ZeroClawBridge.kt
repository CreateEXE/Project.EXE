package com.android.exe.jni

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * ZeroClawBridge: Kotlin JNI wrapper for libzeroclaw.so
 *
 * Purpose:
 * 1. Expose native C++/Rust functions via Kotlin interface
 * 2. Handle env.new_direct_byte_buffer zero-copy memory pointers
 * 3. Manage JNI lifecycle and thread safety
 * 4. Implement native callbacks for events from C++ layer
 *
 * Architecture:
 * - All ByteBuffer operations are direct (off-heap) for zero-copy
 * - Thread-safe access via ReentrantReadWriteLock
 * - Native layer manages memory; Kotlin holds weak references
 *
 * Target: 6GB RAM Snapdragon 6 Gen 1, low-end device optimization
 */
object ZeroClawBridge {

    private const val TAG = "ZeroClawBridge"

    // State management
    private val lock = ReentrantReadWriteLock()
    @Volatile
    private var initialized = false

    // Native references
    private var nativeHandle: Long = 0L

    /**
     * Initialize JNI bridge and native backend.
     * Must be called once during application startup.
     *
     * @return true if successful, false otherwise
     */
    fun initialize(context: Context): Boolean = lock.write {
        if (initialized) {
            Log.w(TAG, "Already initialized, skipping")
            return true
        }

        return try {
            // Get cache directory for native temp files
            val cacheDir = context.cacheDir.absolutePath
            val appDir = context.filesDir.absolutePath

            // Call native initialization
            nativeHandle = nativeInitialize(cacheDir, appDir)
            initialized = nativeHandle != 0L

            if (initialized) {
                Log.i(TAG, "Native backend initialized: handle=$nativeHandle")
            } else {
                Log.e(TAG, "Native initialization returned null handle")
            }
            initialized
        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            false
        }
    }

    /**
     * Shutdown JNI backend and clean up resources.
     */
    fun shutdown() = lock.write {
        if (!initialized) return

        try {
            if (nativeHandle != 0L) {
                nativeShutdown(nativeHandle)
                Log.i(TAG, "Native backend shutdown")
            }
            nativeHandle = 0L
            initialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during shutdown", e)
        }
    }

    /**
     * Load a GGUF model file into the inference engine.
     * Returns a model handle for later inference calls.
     *
     * @param modelPath Absolute path to GGUF file
     * @param gpuLayers Number of layers to offload to GPU (0 = CPU only)
     * @return Model handle (>0 on success, 0 on failure)
     */
    fun loadModel(modelPath: String, gpuLayers: Int = 0): Long = lock.read {
        if (!initialized) {
            Log.e(TAG, "Bridge not initialized")
            return 0L
        }

        return try {
            val modelHandle = nativeLoadModel(nativeHandle, modelPath, gpuLayers)
            if (modelHandle > 0) {
                Log.i(TAG, "Model loaded: path=$modelPath, handle=$modelHandle, gpu_layers=$gpuLayers")
            } else {
                Log.e(TAG, "Failed to load model: $modelPath")
            }
            modelHandle
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model", e)
            0L
        }
    }

    /**
     * Unload a model and free its memory.
     *
     * @param modelHandle Handle returned from loadModel()
     * @return true if successful
     */
    fun unloadModel(modelHandle: Long): Boolean = lock.read {
        if (!initialized) return false

        return try {
            nativeUnloadModel(nativeHandle, modelHandle)
            Log.i(TAG, "Model unloaded: handle=$modelHandle")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception unloading model", e)
            false
        }
    }

    /**
     * Run inference on a prompt.
     * Returns tokens as a direct ByteBuffer with zero-copy semantics.
     *
     * @param modelHandle Model to use
     * @param prompt Input text
     * @param maxTokens Maximum tokens to generate
     * @return Direct ByteBuffer containing token IDs + logits, or null on error
     */
    fun runInference(
        modelHandle: Long,
        prompt: String,
        maxTokens: Int
    ): ByteBuffer? = lock.read {
        if (!initialized) {
            Log.e(TAG, "Bridge not initialized")
            return null
        }

        return try {
            // Call native inference
            val resultBuffer = nativeRunInference(nativeHandle, modelHandle, prompt, maxTokens)
            if (resultBuffer != null) {
                resultBuffer.order(ByteOrder.nativeOrder())
                Log.d(TAG, "Inference complete: ${resultBuffer.remaining()} bytes")
            } else {
                Log.w(TAG, "Inference returned null buffer")
            }
            resultBuffer
        } catch (e: Exception) {
            Log.e(TAG, "Exception during inference", e)
            null
        }
    }

    /**
     * Stream inference tokens with callback.
     * Calls onToken() for each generated token in real-time.
     *
     * @param modelHandle Model to use
     * @param prompt Input text
     * @param maxTokens Maximum tokens
     * @param onToken Callback invoked for each token
     * @return true if successful
     */
    fun streamInference(
        modelHandle: Long,
        prompt: String,
        maxTokens: Int,
        onToken: (tokenId: Int, tokenString: String) -> Unit
    ): Boolean = lock.read {
        if (!initialized) return false

        return try {
            // Create callback wrapper
            val callback = object : InferenceCallback {
                override fun onToken(tokenId: Int, tokenString: String) {
                    onToken(tokenId, tokenString)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Inference error: $message")
                }

                override fun onComplete() {
                    Log.d(TAG, "Inference complete")
                }
            }

            nativeStreamInference(nativeHandle, modelHandle, prompt, maxTokens, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during stream inference", e)
            false
        }
    }

    /**
     * Get memory stats from native layer.
     * Returns a direct ByteBuffer containing formatted stats.
     *
     * @return ByteBuffer with memory info, or null on error
     */
    fun getMemoryStats(): ByteBuffer? = lock.read {
        if (!initialized) return null

        return try {
            val statsBuffer = nativeGetMemoryStats(nativeHandle)
            if (statsBuffer != null) {
                statsBuffer.order(ByteOrder.nativeOrder())
            }
            statsBuffer
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting memory stats", e)
            null
        }
    }

    /**
     * Allocate a direct ByteBuffer from native heap.
     * Used for zero-copy data exchange between Kotlin and C++.
     *
     * @param size Bytes to allocate
     * @return Direct ByteBuffer, or null on failure
     */
    fun allocateBuffer(size: Int): ByteBuffer? {
        return try {
            val buffer = ByteBuffer.allocateDirect(size)
            buffer.order(ByteOrder.nativeOrder())
            buffer
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Failed to allocate ${size}B direct buffer", e)
            null
        }
    }

    /**
     * Free a direct ByteBuffer.
     * Called when done with zero-copy buffer from native layer.
     *
     * @param buffer Buffer to free
     */
    fun freeBuffer(buffer: ByteBuffer?) {
        if (buffer == null) return
        // Direct buffers are freed by GC; explicit cleanup if needed
        // Can be extended for custom allocator if performance critical
    }

    // ========================
    // Native Interface Methods
    // ========================

    /**
     * Called from native code to report tokens during streaming inference.
     * This method is invoked on the native thread pool.
     */
    @Suppress("unused")
    fun onNativeToken(tokenId: Int, tokenString: String) {
        Log.v(TAG, "Native token callback: id=$tokenId, str='$tokenString'")
    }

    /**
     * Called from native code to report errors.
     */
    @Suppress("unused")
    fun onNativeError(errorCode: Int, errorMessage: String) {
        Log.e(TAG, "Native error [$errorCode]: $errorMessage")
    }

    // ========================
    // Native Function Bindings
    // ========================

    private external fun nativeInitialize(cacheDir: String, appDir: String): Long

    private external fun nativeShutdown(handle: Long)

    private external fun nativeLoadModel(handle: Long, path: String, gpuLayers: Int): Long

    private external fun nativeUnloadModel(handle: Long, modelHandle: Long): Boolean

    private external fun nativeRunInference(
        handle: Long,
        modelHandle: Long,
        prompt: String,
        maxTokens: Int
    ): ByteBuffer?

    private external fun nativeStreamInference(
        handle: Long,
        modelHandle: Long,
        prompt: String,
        maxTokens: Int,
        callback: InferenceCallback
    ): Boolean

    private external fun nativeGetMemoryStats(handle: Long): ByteBuffer?
}

/**
 * Callback interface for streaming inference events.
 * Implemented by native code to report tokens in real-time.
 */
interface InferenceCallback {
    fun onToken(tokenId: Int, tokenString: String)
    fun onError(message: String)
    fun onComplete()
}
