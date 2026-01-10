# Building Supertonic APK in Termux (Android on Android)

This guide documents the process of building the Supertonic Android app directly on an Android device using Termux. This is useful for development and testing without a PC.

## Prerequisites

*   **Termux App**
*   **Packages:** `openjdk-17`, `gradle`, `android-tools` (provides aapt2), `git`, `rust`, `clang`.
*   **Android SDK**: Installed via command line tools in Termux.

## Limitations & Workarounds

### 1. SDK Version Downgrade (Target SDK 34)
**Issue:** The project defaults to SDK 35 (Android 15). However, the native `aapt2` (Android Asset Packaging Tool) binary provided by the Termux `android-tools` package (v35.0.0-rc1 or similar) currently has compatibility issues with the `android-35.jar` resources, often failing with "failed to load include path".
**Fix:** Downgrade the project to **SDK 34** (Android 14).
*   Change `compileSdk` and `targetSdk` to `34` in `app/build.gradle`.
*   Downgrade `androidx.compose.material3:material3` to `1.2.1` (since newer alphas require SDK 35).

### 2. The `aapt2` Architecture Mismatch
**Issue:** Gradle automatically downloads build tools for `linux-x86_64` (Intel/AMD). Termux runs on `aarch64` (ARM64). When Gradle tries to run the downloaded `aapt2`, it fails with `Syntax error: "(" unexpected` (because it's trying to run an x86 binary on ARM).
**Fix:** Replace the Gradle-downloaded `aapt2` binary with the system `aapt2` provided by Termux.
*   Run a build once to let Gradle download the tools (it will fail).
*   Locate the downloaded binary in `~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2/.../linux/`.
*   Replace it with a symlink to `/data/data/com.termux/files/usr/bin/aapt2`.

### 3. Native Library Linking (Rust)
**Issue:** Standard NDK setups usually expect a specific toolchain path.
**Fix:** Configure `.cargo/config.toml` to use the system `clang` provided by Termux instead of the NDK's prebuilt compiler.

### 4. ONNX Runtime Dependency
**Issue:** The `ort` crate tries to download binaries which may not be available or compatible with the Termux environment.
**Fix:** Use the system strategy (`ORT_STRATEGY=system`) and point to a local ONNX Runtime Android build (e.g., from `onnxruntime-android`).

## Step-by-Step Build Instructions

### 1. Setup Environment
```bash
pkg update
pkg install openjdk-17 gradle android-tools git rust clang wget unzip
```

### 2. Install Android SDK
Set up a directory for the SDK:
```bash
export ANDROID_HOME=$HOME/.android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
# Download commandlinetools-linux-...zip and extract to $ANDROID_HOME/cmdline-tools/latest
```
Install required platforms:
```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### 3. Compile Native Libraries (Rust JNI)
You must build the Rust library (`libsupertonic_tts.so`) before building the APK.

1.  **Download ONNX Runtime Android:**
    Get the `onnxruntime-android` package (aar or zip) and extract the `jni/arm64-v8a` folder.
    Let's assume it's at `~/onnxruntime-android/jni/arm64-v8a`.

2.  **Build Rust Library:**
    ```bash
    cd rust
    # Point to your local ONNX Runtime libs
    export ORT_STRATEGY=system
    export ORT_LIB_LOCATION=$HOME/onnxruntime-android/jni/arm64-v8a

    cargo build --release --target aarch64-linux-android
    ```

3.  **Install Libraries to Android Project:**
    Create the JNI directory and copy the libs:
    ```bash
    mkdir -p ../android/app/src/main/jniLibs/arm64-v8a
    cp target/aarch64-linux-android/release/libsupertonic_tts.so ../android/app/src/main/jniLibs/arm64-v8a/
    cp $ORT_LIB_LOCATION/libonnxruntime.so ../android/app/src/main/jniLibs/arm64-v8a/
    cd ..
    ```

### 4. Patch Build Configuration
Edit `android/app/build.gradle`:
```gradle
android {
    compileSdk 34
    defaultConfig {
        targetSdk 34
    }
}
dependencies {
    // Downgrade M3 to a stable version compatible with SDK 34
    implementation 'androidx.compose.material3:material3:1.2.1'
}
```

### 5. Patch Rust Configuration
Edit `rust/.cargo/config.toml` to use system clang:
```toml
[target.aarch64-linux-android]
linker = "clang"
rustflags = ["-l", "log"]
```

### 6. Build APK
Run the build. It will fail on the first try due to the `aapt2` binary issue.
```bash
cd android
./gradlew assembleDebug
```

### 7. Apply `aapt2` Fix
Find the wrong binary and link the right one:
```bash
find ~/.gradle -name "aapt2" -type f -exec ln -sf $(which aapt2) {} \;
```

### 8. Build Again
```bash
./gradlew assembleDebug
```
The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Best Practices
*   **Clean Build:** If you switch between Termux and PC, run `./gradlew clean` to avoid cache conflicts.
*   **Memory:** Termux can be memory constrained. If the Java daemon dies, try `export _JAVA_OPTIONS="-Xmx2g"`.
*   **Symlinks:** Always check if native libs (`.so` files) are actually present in `src/main/jniLibs` if you aren't building them via Gradle.
