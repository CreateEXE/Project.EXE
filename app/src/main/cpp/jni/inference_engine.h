/**
 * inference_engine.h: Core inference engine abstraction
 *
 * Manages llama.cpp model loading, inference, memory.
 */

#ifndef INFERENCE_ENGINE_H
#define INFERENCE_ENGINE_H

#include <jni.h>
#include <string>
#include <memory>
#include <unordered_map>

class InferenceEngine {
public:
    explicit InferenceEngine(const std::string& cacheDir, const std::string& appDir);
    ~InferenceEngine();

    bool initialize();
    void shutdown();

    int64_t loadModel(const char* modelPath, int32_t gpuLayers);
    bool unloadModel(int64_t modelHandle);

    jobject runInference(JNIEnv* env, int64_t modelHandle,
                        const char* prompt, int32_t maxTokens);
    bool streamInference(JNIEnv* env, int64_t modelHandle,
                        const char* prompt, int32_t maxTokens,
                        jobject callback);

    jobject getMemoryStats(JNIEnv* env);

private:
    std::string m_cacheDir;
    std::string m_appDir;
    std::unordered_map<int64_t, void*> m_models;  // model handles
    bool m_initialized = false;
};

#endif // INFERENCE_ENGINE_H
