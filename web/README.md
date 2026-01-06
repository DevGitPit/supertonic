# Supertonic Web Example

This example demonstrates how to use Supertonic in a web browser using ONNX Runtime Web.

## 📰 Update News

**2026.01.06** - 🎉 **Supertonic 2** released with multilingual support! Now supports English (`en`), Korean (`ko`), Spanish (`es`), Portuguese (`pt`), and French (`fr`). [Demo](https://huggingface.co/spaces/Supertone/supertonic-2) | [Models](https://huggingface.co/Supertone/supertonic-2)

**2025.12.10** - Added [6 new voice styles](https://huggingface.co/Supertone/supertonic/tree/b10dbaf18b316159be75b34d24f740008fddd381) (M3, M4, M5, F3, F4, F5). See [Voices](https://supertone-inc.github.io/supertonic-py/voices/) for details

**2025.12.08** - Optimized ONNX models via [OnnxSlim](https://github.com/inisis/OnnxSlim) now available on [Hugging Face Models](https://huggingface.co/Supertone/supertonic)

**2025.11.23** - Enhanced text preprocessing with comprehensive normalization, emoji removal, symbol replacement, and punctuation handling for improved synthesis quality.

**2025.11.19** - Added speed control slider to adjust speech synthesis speed (default: 1.05, recommended range: 0.9-1.5).

**2025.11.19** - Added automatic text chunking for long-form inference. Long texts are split into chunks and synthesized with natural pauses.

## Features

- 🌐 Runs entirely in the browser (no server required for inference)
- 🚀 WebGPU support with automatic fallback to WebAssembly
- 🌍 Multilingual support: English (en), Korean (ko), Spanish (es), Portuguese (pt), French (fr)
- ⚡ Pre-extracted voice styles for instant generation
- 🎨 Modern, responsive UI
- 🎭 Multiple voice style presets (5 Male, 5 Female)
- 💾 Download generated audio as WAV files
- 📊 Detailed generation statistics (audio length, generation time)
- ⏱️ Real-time progress tracking

## Requirements

- Node.js (for development server)
- Modern web browser (Chrome, Edge, Firefox, Safari)

## Installation

1. Install dependencies:

```bash
npm install
```

## Running the Demo

Start the development server:

```bash
npm run dev
```

This will start a local development server (usually at http://localhost:3000) and open the demo in your browser.

## Usage

1. **Wait for Models to Load**: The app will automatically load models and the default voice style (M1)
2. **Select Voice Style**: Choose from available voice presets
   - **Male 1-5 (M1-M5)**: Male voice styles
   - **Female 1-5 (F1-F5)**: Female voice styles
3. **Select Language**: Choose the language that matches your input text
   - **English (en)**: Default language
   - **한국어 (ko)**: Korean
   - **Español (es)**: Spanish
   - **Português (pt)**: Portuguese
   - **Français (fr)**: French
4. **Enter Text**: Type or paste the text you want to convert to speech
5. **Adjust Settings** (optional):
   - **Total Steps**: More steps = better quality but slower (default: 5)
6. **Generate Speech**: Click the "Generate Speech" button
7. **View Results**: 
   - See the full input text
   - View audio length and generation time statistics
   - Play the generated audio in the browser
   - Download as WAV file

## Multilingual Support

Supertonic 2 supports multiple languages. Make sure to select the correct language for your input text to get the best results. The model will automatically handle text preprocessing and pronunciation for the selected language.

## Technical Details

### Browser Compatibility

This demo uses:
- **ONNX Runtime Web**: For running models in the browser
- **Web Audio API**: For playing generated audio
- **Vite**: For development and bundling

## Notes

- The ONNX models must be accessible at `assets/onnx/` relative to the web root
- Voice style JSON files must be accessible at `assets/voice_styles/` relative to the web root
- Pre-extracted voice styles enable instant generation without audio processing
- Ten voice style presets are provided (M1-M5, F1-F5)

## Troubleshooting

### Models not loading
- Check browser console for errors
- Ensure `assets/onnx/` path is correct and models are accessible
- Check CORS settings if serving from a different domain

### WebGPU not available
- WebGPU is only available in recent Chrome/Edge browsers (version 113+)
- The app will automatically fall back to WebAssembly if WebGPU is not available
- Check the backend badge to see which execution provider is being used

### Out of memory errors
- Try shorter text inputs
- Reduce denoising steps
- Use a browser with more available memory
- Close other tabs to free up memory

### Audio quality issues
- Try different voice style presets
- Increase denoising steps for better quality

### Slow generation
- If using WebAssembly, try a browser that supports WebGPU
- Ensure no other heavy processes are running
- Consider using fewer denoising steps for faster (but lower quality) results