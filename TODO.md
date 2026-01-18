# Supertonic TTS - Implementation Roadmap

## Version 1.1: Core Stability & UX - ✅ COMPLETED
*Goal: Robust background playback, lifecycle recovery, and polished UI.*

- [x] **[Android] App Lifecycle & Recovery**:
    -   State persistence for text, index, and voice settings.
    -   "Ask to Resume" dialog on cold start after background kill.
- [x] **[Android] Audio Focus Management**:
    -   Service now respects transient loss, ducking, and call interruptions.
- [x] **[Android] UX Refinements**:
    -   Pause/Resume toggle and dedicated Stop button.
    -   Smooth centering scroll in PlaybackActivity.
    -   Confirmation dialog for History item selection.
- [x] **[Android] Audio Management**:
    -   Saved Audio list with play/delete functionality.
    -   Export WAV progress overlay with cancel option.
    -   Limited history to last 10 items.
- [x] **[Global] Security & Cleanup**:
    -   Disabled dangerous manual asset imports.
    -   Default disabled state for Synthesize button during engine init.

## Version 1.2: Chrome Extension Polish - ✅ COMPLETED
*Goal: Align Chrome Extension features with Android app standards.*

- [x] **[Extension] UI/UX Improvements**:
    -   Hide Fetch/Clean buttons during playback; Send button stops playback.
    -   Auto-exit Fluff Mode when Play is clicked.
    -   Fixed scrolling jitter in Fluff Mode using internal container scrolling.
    -   Updated speed slider to match Android (0.9x - 1.5x).

## Version 1.3: Advanced Playback & Queuing - 🚀 PLANNED
*Goal: Transform the app into a sequential reader with seamless navigation.*

- [x] **Sequential Queue System**:
    -   Implement `QueueItem` data model (per-item voice/speed settings).
    -   Add auto-advance logic to `PlaybackService` to play the next queued item automatically.
    -   Add "Add to Queue" vs "Play Now" choice when initiating synthesis while audio is already active.
- [x] **Multi-Activity Navigation Bridge**:
    -   Implement "Now Playing" mini-player bar at the bottom of `MainActivity`.
    -   Allow switching between `MainActivity` (for editing/settings) and `PlaybackActivity` (for seeking/highlighting) using `REORDER_TO_FRONT` flags to prevent state reset.
- [x] **Playlist Management UI**:
    -   Create `QueueActivity` to view pending tracks.
    -   Implement Drag & Drop reordering and Swipe-to-Delete for the queue.
- [x] **Enterprise-Grade Robustness**:
    -   **Storage-Backed Queue**: Cache queued text to disk to avoid `OutOfMemory` errors on massive playlists.
    -   **JNI Handshake**: Implement explicit teardown/re-init sequence between queued tracks to prevent native race conditions.
    -   **Queue Persistence**: Save the entire playlist to disk so it survives app restarts.

## Future Backlog
- [x] **[JNI] Dynamic Voice Mixing**: Allow blending two voice style JSONs (via `path1;path2;alpha` format).
- [x] **[UI] Material 3 Expressive Implementation**: Full migration of existing XML screens to Compose with Android 16 expressive tokens.
- [x] **[Web] Content Selection Integration**: Right-click context menu to send specific text selections to the app.