cmake_minimum_required(VERSION 3.22.1)
project("androidexe")

# ── llama.cpp source (git submodule at app/src/main/cpp/llama.cpp) ──────────
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Build only the core library; skip tests, examples, and metal
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(GGML_METAL            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)

# Android-specific performance flags
if(ANDROID)
    set(GGML_ANDROID        ON  CACHE BOOL "" FORCE)
    set(GGML_VULKAN         OFF CACHE BOOL "" FORCE)   # flip ON if you want Vulkan compute
    set(GGML_NEON           ON  CACHE BOOL "" FORCE)
    set(GGML_ARM_FP16       ON  CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama_build)

# ── Our JNI bridge ───────────────────────────────────────────────────────────
add_library(
    llama-android
    SHARED
    llama_jni.cpp
)

target_include_directories(llama-android PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llama-android
    llama
    ggml
    android
    log
)

target_compile_options(llama-android PRIVATE -O3 -ffast-math)
