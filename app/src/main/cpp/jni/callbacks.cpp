/**
 * callbacks.cpp: JNI callback infrastructure for streaming inference
 *
 * Implements the InferenceCallback interface that native code calls
 * to report tokens back to Kotlin.
 */

#include <jni.h>
#include <android/log.h>
#include <memory>

#define LOG_TAG "InferenceCallbacks"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Called from native inference code to report a generated token.
 * Calls back to Kotlin: InferenceCallback.onToken(tokenId, tokenString)
 */
void native_on_token(JNIEnv* env, jobject callback, int tokenId, const char* tokenString) {
    if (!callback) return;

    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) {
        LOGE("Failed to get callback class");
        return;
    }

    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(ILjava/lang/String;)V");
    if (!onTokenMethod) {
        LOGE("Failed to find onToken method");
        env->DeleteLocalRef(callbackClass);
        return;
    }

    jstring tokenStr = env->NewStringUTF(tokenString);
    if (!tokenStr) {
        LOGE("Failed to create token string");
        env->DeleteLocalRef(callbackClass);
        return;
    }

    env->CallVoidMethod(callback, onTokenMethod, (jint)tokenId, tokenStr);

    env->DeleteLocalRef(tokenStr);
    env->DeleteLocalRef(callbackClass);
}

/**
 * Called to report inference completion.
 */
void native_on_complete(JNIEnv* env, jobject callback) {
    if (!callback) return;

    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) return;

    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
    if (!onCompleteMethod) {
        env->DeleteLocalRef(callbackClass);
        return;
    }

    env->CallVoidMethod(callback, onCompleteMethod);
    env->DeleteLocalRef(callbackClass);
}

/**
 * Called to report an error.
 */
void native_on_error(JNIEnv* env, jobject callback, const char* errorMessage) {
    if (!callback) return;

    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) return;

    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    if (!onErrorMethod) {
        env->DeleteLocalRef(callbackClass);
        return;
    }

    jstring msgStr = env->NewStringUTF(errorMessage);
    if (msgStr) {
        env->CallVoidMethod(callback, onErrorMethod, msgStr);
        env->DeleteLocalRef(msgStr);
    }

    env->DeleteLocalRef(callbackClass);
}
