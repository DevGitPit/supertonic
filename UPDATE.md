# Supertonic Local TTS - Update Log

## Recent Updates (2025-12-13)

### 1. New Voice Styles and Models
We have updated the project to use the latest voice styles and ONNX models provided by Supertone.

**Update Procedure:**
To acquire the new assets (voices like F3, F4, F5, M3, M4, M5 and optimized models), we performed the following:

1.  **Direct Hugging Face Clone:** The upstream git repository references new voices but does not track them directly in the main tree (likely due to LFS or submodule restructuring). To get the actual files, we cloned the official Hugging Face repository directly into the `assets` directory:
    ```bash
    git clone https://huggingface.co/Supertone/supertonic assets
    ```
2.  **Model Integration:** The `.onnx` models from this clone were placed in `assets/onnx`.
3.  **C++ Rebuild:** The `native_host` binary was rebuilt to link against these new asset paths.

### 2. Chrome Extension Enhancements
The `chrome_extension` has been significantly upgraded:

*   **Architecture Change:** Switched from direct Native Messaging (which fails on Android sandboxes) to a **Python HTTP Bridge** (`server.py` running on `127.0.0.1:8080`) that communicates with the C++ binary.
*   **New UI Features:**
    *   **Voices:** Dropdown now includes F1-F5 and M1-M5.
    *   **Speed Control:** Adjustable from 0.9x to 1.5x (default 1.05x).
    *   **Denoising Steps:** Configurable from 1 to 10 (default 5).
    *   **Pre-buffer:** Adjustable buffer size (1-10 sentences) for smoother streaming.
*   **Playback Modes:**
    *   **Play (Full):** Converts entire text and plays with a standard audio player.
    *   **Stream:** Sentence-by-sentence streaming with real-time text highlighting.
*   **Mobile Optimizations:**
    *   **Keyboard Suppression:** The text input becomes `readOnly` during streaming to prevent the virtual keyboard from popping up and obscuring the view on mobile devices.
    *   **State Persistence:** Text and settings are saved automatically, and edits to the text box are preserved independently of the original webpage content.

### 3. Backend (Termux/Android)
*   **Server Script:** `server.py` acts as the bridge.
*   **Execution:** The server must be running in Termux for the extension to work:
    ```bash
    python3 server.py
    ```
