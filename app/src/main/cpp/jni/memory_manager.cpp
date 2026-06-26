/**
 * memory_manager.cpp: Zero-copy memory utilities for JNI
 *
 * Implements efficient ByteBuffer allocation and management
 * for passing data between Kotlin and native code.
 */

#include "memory_manager.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "MemoryManager"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

jobject create_direct_byte_buffer(JNIEnv* env, const void* data, size_t size) {
    jobject buffer = env->NewDirectByteBuffer((void*)data, (jlong)size);
    if (!buffer) {
        LOGD("Failed to create direct ByteBuffer");
        return nullptr;
    }
    return buffer;
}

jobject create_owned_byte_buffer(JNIEnv* env, const void* data, size_t size) {
    void* heap_data = malloc(size);
    if (!heap_data) {
        LOGD("Failed to allocate ByteBuffer heap");
        return nullptr;
    }
    memcpy(heap_data, data, size);
    return create_direct_byte_buffer(env, heap_data, size);
}
