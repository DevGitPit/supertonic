# Supertonic Chrome Extension

A companion extension for the Supertonic Android app that allows you to fetch article text, clean up distraction (fluff), and send it directly to your device for TTS playback.

## Features

### 🧹 Smart Clean Mode (Fluff Detection)
Automatically detect and remove distracting elements like navigation menus, copyright footers, and social media links.

1.  **Auto-Clean**: Automatically strips common "Print this page" headers and Copyright footers.
2.  **Fluff Mode**: Click the **Clean** button to enter an interactive mode where "suspect" lines are highlighted in orange.
    *   **Suspects**: Detects copyright symbols, short navigation lines, and disconnected headers.
    *   **Context Aware**: Intelligently groups section headers (e.g., "Recommended") with their following titles so you can delete them as a block.
    *   **Controls**: Use **↑/↓** to navigate suspects, **Delete** to remove the current block, or **Del All** to remove all detected fluff.

### 🎧 Playback & Sync
*   **Fetch**: Grabs the main text content from the current tab.
*   **Read Mode**: Highlights sentences as they are spoken. Click any sentence to seek.
*   **Send to App**: Deep-links the text to the Supertonic Android app for offline playback.

## Troubleshooting

### Android: Extension Uninstalls Itself
If the browser automatically removes or disables the extension, it is likely a permissions issue.
1.  **Do not load from Termux private data.** Browsers cannot persistently access files in `/data/data/com.termux/...`.
2.  **Move to Shared Storage:**
    ```bash
    cp supertonic_extension.zip /sdcard/
    ```
3.  **Load from `/sdcard/`:** Unpack or load the zip from your shared storage (Downloads/Internal Storage).

### Android: Playback Stops in Background
Android aggressively kills background processes. To fix this:
1.  **Battery Settings:** Go to **Android Settings > Apps > [Your Browser] > Battery** and set to **Unrestricted**.
2.  **Do not "Force Stop" the browser.**
3.  **Media Notification:** Ensure the media notification ("Supertonic TTS") is visible. If it disappears, the OS has killed the process. Press Play in the popup to restart.
