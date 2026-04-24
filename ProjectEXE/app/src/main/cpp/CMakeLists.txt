cmake_minimum_required(VERSION 3.22.1)
project(exe_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(EXE_BUILD_LLAMA "Fetch and build llama.cpp" ON)

# JNI shim — always built. It dynamically resolves the optional llama
# implementation if it was linked in, otherwise reports "engine unavailable"
# back to Kotlin so the app never crashes when the native lib is missing.
add_library(exe_native SHARED
    exe_jni.cpp
)

find_library(log-lib log)
target_link_libraries(exe_native ${log-lib})

if(EXE_BUILD_LLAMA)
    include(FetchContent)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        b3963
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)

    target_compile_definitions(exe_native PRIVATE EXE_HAVE_LLAMA=1)
    target_link_libraries(exe_native llama)
endif()
