# Supertonic TTS - Release Notes

## Version 1.3: The "Expressive" Update 🎨 (Current)

This major update brings a complete UI overhaul and powerful new audio features.

### 🚀 New Features

#### 🎨 Material 3 Expressive UI
-   **Full Redesign**: The entire Android app has been rewritten using **Jetpack Compose** and **Material 3 Expressive** design tokens.
-   **Smoother Animations**: Transitions between screens are now fluid and state-aware.
-   **Mini Player**: A persistent "Now Playing" bar at the bottom of the screen allows you to control playback while browsing your queue or settings.

#### 📝 Sequential Queue & Playlist Management
-   **Queue System**: You can now add multiple text items to a playback queue.
-   **Gapless Playback**: The engine automatically pre-buffers the next item for seamless transitions.
-   **Queue Management**: Drag-and-drop to reorder items, swipe to remove.
-   **Background Persistence**: Your queue is saved automatically and restored when you relaunch the app.

#### 🎛️ Dynamic Voice Mixing
-   **Blend Voices**: Mix two different voice styles together to create unique custom voices.
-   **Real-time Control**: Adjust the mixing ratio (e.g., 70% Voice A + 30% Voice B) directly from the main screen.

### 🌐 Chrome Extension Updates
-   **Context Menu**: Right-click any text on a webpage and select "Send to Supertonic TTS".
-   **Auto-Scroll Fix**: The currently reading sentence now stays perfectly centered in the view.
-   **Cleaner UI**: Buttons auto-hide during playback to reduce clutter.

---

## Version 1.1: Core Stability & UX

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
