#include <jni.h>
#include <string>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global llama context (simplified - use proper state management in production)
static llama_context* g_ctx = nullptr;
static llama_model* g_model = nullptr;

extern "C" {

/**
 * Load a GGUF model file
 * @param env JNI environment
 * @param modelPath path to the .gguf model file
 * @return true if successful
 */
JNIEXPORT jboolean JNICALL
Java_com_android_exe_LlamaManager_loadModel(
    JNIEnv* env,
    jobject obj,
    jstring modelPath) {

    const char* model_path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", model_path);

    // Initialize llama backend
    llama_backend_init();

    // Load model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99; // Offload to GPU if available
    
    g_model = llama_load_model_from_file(model_path, model_params);
    if (!g_model) {
        LOGE("Failed to load model");
        env->ReleaseStringUTFChars(modelPath, model_path);
        return JNI_FALSE;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048; // Context size
    ctx_params.n_threads = 4; // Adjust based on device
    
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, model_path);
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    env->ReleaseStringUTFChars(modelPath, model_path);
    return JNI_TRUE;
}

/**
 * Run inference on a prompt
 * @param prompt the input text
 * @param n_predict number of tokens to generate
 * @return generated text
 */
JNIEXPORT jstring JNICALL
Java_com_android_exe_LlamaManager_inferenceNative(
    JNIEnv* env,
    jobject obj,
    jstring prompt,
    jint n_predict) {

    if (!g_ctx || !g_model) {
        LOGE("Model not loaded");
        return env->NewStringUTF("Error: Model not loaded");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Running inference with prompt: %s", prompt_str);

    // Tokenize input
    std::vector<llama_token> tokens = llama_tokenize(g_model, prompt_str, true);
    if (tokens.empty()) {
        LOGE("Failed to tokenize prompt");
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Error: Tokenization failed");
    }

    // Process tokens
    llama_batch batch = llama_batch_init(512, 0, 1);
    
    for (int i = 0; i < (int)tokens.size(); i++) {
        llama_batch_add(&batch, tokens[i], i, {0}, false);
    }
    
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to decode tokens");
        llama_batch_free(batch);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Error: Decoding failed");
    }

    // Generate output
    std::string result;
    int n_cur = tokens.size();
    
    for (int i = 0; i < n_predict && n_cur < 2048; i++) {
        // Sample next token
        llama_token next = llama_sampler_sample_dist(g_ctx, nullptr);
        
        if (next == llama_token_eos(g_model)) {
            break;
        }

        tokens.push_back(next);
        result += llama_token_to_piece(g_model, next);

        // Prepare batch for next iteration
        llama_batch_clear(&batch);
        llama_batch_add(&batch, next, n_cur, {0}, true);
        batch.logits[batch.n_tokens - 1] = true;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode at step %d", i);
            break;
        }

        n_cur++;
    }

    llama_batch_free(batch);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("Inference complete: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/**
 * Free model resources
 */
JNIEXPORT void JNICALL
Java_com_android_exe_LlamaManager_unloadModel(
    JNIEnv* env,
    jobject obj) {

    LOGI("Unloading model");
    
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_free();
    LOGI("Model unloaded");
}

/**
 * Get model info
 */
JNIEXPORT jstring JNICALL
Java_com_android_exe_LlamaManager_getModelInfo(
    JNIEnv* env,
    jobject obj) {

    if (!g_model) {
        return env->NewStringUTF("No model loaded");
    }

    std::string info = "Model loaded successfully";
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
