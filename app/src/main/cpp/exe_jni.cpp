// JNI shim for the EXE offline (llama.cpp) engines.
//
// Supports TWO independent slots so the dual-agent pipeline (Persona + Factual)
// can each hold their own GGUF model loaded in memory at the same time.
//
// Build modes:
//   EXE_HAVE_LLAMA defined → real llama.cpp wrapper.
//   undefined              → returns "ERROR: ENGINE_UNAVAILABLE" so the app
//                            gracefully falls back to the online engine.

#include <jni.h>
#include <string>
#include <mutex>
#include <vector>
#include <android/log.h>

#define LOG_TAG "EXE.Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int NUM_SLOTS = 2;

#ifdef EXE_HAVE_LLAMA
extern "C" {
#include "llama.h"
}

namespace {
struct Ctx {
    llama_model*   model = nullptr;
    llama_context* lctx  = nullptr;
    int            n_ctx = 2048;
};
std::mutex g_mu;
Ctx g_slots[NUM_SLOTS];
bool g_backend_initd = false;

void release_slot_locked(int s) {
    if (s < 0 || s >= NUM_SLOTS) return;
    if (g_slots[s].lctx)  { llama_free(g_slots[s].lctx);   g_slots[s].lctx  = nullptr; }
    if (g_slots[s].model) { llama_free_model(g_slots[s].model); g_slots[s].model = nullptr; }
}
}
#endif

static jstring jstr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projectexe_ai_engine_local_LlamaCpp_nativeLoad(
        JNIEnv* env, jobject /*thiz*/, jint slot, jstring jpath, jint nCtx) {
#ifndef EXE_HAVE_LLAMA
    (void)env; (void)slot; (void)jpath; (void)nCtx;
    LOGE("llama.cpp not compiled in");
    return JNI_FALSE;
#else
    if (slot < 0 || slot >= NUM_SLOTS) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_backend_initd) { llama_backend_init(); g_backend_initd = true; }
    release_slot_locked(slot);
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;
    std::string p(path);
    env->ReleaseStringUTFChars(jpath, path);

    auto mparams = llama_model_default_params();
    g_slots[slot].model = llama_load_model_from_file(p.c_str(), mparams);
    if (!g_slots[slot].model) { LOGE("slot %d load_model failed: %s", slot, p.c_str()); return JNI_FALSE; }

    auto cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t) (nCtx > 0 ? nCtx : 2048);
    cparams.n_batch   = 256;
    cparams.n_threads = 4;
    g_slots[slot].lctx = llama_new_context_with_model(g_slots[slot].model, cparams);
    if (!g_slots[slot].lctx) { release_slot_locked(slot); LOGE("slot %d new_context failed", slot); return JNI_FALSE; }
    g_slots[slot].n_ctx = (int) cparams.n_ctx;
    LOGI("Slot %d loaded model %s ctx=%d", slot, p.c_str(), g_slots[slot].n_ctx);
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_projectexe_ai_engine_local_LlamaCpp_nativeRelease(JNIEnv*, jobject, jint slot) {
#ifdef EXE_HAVE_LLAMA
    if (slot < 0 || slot >= NUM_SLOTS) return;
    std::lock_guard<std::mutex> lk(g_mu);
    release_slot_locked(slot);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectexe_ai_engine_local_LlamaCpp_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/, jint slot, jstring jprompt, jint maxTokens, jfloat temperature) {
#ifndef EXE_HAVE_LLAMA
    (void)slot; (void)jprompt; (void)maxTokens; (void)temperature;
    return jstr(env, "ERROR: ENGINE_UNAVAILABLE");
#else
    if (slot < 0 || slot >= NUM_SLOTS) return jstr(env, "ERROR: BAD_SLOT");
    std::lock_guard<std::mutex> lk(g_mu);
    Ctx& c = g_slots[slot];
    if (!c.model || !c.lctx) return jstr(env, "ERROR: NO_MODEL");

    const char* p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = p ? p : "";
    if (p) env->ReleaseStringUTFChars(jprompt, p);

    std::vector<llama_token> tokens(prompt.size() + 8);
    int n = llama_tokenize(c.model, prompt.c_str(), (int)prompt.size(),
                           tokens.data(), (int)tokens.size(), true, true);
    if (n < 0) { tokens.resize(-n);
        n = llama_tokenize(c.model, prompt.c_str(), (int)prompt.size(),
                           tokens.data(), (int)tokens.size(), true, true);
    }
    if (n <= 0) return jstr(env, "ERROR: TOKENIZE_FAILED");
    tokens.resize(n);

    llama_kv_cache_clear(c.lctx);
    llama_batch batch = llama_batch_init((int)tokens.size(), 0, 1);
    for (int i = 0; i < (int)tokens.size(); ++i) {
        batch.token[i] = tokens[i]; batch.pos[i] = i;
        batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == (int)tokens.size() - 1) ? 1 : 0;
    }
    batch.n_tokens = (int)tokens.size();
    if (llama_decode(c.lctx, batch) != 0) {
        llama_batch_free(batch); return jstr(env, "ERROR: DECODE_FAILED");
    }

    std::string out;
    int n_vocab = llama_n_vocab(c.model);
    int cur     = (int)tokens.size();
    int budget  = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < budget; ++i) {
        float* logits = llama_get_logits_ith(c.lctx, batch.n_tokens - 1);
        std::vector<llama_token_data> cand; cand.reserve(n_vocab);
        for (llama_token id = 0; id < n_vocab; ++id)
            cand.push_back({id, logits[id], 0.0f});
        llama_token_data_array a{cand.data(), cand.size(), false};
        llama_sample_temp(c.lctx, &a, temperature);
        llama_token tok = llama_sample_token(c.lctx, &a);
        if (tok == llama_token_eos(c.model)) break;

        char buf[256];
        int written = llama_token_to_piece(c.model, tok, buf, sizeof(buf), 0, false);
        if (written > 0) out.append(buf, (size_t)written);

        batch.n_tokens = 1; batch.token[0] = tok; batch.pos[0] = cur++;
        batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = 1;
        if (llama_decode(c.lctx, batch) != 0) break;
        if (cur >= c.n_ctx - 4) break;
    }
    llama_batch_free(batch);
    return jstr(env, out);
#endif
}
