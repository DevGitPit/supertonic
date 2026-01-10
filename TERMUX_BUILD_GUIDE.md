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

## Step-by-Step Build Instructions

### 1. Setup Environment
```bash
pkg update
pkg install openjdk-17 gradle android-tools git rust clang
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

### 3. Patch Build Configuration
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

### 4. Patch Rust Configuration
Edit `rust/.cargo/config.toml` to use system clang:
```toml
[target.aarch64-linux-android]
linker = "clang"
rustflags = ["-l", "log"]
```

### 5. Build
Run the build. It will fail on the first try due to the `aapt2` binary issue.
```bash
cd android
./gradlew assembleDebug
```

### 6. Apply `aapt2` Fix
Find the wrong binary and link the right one:
```bash
find ~/.gradle -name "aapt2" -type f -exec ln -sf $(which aapt2) {} \;
```

### 7. Build Again
```bash
./gradlew assembleDebug
```
The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Best Practices
*   **Clean Build:** If you switch between Termux and PC, run `./gradlew clean` to avoid cache conflicts.
*   **Memory:** Termux can be memory constrained. If the Java daemon dies, try `export _JAVA_OPTIONS="-Xmx2g"`.
*   **Symlinks:** Always check if native libs (`.so` files) are actually present in `src/main/jniLibs` if you aren't building them via Gradle.
