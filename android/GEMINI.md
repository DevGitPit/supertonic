# Supertonic Android Application

## Project Overview
This directory contains the complete **Android application** for Supertonic, a high-performance, on-device text-to-speech (TTS) system. It demonstrates how to integrate the Supertonic ONNX models into a mobile app using Kotlin and JNI.

The application serves two main purposes:
1.  **Standalone TTS Player:** A user-friendly, immersive interface to type/paste text, select voices, and generate speech instantly.
2.  **System TTS Service:** Implements the Android `TextToSpeechService` API, allowing Supertonic to be used as the system-wide TTS engine.

## Key Features
*   **Offline Inference:** Runs entirely on-device using ONNX Runtime.
*   **Multilingual Support:** Native support for **English, Korean, Spanish, Portuguese, and French**.
*   **Gapless Playback:** Implements a Producer-Consumer pipeline to synthesize the next sentence while the current one plays, eliminating latency between sentences.
*   **Material Design 3 (Expressive):** Modern, coherent UI with dynamic semantic colors and dark mode support.
*   **UI Localization:** The interface automatically adapts to the system language (English, Korean, Spanish, Portuguese, French).
*   **Edge-to-Edge Support:** Optimized for Android 15/16 system insets and curved displays.
*   **Digital Volume Boost:** Built-in 2.5x gain with hard-clipping protection for significantly louder audio output.
*   **User Pronunciation Dictionary (Lexicon):** Custom rules to correct or change how specific terms are pronounced, including Import/Export support via JSON.
*   **AIDL Architecture:** Robust inter-process communication (IPC) for both internal playback and third-party app integration (e.g., eBook readers).
*   **Smart Audio Focus:** Intelligently handles system interruptions (notifications, calls), resuming only if the interruption was transient and not a manual user pause.
*   **Immersive Reader:** Distraction-free playback interface with text highlighting.
*   **Audio Export:** Save synthesized speech as WAV files to the Music directory.
*   **Rich Media Controls:** Native Android media notification with album art, play/pause, and metadata.
*   **History:** Tracks recent synthesis requests.

## Architecture

### Directory Structure
*   `app/src/main/aidl/com/brahmadeo/supertonic/tts/service/`: AIDL interface definitions.
    *   `IPlaybackService.aidl`: Main control interface for synthesis and playback.
    *   `IPlaybackListener.aidl`: Callback interface for progress and state updates.
*   `app/src/main/java/com/brahmadeo/supertonic/tts/`: Kotlin source code.
    *   `MainActivity.kt`: Main UI for input and configuration (Controls anchored to bottom).
    *   `PlaybackActivity.kt`: Immersive player with sentence highlighting.
    *   `HistoryActivity.kt`: View recent synthesis requests.
    *   `SavedAudioActivity.kt`: Manage exported WAV files.
    *   `LexiconActivity.kt`: User dictionary management (Add/Edit/Delete pronunciation rules).
    *   `SupertonicTTS.kt`: JNI wrapper class (Singleton) for the native C++ library.
    *   `service/`:
        *   `PlaybackService.kt`: Foreground service handling the audio pipeline (Producer-Consumer).
        *   `SupertonicTextToSpeechService.kt`: System TTS service implementation.
    *   `utils/`:
        *   `TextNormalizer.kt`: Robust regex-based normalization (currencies, dates, measurements).
        *   `CurrencyNormalizer.kt`: Handles complex currency formats.
        *   `LexiconManager.kt`: Manages user-defined pronunciation rules.
        *   `HistoryManager.kt`: JSON-based history persistence.
        *   `WavUtils.kt`: WAV header generation.
*   `app/src/main/assets/`: Contains the required model files.
    *   `onnx/`: The core ONNX models.
    *   `voice_styles/`: JSON configuration files for different voice personas.
*   `app/src/main/res/`: Resources.
    *   `values/strings.xml`: Default (English) strings.
    *   `values-ko/`: Korean translations.
    *   `values-es/`: Spanish translations.
    *   `values-pt/`: Portuguese translations.
    *   `values-fr/`: French translations.
*   `app/src/main/jniLibs/arm64-v8a/`: Pre-compiled native libraries.
    *   `libonnxruntime.so`: Microsoft ONNX Runtime.
    *   `libsupertonic_tts.so`: Supertonic C++ core logic.

### Voice Personas
Voices are treated as "Personas" that work across all supported languages.
*   **Male:** Alex (M1), James (M2), Robert (M3), Sam (M4), Daniel (M5)
*   **Female:** Sarah (F1), Lily (F2), Jessica (F3), Olivia (F4), Emily (F5)

### Core Components
*   **Audio Pipeline (`PlaybackService`)**: Uses a Kotlin Coroutine `Channel` (capacity 2) to buffer synthesized audio. The "Producer" coroutine synthesizes sentences ahead of time and applies a **2.5x digital gain**, while the "Consumer" loop plays them using `AudioTrack` in `MODE_STATIC` for instant starts.
*   **Native Bridge**: `SupertonicTTS.kt` exposes thread-safe methods for `initialize()` and `generateAudio()`.
*   **Text Normalization**: A dedicated `TextNormalizer` class handles complex patterns (e.g., "$2.5bn" -> "two point five billion dollars") to ensure natural reading.

## Building and Running

### Prerequisites
*   **Android Studio** or **Gradle** command line tools.
*   **JDK 17**.
*   **Git LFS** (ensure `assets/` in the project root are downloaded).

### Build Commands
To build the debug APK:
```bash
./gradlew assembleDebug
```

### Setup on Device
1.  Install the app.
2.  Open **Supertonic** to initialize assets (wait for "Ready" status).
3.  (Optional) To use system-wide:
    *   Go to **Settings > Accessibility > Text-to-speech output**.
    *   Select **Preferred engine** and choose **Supertonic TTS**.

## Development Conventions
*   **Language:** Kotlin (UI/Logic) and C++ (Native Core).
*   **UI Framework:** XML Layouts using **Material Components** (M3).
*   **Threading:** Heavy operations (synthesis, asset copying) run on `Dispatchers.IO`.
*   **Style:** Follows Material Design 3 guidelines (Semantic Colors, Shapes, Typography).
