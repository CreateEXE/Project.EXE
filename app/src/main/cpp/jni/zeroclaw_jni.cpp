/**
 * zeroclaw_jni.cpp: Main JNI entry point and initialization
 *
 * Implements the Java↔C++ bridge as defined in ZeroClawBridge.kt
 * Manages JNI environment, exception handling, and lifecycle.
 */

#include "zeroclaw.h"
#include "inference_engine.h"
#include <android/log.h>
#include <memory>
#include <unordered_map>

#define LOG_TAG "ZeroClawJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global engine registry (thread-safe in future)
static std::unordered_map<int64_t, std::shared_ptr<InferenceEngine>> g_engines;
static int64_t g_nextHandle = 1;

// ============================================================================
// JNI_OnLoad: Called when libzeroclaw.so is loaded by ClassLoader
// ============================================================================

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad called");
    return JNI_VERSION_1_6;
}

// ============================================================================
// JNI_OnUnload: Called when libzeroclaw.so is unloaded
// ============================================================================

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload called");
    g_engines.clear();
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeInitialize
// ============================================================================

extern "C" jlong Java_com_android_exe_jni_ZeroClawBridge_nativeInitialize(
    JNIEnv* env,
    jobject thiz,
    jstring cacheDir,
    jstring appDir
) {
    try {
        const char* cacheDirStr = env->GetStringUTFChars(cacheDir, nullptr);
        const char* appDirStr = env->GetStringUTFChars(appDir, nullptr);

        LOGI("Initializing InferenceEngine: cache=%s, app=%s", cacheDirStr, appDirStr);

        auto engine = std::make_shared<InferenceEngine>(cacheDirStr, appDirStr);
        if (!engine->initialize()) {
            LOGE("Failed to initialize engine");
            env->ReleaseStringUTFChars(cacheDir, cacheDirStr);
            env->ReleaseStringUTFChars(appDir, appDirStr);
            return 0;
        }

        int64_t handle = g_nextHandle++;
        g_engines[handle] = engine;

        LOGI("Engine initialized: handle=%ld", handle);

        env->ReleaseStringUTFChars(cacheDir, cacheDirStr);
        env->ReleaseStringUTFChars(appDir, appDirStr);

        return handle;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeInitialize: %s", e.what());
        return 0;
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeShutdown
// ============================================================================

extern "C" void Java_com_android_exe_jni_ZeroClawBridge_nativeShutdown(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    try {
        auto it = g_engines.find(handle);
        if (it != g_engines.end()) {
            LOGI("Shutting down engine: handle=%ld", handle);
            it->second->shutdown();
            g_engines.erase(it);
        } else {
            LOGE("Invalid engine handle: %ld", handle);
        }
    } catch (const std::exception& e) {
        LOGE("Exception in nativeShutdown: %s", e.what());
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeLoadModel
// ============================================================================

extern "C" jlong Java_com_android_exe_jni_ZeroClawBridge_nativeLoadModel(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jstring modelPath,
    jint gpuLayers
) {
    try {
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Invalid engine handle: %ld", handle);
            return 0;
        }

        const char* pathStr = env->GetStringUTFChars(modelPath, nullptr);
        LOGI("Loading model: path=%s, gpu_layers=%d", pathStr, gpuLayers);

        auto modelHandle = it->second->loadModel(pathStr, gpuLayers);

        env->ReleaseStringUTFChars(modelPath, pathStr);

        if (modelHandle > 0) {
            LOGI("Model loaded: handle=%ld", modelHandle);
        } else {
            LOGE("Failed to load model");
        }

        return modelHandle;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeLoadModel: %s", e.what());
        return 0;
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeUnloadModel
// ============================================================================

extern "C" jboolean Java_com_android_exe_jni_ZeroClawBridge_nativeUnloadModel(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong modelHandle
) {
    try {
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Invalid engine handle: %ld", handle);
            return JNI_FALSE;
        }

        LOGI("Unloading model: handle=%ld", modelHandle);
        bool success = it->second->unloadModel(modelHandle);
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeUnloadModel: %s", e.what());
        return JNI_FALSE;
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeRunInference
// ============================================================================

extern "C" jobject Java_com_android_exe_jni_ZeroClawBridge_nativeRunInference(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong modelHandle,
    jstring prompt,
    jint maxTokens
) {
    try {
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Invalid engine handle: %ld", handle);
            return nullptr;
        }

        const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
        LOGD("Running inference: prompt_len=%zu, max_tokens=%d",
             strlen(promptStr), maxTokens);

        auto resultBuffer = it->second->runInference(env, modelHandle, promptStr, maxTokens);

        env->ReleaseStringUTFChars(prompt, promptStr);

        return resultBuffer;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeRunInference: %s", e.what());
        return nullptr;
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeStreamInference
// ============================================================================

extern "C" jboolean Java_com_android_exe_jni_ZeroClawBridge_nativeStreamInference(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong modelHandle,
    jstring prompt,
    jint maxTokens,
    jobject callback
) {
    try {
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Invalid engine handle: %ld", handle);
            return JNI_FALSE;
        }

        const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
        LOGD("Starting inference stream: prompt_len=%zu, max_tokens=%d",
             strlen(promptStr), maxTokens);

        bool success = it->second->streamInference(env, modelHandle, promptStr, maxTokens, callback);

        env->ReleaseStringUTFChars(prompt, promptStr);

        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeStreamInference: %s", e.what());
        return JNI_FALSE;
    }
}

// ============================================================================
// Java_com_android_exe_jni_ZeroClawBridge_nativeGetMemoryStats
// ============================================================================

extern "C" jobject Java_com_android_exe_jni_ZeroClawBridge_nativeGetMemoryStats(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    try {
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Invalid engine handle: %ld", handle);
            return nullptr;
        }

        return it->second->getMemoryStats(env);
    } catch (const std::exception& e) {
        LOGE("Exception in nativeGetMemoryStats: %s", e.what());
        return nullptr;
    }
}
