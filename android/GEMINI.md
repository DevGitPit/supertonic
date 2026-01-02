# Supertonic Android Application

## Project Overview
This directory contains the complete **Android application** for Supertonic, a high-performance, on-device text-to-speech (TTS) system. It demonstrates how to integrate the Supertonic ONNX models into a mobile app using Kotlin and JNI.

The application serves two main purposes:
1.  **Standalone TTS Player:** A user-friendly interface to type text, select voices, and generate speech instantly.
2.  **System TTS Service:** Implements the Android `TextToSpeechService` API, allowing Supertonic to be used as the system-wide TTS engine (accessible by other apps like eBook readers, accessibility tools, etc.).

## Key Features
*   **Offline Inference:** Runs entirely on-device using ONNX Runtime.
*   **Zero Latency:** optimized for mobile processors (ARM64).
*   **Voice Customization:** Supports multiple voice styles (stored as JSON) and speed adjustments.
*   **System Integration:** Can be set as the default Android TTS engine.
*   **Background Playback:** Uses a foreground service for uninterrupted audio generation.

## Architecture

### Directory Structure
*   `app/src/main/java/`: Kotlin source code.
    *   `MainActivity.kt`: Main UI for text input and configuration.
    *   `SupertonicTTS.kt`: JNI wrapper class communicating with the native C++ library.
    *   `service/SupertonicTextToSpeechService.kt`: Implementation of Android's system TTS service.
*   `app/src/main/assets/`: Contains the required model files.
    *   `onnx/`: The core ONNX models.
    *   `voice_styles/`: JSON configuration files for different voice personas.
*   `app/src/main/jniLibs/arm64-v8a/`: Pre-compiled native libraries.
    *   `libonnxruntime.so`: Microsoft ONNX Runtime.
    *   `libsupertonic_tts.so`: Supertonic C++ core logic.

### Core Components
*   **Asset Management**: On first launch (in `MainActivity` or `SupertonicTextToSpeechService`), assets are copied from the APK's `assets/` folder to the application's internal file storage (`filesDir`) to be accessible by the native C++ code.
*   **Native Bridge (`SupertonicTTS.kt`)**: Loads `libonnxruntime.so` and `libsupertonic_tts.so`. Exposes methods like `initialize()` and `generateAudio()`.
*   **System Service**: The `SupertonicTextToSpeechService` exposes the engine to the Android OS, handling standard TTS intents and normalizing text input.

## Building and Running

### Prerequisites
*   **Android Studio** or **Gradle** command line tools.
*   **JDK 17** (or compatible with the Gradle version).
*   **Git LFS** (ensure `assets/` in the project root are downloaded).

### Build Commands
To build the debug APK:
```bash
./gradlew assembleDebug
```

To install directly to a connected device:
```bash
./gradlew installDebug
```

### Setup on Device
1.  Install the app.
2.  Open **Supertonic** to initialize assets (wait for "Ready" status).
3.  (Optional) To use system-wide:
    *   Go to **Settings > Accessibility > Text-to-speech output**.
    *   Select **Preferred engine** and choose **Supertonic TTS**.

## Development Conventions
*   **Language:** Kotlin (UI/Logic) and C++ (Native Core).
*   **UI Framework:** XML Layouts (primary) mixed with Jetpack Compose libraries.
*   **Threading:** Uses Kotlin Coroutines (`Dispatchers.IO`) for heavy operations like asset copying and synthesis to keep the UI responsive.
*   **Architecture Pattern:** MVVM-like structure where Activities delegate logic to Services and helper classes.
