# Supertonic Chrome Extension Context

## Project Overview
This directory contains the source code for the **Supertonic Chrome Extension**, a browser-based interface for the Supertonic local Text-to-Speech (TTS) system.

*   **Type:** Chrome Extension (Manifest V3)
*   **Purpose:** Enables local, high-performance TTS within the browser by interfacing with the underlying Supertonic C++ engine.
*   **Key Dependencies:** Requires the local C++ build of Supertonic to be running (either as a local server or via native messaging).

## Architecture & Configuration

### Extension Structure
*   **`manifest.json`**: Defines the extension configuration (Permissions: `scripting`, `activeTab`, `storage`, `tts`, `offscreen`).
*   **`host_manifest.json`**: Configures the **Native Messaging Host** (`com.supertonic.tts`), which allows the extension to communicate directly with the local system shell script (`.../supertonic/cpp/build/start_host.sh`).
*   **`background.js`**: The service worker that orchestrates TTS requests. It manages an **Offscreen Document** (`offscreen.html`) to handle audio playback constraints in Manifest V3 and implements a polling fallback for Android environments where TTS events might be unreliable.

### Core Logic
*   **Text Normalization**: 
    *   `currencyNormalizer.js`: A dedicated class for converting complex currency formats (e.g., "$1.5bn", "€500m", "₹10k") into speakable text ("1 point 5 billion dollars").
    *   `numberUtils.js`: (Likely) handles general number formatting, phone numbers, and measurements.
*   **Playback**: Uses the `chrome.tts` API, delegating actual synthesis to the local backend.
*   **Communication & Stability**:
    *   **OFFSCREEN_READY Handshake**: Implements an explicit handshake between the background service worker and offscreen document to ensure reliable initialization.
    *   **Retry Mechanism**: `safeRuntimeMessage` include automated retries to handle extension suspension and cold-boot race conditions.
    *   **Asynchronous Cleanup**: Stop commands are fully asynchronous to prevent race conditions during playback interruption.

## Development & Usage

### Prerequisites
1.  **C++ Engine**: The core Supertonic engine must be built in `../cpp/`.
2.  **Native Host**: The `start_host.sh` script must be executable at the path specified in `host_manifest.json`.
3.  **Local Server (Optional/Alternative)**: The extension also requests host permissions for `http://127.0.0.1:8080`, allowing it to fetch audio from the Python/C++ server.

### Key Files
*   `background.js`: Main event loop, message coordinator, and lifecycle manager.
*   `offscreen.html` / `offscreen.js`: Handles audio context creation and playback loops (fetch-decode-play).
*   `popup.js`: Manages UI state, including setting locks during active playback and intent-based communication with the Android app.
*   `tests/normalization_tests.txt`: Contains test cases and expected outputs for the text normalization logic (Currency, Numbers, Dates).

### Testing
*   **Manual Testing**: Load the directory as an "Unpacked Extension" in Chrome (`chrome://extensions`).
*   **Normalization Logic**: Check `tests/normalization_tests.txt` to understand how specific text patterns (currencies, units) should be handled.

## Notes
*   **Android Compatibility**: The `background.js` contains specific workarounds (polling loops) for Android environments where standard Chrome TTS events might not fire reliably.
*   **Voice Handling**: The extension logic attempts to parse locale and voice names (e.g., `en-us-supertonic-F5`) to configure the TTS engine correctly.
