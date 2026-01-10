#!/bin/bash
# compile_jni_libs.sh
# Compiles the Rust JNI library and copies it + ONNX Runtime to the Android project

set -e

# Configuration
ONNX_ANDROID_PATH="$HOME/onnxruntime-android/jni/arm64-v8a"
JNI_LIBS_DIR="android/app/src/main/jniLibs/arm64-v8a"

echo "=== Supertonic JNI Build Script ==="

# 1. Check for ONNX Runtime
if [ ! -d "$ONNX_ANDROID_PATH" ]; then
    echo "Error: ONNX Runtime Android libs not found at $ONNX_ANDROID_PATH"
    echo "Please download and extract onnxruntime-android to your home directory."
    exit 1
fi

echo "[1/4] Building Rust library (libsupertonic_tts.so)..."
echo "      Using ONNX Runtime from: $ONNX_ANDROID_PATH"

# 2. Build Rust Lib
cd rust
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="$ONNX_ANDROID_PATH"

# Ensure target is added if rustup exists
if command -v rustup &> /dev/null; then
    rustup target add aarch64-linux-android
fi

cargo build --release --target aarch64-linux-android

cd ..

# 3. Create JNI Directory
echo "[2/4] Preparing Android JNI directory..."
mkdir -p "$JNI_LIBS_DIR"

# 4. Copy Libraries
echo "[3/4] Copying shared libraries..."

# Copy Rust lib
if [ -f "rust/target/aarch64-linux-android/release/libsupertonic_tts.so" ]; then
    cp "rust/target/aarch64-linux-android/release/libsupertonic_tts.so" "$JNI_LIBS_DIR/"
    echo "      - libsupertonic_tts.so copied."
else
    echo "Error: Rust build failed or output file missing."
    exit 1
fi

# Copy ONNX Runtime lib
if [ -f "$ONNX_ANDROID_PATH/libonnxruntime.so" ]; then
    cp "$ONNX_ANDROID_PATH/libonnxruntime.so" "$JNI_LIBS_DIR/"
    echo "      - libonnxruntime.so copied."
else
    echo "Error: libonnxruntime.so not found in $ONNX_ANDROID_PATH"
    exit 1
fi

echo "[4/4] JNI libraries setup complete!"
echo "      Location: $JNI_LIBS_DIR"
ls -lh "$JNI_LIBS_DIR"
