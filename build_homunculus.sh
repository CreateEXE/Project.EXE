#!/bin/bash

##############################################################################
# build_homunculus.sh: Orchestrator script for native compilation pipeline
#
# Purpose:
# 1. Cross-compile llama.cpp + Rust components via Android NDK
# 2. Generate JNI bindings and link into libzeroclaw.so
# 3. Deploy shared libraries to Android project jniLibs directories
# 4. Clean and optimize build artifacts for 6GB RAM device
#
# Requirements:
# - Android NDK r26.1+ installed (set via ANDROID_NDK_ROOT env var)
# - CMake 3.22.1+
# - Gradle 8.0+
# - Kotlin 2.0+
#
# Usage:
#   chmod +x build_homunculus.sh
#   ./build_homunculus.sh [arm64-v8a|x86_64|all]
#
# Examples:
#   ./build_homunculus.sh arm64-v8a    # Build for Snapdragon 6 Gen 1
#   ./build_homunculus.sh all           # Build both ABIs
#
##############################################################################

set -e

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}"
APP_DIR="${PROJECT_ROOT}/app"
CPP_DIR="${APP_DIR}/src/main/cpp"
JNI_LIBS="${APP_DIR}/src/main/jniLibs"
BUILD_DIR="${PROJECT_ROOT}/build/native"

# NDK Configuration
if [ -z "${ANDROID_NDK_ROOT}" ]; then
    echo -e "${RED}ERROR: ANDROID_NDK_ROOT not set${NC}"
    echo "Set it with: export ANDROID_NDK_ROOT=/path/to/android-ndk-r26"
    exit 1
fi

NDK_VERSION="26.1.10909125"
MIN_SDK="26"
TARGET_SDK="34"

# Default: build both ABIs
ABIS=("arm64-v8a" "x86_64")
if [ $# -gt 0 ]; then
    if [ "$1" = "arm64-v8a" ] || [ "$1" = "x86_64" ]; then
        ABIS=("$1")
    elif [ "$1" != "all" ]; then
        echo -e "${RED}Usage: $0 [arm64-v8a|x86_64|all]${NC}"
        exit 1
    fi
fi

echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║        Zeroclaw Native Build Orchestrator (v1.0)      ║${NC}"
echo -e "${BLUE}║    llama.cpp + JNI Bridge for Android 14 (API 34)    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}Configuration:${NC}"
echo "  Project Root: ${PROJECT_ROOT}"
echo "  NDK: ${ANDROID_NDK_ROOT}"
echo "  Min SDK: ${MIN_SDK}"
echo "  Target SDK: ${TARGET_SDK}"
echo "  ABIs: ${ABIS[@]}"
echo ""

# ============================================================================
# Step 1: Verify prerequisites
# ============================================================================

echo -e "${YELLOW}[1/5] Verifying prerequisites...${NC}"

if [ ! -d "${ANDROID_NDK_ROOT}" ]; then
    echo -e "${RED}ERROR: NDK not found at ${ANDROID_NDK_ROOT}${NC}"
    exit 1
fi

if [ ! -f "${CPP_DIR}/CMakeLists.txt" ]; then
    echo -e "${RED}ERROR: CMakeLists.txt not found at ${CPP_DIR}${NC}"
    exit 1
fi

if [ ! -d "${CPP_DIR}/llama.cpp" ]; then
    echo -e "${RED}ERROR: llama.cpp submodule not initialized${NC}"
    echo "Run: git submodule update --init --recursive"
    exit 1
fi

echo -e "${GREEN}✓ All prerequisites verified${NC}"
echo ""

# ============================================================================
# Step 2: Clean previous builds
# ============================================================================

echo -e "${YELLOW}[2/5] Cleaning previous build artifacts...${NC}"

rm -rf "${BUILD_DIR}"
rm -rf "${JNI_LIBS}"
mkdir -p "${BUILD_DIR}"
mkdir -p "${JNI_LIBS}"

echo -e "${GREEN}✓ Build directories cleaned${NC}"
echo ""

# ============================================================================
# Step 3: Compile for each ABI
# ============================================================================

echo -e "${YELLOW}[3/5] Compiling native code...${NC}"
echo ""

for ABI in "${ABIS[@]}"; do
    echo -e "${BLUE}  Building for ${ABI}...${NC}"
    
    # Determine ABI-specific flags
    case ${ABI} in
        "arm64-v8a")
            ARCH="arm64"
            TRIPLE="aarch64-linux-android"
            CPU_FLAGS="-march=armv8-a+crc -mtune=cortex-a75"
            ;;
        "x86_64")
            ARCH="x86_64"
            TRIPLE="x86_64-linux-android"
            CPU_FLAGS="-march=x86-64 -mtune=generic"
            ;;
        *)
            echo -e "${RED}ERROR: Unknown ABI ${ABI}${NC}"
            exit 1
            ;;
    esac

    # Create ABI-specific build directory
    BUILD_ABI_DIR="${BUILD_DIR}/${ABI}"
    mkdir -p "${BUILD_ABI_DIR}"

    # Configure CMake with NDK toolchain
    echo -e "    ${GREEN}→${NC} Configuring CMake..."
    cmake -B "${BUILD_ABI_DIR}" -S "${CPP_DIR}" \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.cmake" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM="android-${TARGET_SDK}" \
        -DANDROID_STL="c++_shared" \
        -DCMAKE_BUILD_TYPE="Release" \
        -DGGML_NEON=$([ "${ABI}" = "arm64-v8a" ] && echo "ON" || echo "OFF") \
        -DGGML_SSE=$([ "${ABI}" = "x86_64" ] && echo "ON" || echo "OFF") \
        -DGGML_OPENMP="OFF" \
        -DCMAKE_CXX_FLAGS="${CPU_FLAGS} -O3" \
        2>&1 | grep -v "^--" || true

    # Compile
    echo -e "    ${GREEN}→${NC} Building libzeroclaw.so (${ABI})..."
    cmake --build "${BUILD_ABI_DIR}" --config Release --parallel $(nproc)

    # Install to jniLibs
    JNI_ABI_DIR="${JNI_LIBS}/${ABI}"
    mkdir -p "${JNI_ABI_DIR}"
    
    if [ -f "${BUILD_ABI_DIR}/libzeroclaw.so" ]; then
        cp "${BUILD_ABI_DIR}/libzeroclaw.so" "${JNI_ABI_DIR}/"
        echo -e "    ${GREEN}✓ libzeroclaw.so installed to ${JNI_ABI_DIR}${NC}"
    else
        echo -e "    ${RED}✗ libzeroclaw.so not found in build output${NC}"
        exit 1
    fi
    
    # Also copy c++_shared for Android runtime
    C_SHARED="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/${TARGET_SDK}/libc++_shared.so"
    if [ -f "${C_SHARED}" ]; then
        cp "${C_SHARED}" "${JNI_ABI_DIR}/"
        echo -e "    ${GREEN}✓ libc++_shared.so installed${NC}"
    fi
    
    echo ""
done

echo -e "${GREEN}✓ All architectures compiled${NC}"
echo ""

# ============================================================================
# Step 4: Verify compiled libraries
# ============================================================================

echo -e "${YELLOW}[4/5] Verifying compiled libraries...${NC}"

for ABI in "${ABIS[@]}"; do
    JNI_ABI_DIR="${JNI_LIBS}/${ABI}"
    
    if [ ! -f "${JNI_ABI_DIR}/libzeroclaw.so" ]; then
        echo -e "${RED}ERROR: libzeroclaw.so not found for ${ABI}${NC}"
        exit 1
    fi
    
    SIZE=$(du -h "${JNI_ABI_DIR}/libzeroclaw.so" | cut -f1)
    echo -e "  ${GREEN}✓${NC} ${ABI}: libzeroclaw.so (${SIZE})"
done

echo -e "${GREEN}✓ All libraries verified${NC}"
echo ""

# ============================================================================
# Step 5: Build Android APK/AAB
# ============================================================================

echo -e "${YELLOW}[5/5] Building Android application...${NC}"

cd "${PROJECT_ROOT}"

# Run Gradle build
echo -e "  ${GREEN}→${NC} Executing: ./gradlew assembleDebug"
./gradlew assembleDebug --parallel 2>&1 | grep -E "(BUILD|FAILED|libzeroclaw)" || true

if [ -f "${APP_DIR}/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_SIZE=$(du -h "${APP_DIR}/build/outputs/apk/debug/app-debug.apk" | cut -f1)
    echo -e "${GREEN}✓ APK built: ${APK_SIZE}${NC}"
else
    echo -e "${RED}✗ APK build failed${NC}"
    exit 1
fi

echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║              Build Complete! (Success)                 ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}Output:${NC}"
echo "  JNI Libraries: ${JNI_LIBS}"
echo "  APK: ${APP_DIR}/build/outputs/apk/debug/app-debug.apk"
echo ""
echo -e "${GREEN}Next steps:${NC}"
echo "  1. adb install ${APP_DIR}/build/outputs/apk/debug/app-debug.apk"
echo "  2. adb logcat -s 'HomunculusService|ZeroClawBridge'"
echo ""
