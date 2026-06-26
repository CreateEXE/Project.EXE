# CMakeLists.txt: Build libzeroclaw.so (JNI bridge + llama.cpp)
#
# Architecture:
# 1. Compile llama.cpp as a static library
# 2. Compile JNI bindings (C++ wrapper) as a shared library
# 3. Link llama.cpp static + JNI into single libzeroclaw.so
#
# Target: arm64-v8a (Snapdragon 6 Gen 1), x86_64 (emulator)
# Language: C++17, libc++ (shared STL for ABI compatibility)
#
# Dependencies:
# - llama.cpp submodule at app/src/main/cpp/llama.cpp/
# - Android NDK r26.1+
#

cmake_minimum_required(VERSION 3.22.1)
project(zeroclaw CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -Wall -Wextra -O3")

# ============================================================================
# Configuration: Low-end device optimization (Snapdragon 6 Gen 1, 6GB RAM)
# ============================================================================

set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on low-end devices")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Skip llama.cpp tests")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Skip llama.cpp examples")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Skip server build")
set(LLAMA_SHARED ON CACHE BOOL "Build llama as shared library")

# Architecture-specific optimization
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Snapdragon 6 Gen 1 is ARM Cortex-A55/A75
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+crc -mtune=cortex-a75")
    set(GGML_NEON ON CACHE BOOL "Enable NEON SIMD on ARM" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=x86-64 -mtune=generic")
    set(GGML_SSE ON CACHE BOOL "Enable SSE on x86_64" FORCE)
endif()

# ============================================================================
# llama.cpp: Add as subdirectory (will use its own CMakeLists.txt)
# ============================================================================

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp llama.cpp.build)

# ============================================================================
# JNI Bindings: Compile C++ wrapper for Kotlin interface
# ============================================================================

find_package(JNI REQUIRED)

# JNI source files
set(JNI_SOURCES
    jni/zeroclaw_jni.cpp
    jni/inference_engine.cpp
    jni/memory_manager.cpp
    jni/callbacks.cpp
)

# Create libzeroclaw.so: Link JNI code + llama.cpp static library
add_library(zeroclaw SHARED ${JNI_SOURCES})

# Include directories
target_include_directories(zeroclaw PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Link against llama.cpp static library
target_link_libraries(zeroclaw PRIVATE
    llama
    ${ANDROID_LOG_LIBRARY}
)

# Android NDK specific linking
find_library(ANDROID_LOG_LIBRARY log)
if(NOT ANDROID_LOG_LIBRARY)
    message(FATAL_ERROR "Android log library not found")
endif()

# Optimization flags for release builds
if(CMAKE_BUILD_TYPE STREQUAL Release)
    target_compile_options(zeroclaw PRIVATE -O3 -DNDEBUG)
else()
    target_compile_options(zeroclaw PRIVATE -g -O0)
endif()

# ============================================================================
# Linker Configuration
# ============================================================================

# Use c++_shared STL (ABI compatible with Kotlin runtime)
set_target_properties(zeroclaw PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

# Symbol visibility: hide all symbols except JNI exports
set_target_properties(zeroclaw PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# ============================================================================
# Installation: Copy libzeroclaw.so to jniLibs
# ============================================================================

install(
    TARGETS zeroclaw
    LIBRARY DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/intermediates/cmake/debug/obj/${ANDROID_ABI}
)
