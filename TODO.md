# Supertonic TTS - Implementation Roadmap

## Phase 1: Web-to-App Bridge (Integration) - ✅ COMPLETED
*Goal: Seamlessly transfer text from the browser to the Android app.*

- [x] **[Chrome Extension] Send to App Feature**:
    -   Implemented "Send to App" button with smartphone icon.
    -   Constructed `intent://` URI with text payload and fallback URL (`github.com`).
    -   Added error handling for navigation.
- [x] **[Android] Deep Linking / Intent Handling**:
    -   Modified `AndroidManifest.xml`: Added `intent-filter` for `supertonic` scheme and `BROWSABLE` category. Set `launchMode="singleTop"`.
    -   Modified `MainActivity.kt`: Implemented `handleIntent` to extract text from `Intent.EXTRA_TEXT` or URI query parameters.
- [x] **[Android] Increase Text Capacity**:
    -   Verified `inputText` handles large payloads.

## Phase 2: Core Engine Optimization (Performance) - ✅ COMPLETED
*Goal: Make the app feel instant by streaming audio.*

- [x] **[Android] Streaming Playback**:
    -   Refactored `PlaybackService.kt` to use `AudioTrack` in `MODE_STREAM`.
    -   Updated `initAudioTrack` to play immediately.
    -   Implemented chunk processing in `onAudioChunk`.

## Phase 3: Mobile UI/UX Overhaul (Chrome Extension) - ✅ COMPLETED
*Goal: Ensure the extension works natively on Android browsers (Quetta/Kiwi).*

- [x] **[UI] Viewport & Layout**:
    -   Added `<meta name="viewport">` tag.
    -   Switched from fixed `420px` width to fluid `100%` width/height.
    -   Optimized CSS for mobile (touch targets > 48px, safe area padding).
- [x] **[UI] Component Improvements**:
    -   Refactored `#textInput` to use flexbox (avoids keyboard overlap).
    -   Increased button sizes for thumb interaction.

## Phase 4: Housekeeping & Polish (Next Steps)
*Goal: Clean up technical debt and prepare for release.*

- [x] **[Global] Package Renaming**:
    -   Refactor package name from `com.example.supertonic` to `com.brahmadeo.supertonic`.
    -   Update `build.gradle`, `AndroidManifest.xml`, and source files.
    -   **CRITICAL**: Update JNI native method signatures in C++ code to match the new package name before compiling.

## Phase 5: Future Features (Backlog)
- [x] **[Android] Audio Export**: Add functionality to save the synthesized audio as a `.wav` file.
- [x] **[Android] Voice Management**: Create a UI to manage/import voice style files (`.json`) from device storage.
- [x] **[Android] History**: Keep a local history of synthesized texts.
