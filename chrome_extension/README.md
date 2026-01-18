# Supertonic Chrome Extension

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
