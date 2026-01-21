# Supertonic Chrome Extension Context

## Project Overview
This directory contains the source code for the **Supertonic Chrome Extension (Collector Edition)**.

*   **Type:** Chrome Extension (Manifest V3)
*   **Version:** 1.0.0
*   **Purpose:** A lightweight tool to "Fetch, Clean, and Send" text from web pages to the Supertonic Android App.
*   **Architecture:** **Text Collector Only**. This version has NO background process, NO offscreen documents, and NO native messaging. It is purely an on-demand popup.

## Architecture & Configuration

### Extension Structure
*   **`manifest.json`**: Minimal configuration. Permissions: `scripting`, `activeTab`, `storage`.
    *   **NO Background Script**: The extension terminates completely when the popup closes.
*   **`popup.html` / `popup.js`**: The sole entry point. Handles all logic:
    *   Fetching text from the active tab.
    *   Cleaning text ("Fluff Mode").
    *   Sending text to the Android app via `intent://`.

### Core Logic
*   **Text Normalization**:
    *   `textProcessor.js`: Centralized logic for text normalization, sentence splitting, and "fluff" removal (navigation links, headers).
    *   `currencyNormalizer.js`: Converts complex currency formats (e.g., "$1.5bn") into speakable text.
    *   `numberUtils.js`: Handles general number formatting.
*   **Android Integration**: Uses `intent://` URIs to pass cleaned text directly to the Supertonic Android App.

## Development & Usage

### Key Files
*   `popup.js`: Manages UI state, fetching text, and cleaning logic.
*   `textProcessor.js`: The core logic for detecting "fluff" and normalizing text.

### Testing
*   **Manual Testing**: Load the directory as an "Unpacked Extension" in Chrome/Kiwi/Yandex (`chrome://extensions`).
*   **Flow**: Open Popup -> Fetch -> Clean -> Send.

## Notes
*   **Android Compatibility**: Optimized for Android browsers (Kiwi, Lemur).
*   **Stability**: By removing the background service worker, this version eliminates "zombie process" issues common in mobile browsers.
