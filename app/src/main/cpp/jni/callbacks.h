/**
 * callbacks.h: JNI callback infrastructure
 */

#ifndef CALLBACKS_H
#define CALLBACKS_H

#include <jni.h>

/**
 * Called from native inference code to report a token.
 */
void native_on_token(JNIEnv* env, jobject callback, int tokenId, const char* tokenString);

/**
 * Called when inference completes.
 */
void native_on_complete(JNIEnv* env, jobject callback);

/**
 * Called when an error occurs.
 */
void native_on_error(JNIEnv* env, jobject callback, const char* errorMessage);

#endif // CALLBACKS_H
