# Supertonic TTS - Release Notes (v1.1)

## New Features (Android App)

### 🎧 Audio Export
-   **Save to WAV**: You can now export synthesized audio directly to your device's Downloads folder.
-   **How to use**: After synthesizing text, click the "Save WAV" button in the playback screen.

### 🗣️ Voice Management
-   **Import Custom Voices**: Support for importing external voice style JSON files.
-   **How to use**: Click the **+** button next to the voice selector on the main screen to pick a `.json` voice file from your storage.

### 📜 History
-   **Synthesis History**: The app now remembers your previously synthesized texts.
-   **How to use**: Click the **History** button on the main screen to view and restore past entries. items show the text preview, date, and the voice used.

## Bug Fixes

### 🧩 Chrome Extension
-   **Complex Sentence Handling**: Fixed an issue where the TTS engine would hang or loop when processing long text chunks containing semicolons (`;`) or em-dashes (`—`). The sentence splitter now correctly handles these punctuation marks.

## Technical Improvements
-   **Package Renaming**: Finalized the migration to `com.brahmadeo.supertonic.tts` across all Android and Native (C++/Rust) components.
