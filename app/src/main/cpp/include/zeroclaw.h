/**
 * zeroclaw.h: Public JNI interface header
 *
 * Defines the C++ interface that Kotlin calls via JNI.
 * Implements the contract between Kotlin (ZeroClawBridge) and native code.
 */

#ifndef ZEROCLAW_H
#define ZEROCLAW_H

#include <jni.h>
#include <cstdint>
#include <memory>
#include <vector>

/**
 * Opaque handle to the native inference engine.
 * Created by nativeInitialize(), passed to all other functions.
 */
using EngineHandle = int64_t;
using ModelHandle = int64_t;

// ============================================================================
// Core Interface
// ============================================================================

/**
 * Initialize the native inference engine.
 * Called once during application startup.
 *
 * @param cacheDir Path to app cache directory (for temp files)
 * @param appDir Path to app data directory (for persistent state)
 * @return Non-zero handle on success, 0 on failure
 */
extern "C" EngineHandle nativeInitialize(const char* cacheDir, const char* appDir);

/**
 * Shutdown the native inference engine and clean up resources.
 *
 * @param handle Engine handle from nativeInitialize()
 */
extern "C" void nativeShutdown(EngineHandle handle);

/**
 * Load a GGUF model file into memory.
 *
 * @param handle Engine handle
 * @param modelPath Absolute path to GGUF file
 * @param gpuLayers Number of layers to offload to GPU (0 = CPU only)
 * @return Non-zero model handle on success, 0 on failure
 */
extern "C" ModelHandle nativeLoadModel(EngineHandle handle, const char* modelPath, int32_t gpuLayers);

/**
 * Unload a model and free its memory.
 *
 * @param handle Engine handle
 * @param modelHandle Model handle from nativeLoadModel()
 * @return true if successful
 */
extern "C" jboolean nativeUnloadModel(EngineHandle handle, ModelHandle modelHandle);

// ============================================================================
// Inference Interface
// ============================================================================

/**
 * Run inference on a prompt (synchronous).
 * Blocks until complete, returns tokens as a direct ByteBuffer.
 *
 * @param handle Engine handle
 * @param modelHandle Model to use
 * @param prompt Input text (UTF-8)
 * @param maxTokens Maximum tokens to generate
 * @return Direct ByteBuffer containing results, or null on error
 *
 * ByteBuffer format (zero-copy):
 *   [token_id (int32)] [token_string_len (int32)] [token_string (UTF-8)]...
 */
extern "C" jobject nativeRunInference(
    JNIEnv* env,
    EngineHandle handle,
    ModelHandle modelHandle,
    const char* prompt,
    int32_t maxTokens
);

/**
 * Stream inference tokens with callbacks (asynchronous).
 * Calls back to Kotlin for each generated token.
 *
 * @param handle Engine handle
 * @param modelHandle Model to use
 * @param prompt Input text
 * @param maxTokens Maximum tokens to generate
 * @param callback Java callback interface (InferenceCallback)
 * @return true if streaming started successfully
 */
extern "C" jboolean nativeStreamInference(
    JNIEnv* env,
    EngineHandle handle,
    ModelHandle modelHandle,
    const char* prompt,
    int32_t maxTokens,
    jobject callback
);

// ============================================================================
// Memory Management
// ============================================================================

/**
 * Query memory statistics from the native layer.
 * Returns a direct ByteBuffer with formatted stats.
 *
 * @param handle Engine handle
 * @return Direct ByteBuffer with memory info, or null on error
 *
 * ByteBuffer format:
 *   [heap_used (int64)] [heap_total (int64)]
 *   [model_memory (int64)] [cache_memory (int64)]
 */
extern "C" jobject nativeGetMemoryStats(JNIEnv* env, EngineHandle handle);

#endif // ZEROCLAW_H
