#!/bin/bash
# setup_termux_build.sh
# Automates environment setup for building Supertonic APK on Termux

set -e

echo "=== Supertonic Termux Build Setup ==="

# 1. Install Dependencies
echo "[1/6] Checking Termux packages..."
pkg install -y openjdk-17 gradle android-tools git rust clang wget unzip

# 2. Setup Android SDK
export ANDROID_HOME="$HOME/.android-sdk"
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    echo "[2/6] Installing Android Command Line Tools..."
    mkdir -p "$ANDROID_HOME/cmdline-tools"

    # Download URL for Command Line Tools (latest as of early 2025/2026)
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    wget -q "$CMDLINE_TOOLS_URL" -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
    rm cmdline-tools.zip
else
    echo "[2/6] Android SDK found."
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 3. Install SDK Packages
echo "[3/6] Installing SDK Platforms & Build Tools (Target: 34)..."
yes | sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null

# 4. Patch build.gradle (Downgrade 35 -> 34)
echo "[4/6] Patching build.gradle for Termux compatibility..."
BUILD_GRADLE="android/app/build.gradle"
if [ -f "$BUILD_GRADLE" ]; then
    sed -i 's/compileSdk 35/compileSdk 34/g' "$BUILD_GRADLE"
    sed -i 's/targetSdk 35/targetSdk 34/g' "$BUILD_GRADLE"
    # Downgrade Material3 to 1.2.1 (compatible with SDK 34)
    sed -i 's/material3:1.4.0-alpha04/material3:1.2.1/g' "$BUILD_GRADLE"
    echo "    - Downgraded SDK to 34 and Material3 to 1.2.1"
else
    echo "    ! Warning: $BUILD_GRADLE not found."
fi

# 5. Patch Rust Config
echo "[5/6] Patching Rust config for local clang..."
RUST_CONFIG="rust/.cargo/config.toml"
if [ -f "$RUST_CONFIG" ]; then
    cat > "$RUST_CONFIG" <<EOF
[target.aarch64-linux-android]
linker = "clang"
rustflags = ["-l", "log"]
EOF
    echo "    - Updated .cargo/config.toml"
fi

# 6. Pre-emptively fix aapt2 (The Magic Trick)
echo "[6/6] Applying aapt2 fix..."
# We run a dry-run build to force Gradle to download the (wrong) aapt2 binary
echo "    - Triggering Gradle dependency download (ignore failure)..."
cd android
./gradlew dependencies || true

echo "    - Replacing x86 aapt2 with system binary..."
TERMUX_AAPT2=$(which aapt2)
# Find all aapt2 binaries in gradle cache and replace them
find "$HOME/.gradle/caches" -name "aapt2" -type f | while read -r file; do
    echo "      Replacing $file"
    rm "$file"
    ln -s "$TERMUX_AAPT2" "$file"
done

echo ""
echo "=== Setup Complete! ==="
echo "You can now build the APK:"
echo "  1. ./compile_jni_libs.sh (Required)"
echo "  2. cd android"
echo "  3. gradle assembleDebug"
echo ""
echo "Note: If gradle fails with 'aapt2' errors, run ./setup_termux_build.sh again to re-apply the binary fix."
