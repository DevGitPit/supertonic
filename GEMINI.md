# Supertonic Project Context

## Project Overview
**Supertonic** is a high-performance, on-device text-to-speech (TTS) system designed for extreme speed and minimal computational overhead. It uses ONNX Runtime to run entirely locally without cloud dependencies.

*   **Key Features:** Blazingly fast (up to 167x real-time), ultra-lightweight (66M parameters), privacy-focused (offline).
*   **Architecture:** The project is structured as a collection of SDKs and examples implementing the core ONNX inference across multiple languages and platforms.
*   **Core Assets:** The trained models and voice styles are stored in the `assets/` directory (managed via Git LFS).

## Directory Structure & Implementations
The repository is organized by language/platform, each serving as a standalone example or SDK:

*   `assets/`: Contains ONNX models and `.json` voice style files. **Required for all examples.**
*   `py/`: Python implementation (uses `uv` for dependency management).
*   `nodejs/`: Node.js implementation (uses `npm`).
*   `web/`: Browser-based implementation using WebGPU/WASM (uses `vite`).
*   `cpp/`: High-performance C++ implementation.
*   `rust/`: Rust implementation (uses `cargo`).
*   `go/`: Go implementation.
*   `java/`, `csharp/`, `swift/`: Implementations for JVM, .NET, and macOS.
*   `android/`: Complete Android application (Gradle).
*   `ios/`: Native iOS application (Xcode).
*   `flutter/`: Cross-platform Flutter package.
*   `chrome_extension/`: Browser extension that interfaces with a local server.
*   `server.py`: An HTTP wrapper (port 8080) around the C++ implementation, allowing external tools (like the Chrome extension) to request TTS synthesis.

## Building and Running

### Prerequisites
1.  **Git LFS**: Essential for downloading the model files.
    ```bash
    git lfs install
    git clone https://huggingface.co/Supertone/supertonic assets
    ```
    *Ensure the `assets` folder is populated before running any code.*

### Quick Start Commands

**Python**
```bash
cd py
uv sync
uv run example_onnx.py
```

**Node.js**
```bash
cd nodejs
npm install
npm start
```

**Web (Browser)**
```bash
cd web
npm install
npm run dev
```

**Rust**
```bash
cd rust
cargo build --release
./target/release/example_onnx
```

**C++ & Server Mode**
To run the C++ example or the local server (required for Chrome Extension):
```bash
cd cpp
mkdir build && cd build
cmake .. && cmake --build . --config Release
# Run standalone
./example_onnx
# Or return to root and run server
cd ../..
python3 server.py
```

## Development Conventions
*   **Ecosystem Specifics**: Treat each subdirectory as an isolated project with its own conventions (e.g., `cargo` for Rust, `npm` for Node/Web, `gradle` for Android).
*   **Model Path**: Most examples expect the `assets/` directory to be in the project root or relative path. Ensure paths are correct when running from subdirectories.
*   **Testing**: Check for `test_*.js`, `test_*.py` or `tests/` folders within specific language directories for unit tests.
