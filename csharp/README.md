# TTS ONNX Inference Examples

This guide provides examples for running TTS inference using `ExampleONNX.cs`.

## 📰 Update News

**2026.01.06** - 🎉 **Supertonic 2** released with multilingual support! Now supports English (`en`), Korean (`ko`), Spanish (`es`), Portuguese (`pt`), and French (`fr`). [Demo](https://huggingface.co/spaces/Supertone/supertonic-2) | [Models](https://huggingface.co/Supertone/supertonic-2)

**2025.12.10** - Added [6 new voice styles](https://huggingface.co/Supertone/supertonic/tree/b10dbaf18b316159be75b34d24f740008fddd381) (M3, M4, M5, F3, F4, F5). See [Voices](https://supertone-inc.github.io/supertonic-py/voices/) for details

**2025.12.08** - Optimized ONNX models via [OnnxSlim](https://github.com/inisis/OnnxSlim) now available on [Hugging Face Models](https://huggingface.co/Supertone/supertonic)

**2025.11.23** - Enhanced text preprocessing with comprehensive normalization, emoji removal, symbol replacement, and punctuation handling for improved synthesis quality.

**2025.11.19** - Added `--speed` parameter to control speech synthesis speed (default: 1.05, recommended range: 0.9-1.5).

**2025.11.19** - Added automatic text chunking for long-form inference. Long texts are split into chunks and synthesized with natural pauses.

## Installation

### Prerequisites
- .NET 9.0 SDK or later
- [Download .NET SDK](https://dotnet.microsoft.com/download)

### Install dependencies
```bash
dotnet restore
```

## Basic Usage

### Example 1: Default Inference
Run inference with default settings:
```bash
dotnet run
```

This will use:
- Voice style: `assets/voice_styles/M1.json`
- Text: "This morning, I took a walk in the park, and the sound of the birds and the breeze was so pleasant that I stopped for a long time just to listen."
- Output directory: `results/`
- Total steps: 5
- Number of generations: 4

### Example 2: Batch Inference
Process multiple voice styles and texts at once:
```bash
dotnet run -- \
  --voice-style assets/voice_styles/M1.json,assets/voice_styles/F1.json \
  --text "The sun sets behind the mountains, painting the sky in shades of pink and orange.|오늘 아침에 공원을 산책했는데, 새소리와 바람 소리가 너무 좋아서 한참을 멈춰 서서 들었어요." \
  --lang en,ko \
  --batch
```

This will:
- Use `--batch` flag to enable batch processing mode
- Generate speech for 2 different voice-text pairs
- Use male voice style (M1.json) for the first English text
- Use female voice style (F1.json) for the second Korean text
- Process both samples in a single batch (automatic text chunking disabled)

### Example 3: High Quality Inference
Increase denoising steps for better quality:
```bash
dotnet run -- \
  --total-step 10 \
  --voice-style assets/voice_styles/M1.json \
  --text "Increasing the number of denoising steps improves the output's fidelity and overall quality."
```

This will:
- Use 10 denoising steps instead of the default 5
- Produce higher quality output at the cost of slower inference

### Example 4: Long-Form Inference
For long texts, the system automatically chunks the text into manageable segments and generates a single audio file:
```bash
dotnet run -- \
  --voice-style assets/voice_styles/M1.json \
  --text "Once upon a time, in a small village nestled between rolling hills, there lived a young artist named Clara. Every morning, she would wake up before dawn to capture the first light of day. The golden rays streaming through her window inspired countless paintings. Her work was known throughout the region for its vibrant colors and emotional depth. People from far and wide came to see her gallery, and many said her paintings could tell stories that words never could."
```

This will:
- Automatically split the long text into smaller chunks (max 300 characters by default)
- Process each chunk separately while maintaining natural speech flow
- Insert brief silences (0.3 seconds) between chunks for natural pacing
- Combine all chunks into a single output audio file

**Note**: When using batch mode (`--batch`), automatic text chunking is disabled. Use non-batch mode for long-form text synthesis.

## Available Arguments

| Argument | Type | Default | Description |
|----------|------|---------|-------------|
| `--use-gpu` | flag | False | Use GPU for inference (not supported yet) |
| `--onnx-dir` | str | `assets/onnx` | Path to ONNX model directory |
| `--total-step` | int | 5 | Number of denoising steps (higher = better quality, slower) |
| `--speed` | float | 1.05 | Speech speed factor (higher = faster, lower = slower) |
| `--n-test` | int | 4 | Number of times to generate each sample |
| `--voice-style` | str+ | `assets/voice_styles/M1.json` | Voice style file path(s) (comma-separated) |
| `--text` | str+ | (long default text) | Text(s) to synthesize (pipe-separated: `|`) |
| `--lang` | str+ | `en` | Language(s) for text(s): `en`, `ko`, `es`, `pt`, `fr` (comma-separated) |
| `--save-dir` | str | `results` | Output directory |
| `--batch` | flag | False | Enable batch mode (disables automatic text chunking) |

## Notes

- **Batch Processing**: The number of `--voice-style` files must match the number of `--text` entries
- **Multilingual Support**: Use `--lang` to specify language(s). Available: `en` (English), `ko` (Korean), `es` (Spanish), `pt` (Portuguese), `fr` (French)
- **Long-Form Inference**: Without `--batch` flag, long texts are automatically chunked and combined into a single audio file with natural pauses
- **Quality vs Speed**: Higher `--total-step` values produce better quality but take longer
- **GPU Support**: GPU mode is not supported yet

## Building the Project

### Build for Release
```bash
dotnet build -c Release
```

### Run the compiled executable
```bash
./bin/Release/net9.0/Supertonic
```

## Project Structure

```
csharp/
├── ExampleONNX.cs        # Main inference script
├── Helper.cs             # Helper functions and classes
├── Supertonic.csproj     # Project configuration
├── README.md             # This file
└── results/              # Output directory (created automatically)
```


