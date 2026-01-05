# Supertonic C++ SDK

This directory contains the high-performance C++ implementation of the Supertonic Text-to-Speech system. It uses ONNX Runtime for inference and is designed to be the core engine for other applications (like the Python server or Chrome Extension).

## Project Overview

*   **Language:** C++17
*   **Build System:** CMake (3.15+)
*   **Core Library:** `onnxruntime`
*   **Key Features:**
    *   Direct ONNX model inference.
    *   Batch processing support.
    *   Native Messaging Host implementation for browser extensions.
    *   Automatic text chunking for long-form synthesis.

## Key Files

*   `example_onnx.cpp`: The main CLI application for running TTS inference.
*   `native_host.cpp`: Implements the Native Messaging protocol to allow web browsers (Chrome/Firefox) to communicate with this C++ engine via stdin/stdout.
*   `helper.cpp` / `helper.h`: A static helper library (`tts_helper`) containing the core logic:
    *   `TextToSpeech`: Main class managing ONNX sessions (encoder, decoder, vocoder).
    *   `UnicodeProcessor`: Handles text normalization and tokenization.
    *   `Style`: Manages voice style embedding data.
*   `CMakeLists.txt`: Build configuration. Checks for `onnxruntime` and `nlohmann/json`.

## Build Instructions

### Prerequisites
*   **Compiler:** C++17 compatible (GCC, Clang, MSVC).
*   **CMake:** Version 3.15 or higher.
*   **Libraries:**
    *   `onnxruntime` (Must be installed/discoverable via CMake or pkg-config).
    *   `nlohmann/json`.

### Building
```bash
mkdir build
cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
```

This will produce two executables in the `build` directory:
1.  `example_onnx`: The CLI tool.
2.  `native_host`: The browser extension host.

## Usage

### 1. CLI Tool (`example_onnx`)
Run from the `build` directory. Ensure `../assets` exists and is populated (Git LFS).

**Basic Synthesis:**
```bash
./example_onnx --text "Hello world" --voice-style ../assets/voice_styles/M1.json
```

**High Quality (More Steps):**
```bash
./example_onnx --total-step 10 --text "High quality audio."
```

**Batch Mode:**
```bash
./example_onnx --batch \
  --voice-style "../assets/voice_styles/M1.json,../assets/voice_styles/F1.json" \
  --text "Hello|Hi there"
```

**Long-Form Text:**
Simply provide a long string; the tool automatically chunks it unless `--batch` is used.

### 2. Native Host (`native_host`)
This executable is designed to be launched by a browser or a parent process. It communicates via **Standard I/O** using a length-prefixed JSON protocol.

**Protocol:**
1.  **Input (stdin):** 4 bytes (uint32, little-endian) length + JSON string.
2.  **Output (stdout):** 4 bytes (uint32, little-endian) length + JSON string.

**Commands:**
*   `initialize`: Loads models.
    ```json
    { "command": "initialize", "onnx_dir": "../assets/onnx" }
    ```
*   `synthesize`: Generates audio.
    ```json
    {
      "command": "synthesize",
      "text": "Hello",
      "voice_style_path": "path/to/style.json",
      "speed": 1.0,
      "total_step": 5
    }
    ```
    *Returns:* Base64 encoded PCM audio (16-bit).

## Directory Structure

```
cpp/
├── build/              # Build artifacts (created by user)
├── CMakeLists.txt      # Build configuration
├── example_onnx.cpp    # CLI entry point
├── native_host.cpp     # Native Messaging Host entry point
├── helper.cpp          # Core implementation
├── helper.h            # Header for core implementation
└── README.md           # Usage documentation
```
