/**
 * memory_manager.h: Zero-copy memory utilities
 */

#ifndef MEMORY_MANAGER_H
#define MEMORY_MANAGER_H

#include <jni.h>
#include <cstddef>

/**
 * Create a direct ByteBuffer from existing memory (zero-copy).
 */
jobject create_direct_byte_buffer(JNIEnv* env, const void* data, size_t size);

/**
 * Create a direct ByteBuffer by copying data to new heap allocation.
 */
jobject create_owned_byte_buffer(JNIEnv* env, const void* data, size_t size);

#endif // MEMORY_MANAGER_H
