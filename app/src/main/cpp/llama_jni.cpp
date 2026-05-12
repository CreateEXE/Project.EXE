#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ─────────────────────────────────────────────────────────
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_stop_generation{false};

// ── Helper: jstring → std::string ────────────────────────────────────────────
static std::string jstr(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

extern "C" {

// ── Load model ──────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_android_exe_ai_LlamaBridge_nativeLoad(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath,
        jint    nCtx,
        jint    nThreads)
{
    if (g_model) {
        // Already loaded — free previous
        llama_sampler_free(g_sampler); g_sampler = nullptr;
        llama_free(g_ctx);             g_ctx     = nullptr;
        llama_model_free(g_model);     g_model   = nullptr;
    }

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU-only; set >0 for Vulkan once enabled

    std::string path = jstr(env, modelPath);
    LOGI("Loading model: %s", path.c_str());

    // Use the updated API: llama_model_load_from_file
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = static_cast<uint32_t>(nCtx);
    cparams.n_threads = static_cast<uint32_t>(nThreads);
    cparams.n_threads_batch = static_cast<uint32_t>(nThreads);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    // Get the vocab from the model
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Failed to get vocab");
        llama_free(g_ctx);
        llama_model_free(g_model);
        g_ctx = nullptr;
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Build a sampler chain
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Model loaded OK. Vocab=%d, n_ctx=%d", llama_vocab_n_tokens(vocab), nCtx);
    return JNI_TRUE;
}

// ── Free model ──────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_android_exe_ai_LlamaBridge_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/)
{
    g_stop_generation = true;
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    llama_backend_free();
    LOGI("Model freed");
}

// ── Stop current generation ───────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_android_exe_ai_LlamaBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/)
{
    g_stop_generation = true;
}

// ── Stream inference ────────────────────────────────────────────────────────
// Calls back tokenCallback(token: String) on the Java side for each generated token.
JNIEXPORT jstring JNICALL
Java_com_android_exe_ai_LlamaBridge_nativeInfer(
        JNIEnv*  env,
        jobject  thiz,
        jstring  jPrompt,
        jint     maxNewTokens,
        jobject  callback)   // TokenCallback interface
{
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("nativeInfer: model not loaded");
        return env->NewStringUTF("[ERROR: model not loaded]");
    }

    g_stop_generation = false;
    std::string prompt = jstr(env, jPrompt);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Failed to get vocab in inference");
        return env->NewStringUTF("[ERROR: vocab not available]");
    }

    // Tokenise the prompt
    // New API: llama_tokenize returns the number of tokens (positive value)
    const int n_prompt_tokens = llama_tokenize(
            vocab, prompt.c_str(), (int32_t)prompt.size(),
            nullptr, 0, /*add_special=*/true, /*parse_special=*/true);

    if (n_prompt_tokens < 0) {
        LOGE("Tokenize failed: negative token count");
        return env->NewStringUTF("[ERROR: tokenize count]");
    }

    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                       prompt_tokens.data(), (int32_t)prompt_tokens.size(),
                       true, true) < 0) {
        LOGE("Tokenize failed");
        return env->NewStringUTF("[ERROR: tokenize]");
    }

    // Eval the prompt batch
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(),
                                            (int32_t)prompt_tokens.size());
    if (llama_decode(g_ctx, batch)) {
        LOGE("llama_decode failed for prompt");
        return env->NewStringUTF("[ERROR: decode prompt]");
    }

    // Locate the callback method
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    // Generation loop
    std::string full_response;
    const llama_token eos = llama_vocab_eos(vocab);
    int n_ctx_used = (int)prompt_tokens.size();

    for (int i = 0; i < maxNewTokens && !g_stop_generation; i++) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (new_token == eos) break;

        // Decode token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        buf[n] = '\0';

        std::string piece(buf, n);
        full_response += piece;

        // Fire callback with this piece
        if (onToken) {
            jstring jPiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        // Feed the new token back
        n_ctx_used++;
        if (n_ctx_used >= (int)llama_n_ctx(g_ctx)) {
            LOGI("Context full, stopping");
            break;
        }

        llama_batch next = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next)) {
            LOGE("llama_decode failed mid-generation");
            break;
        }
    }

    // Clear the KV cache for the next inference
    llama_kv_cache_seq_rm(g_ctx, -1, 0, -1);

    return env->NewStringUTF(full_response.c_str());
}

// ── Query: is a model loaded? ─────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_android_exe_ai_LlamaBridge_nativeIsLoaded(JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
