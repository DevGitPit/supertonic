package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	ort "github.com/yalue/onnxruntime_go"
)

// Args holds command line arguments
type Args struct {
	useGPU      bool
	onnxDir     string
	totalStep   int
	speed       float64
	nTest       int
	voiceStyle  []string
	text        []string
	lang        []string
	saveDir     string
	batch       bool
}

func parseArgs() *Args {
	args := &Args{}

	flag.BoolVar(&args.useGPU, "use-gpu", false, "Use GPU for inference (default: CPU)")
	flag.StringVar(&args.onnxDir, "onnx-dir", "assets/onnx", "Path to ONNX model directory")
	flag.IntVar(&args.totalStep, "total-step", 5, "Number of denoising steps")
	flag.Float64Var(&args.speed, "speed", 1.05, "Speech speed factor (higher = faster)")
	flag.IntVar(&args.nTest, "n-test", 4, "Number of times to generate")
	flag.StringVar(&args.saveDir, "save-dir", "results", "Output directory")
	flag.BoolVar(&args.batch, "batch", false, "Enable batch mode (multiple text-style pairs)")

	var voiceStyleStr, textStr, langStr string
	flag.StringVar(&voiceStyleStr, "voice-style", "assets/voice_styles/M1.json", "Voice style file path(s), comma-separated")
	flag.StringVar(&textStr, "text", "This morning, I took a walk in the park, and the sound of the birds and the breeze was so pleasant that I stopped for a long time just to listen.", "Text(s) to synthesize, pipe-separated")
	flag.StringVar(&langStr, "lang", "en", "Language(s) for synthesis, comma-separated (en, ko, es, pt, fr)")

	flag.Parse()

	// Parse comma-separated voice-style
	if voiceStyleStr != "" {
		args.voiceStyle = strings.Split(voiceStyleStr, ",")
		for i := range args.voiceStyle {
			args.voiceStyle[i] = strings.TrimSpace(args.voiceStyle[i])
		}
	}

	// Parse pipe-separated text
	if textStr != "" {
		args.text = strings.Split(textStr, "|")
		for i := range args.text {
			args.text[i] = strings.TrimSpace(args.text[i])
		}
	}

	// Parse comma-separated lang
	if langStr != "" {
		args.lang = strings.Split(langStr, ",")
		for i := range args.lang {
			args.lang[i] = strings.TrimSpace(args.lang[i])
		}
	}

	return args
}

func main() {
	fmt.Println("=== TTS Inference with ONNX Runtime (Go) ===\n")

	// --- 1. Parse arguments --- //
	args := parseArgs()
	totalStep := args.totalStep
	speed := float32(args.speed)
	nTest := args.nTest
	saveDir := args.saveDir
	voiceStylePaths := args.voiceStyle
	textList := args.text
	langList := args.lang
	batch := args.batch

	if batch {
		if len(voiceStylePaths) != len(textList) {
			fmt.Printf("Error: Number of voice styles (%d) must match number of texts (%d)\n",
				len(voiceStylePaths), len(textList))
			os.Exit(1)
		}
		if len(langList) != len(textList) {
			fmt.Printf("Error: Number of languages (%d) must match number of texts (%d)\n",
				len(langList), len(textList))
			os.Exit(1)
		}
	}

	bsz := len(voiceStylePaths)

	// Initialize ONNX Runtime
	if err := InitializeONNXRuntime(); err != nil {
		fmt.Printf("Error initializing ONNX Runtime: %v\n", err)
		os.Exit(1)
	}
	defer ort.DestroyEnvironment()

	// --- 2. Load config --- //
	cfg, err := LoadCfgs(args.onnxDir)
	if err != nil {
		fmt.Printf("Error loading config: %v\n", err)
		os.Exit(1)
	}

	// --- 3. Load TTS components --- //
	textToSpeech, err := LoadTextToSpeech(args.onnxDir, args.useGPU, cfg)
	if err != nil {
		fmt.Printf("Error loading TTS components: %v\n", err)
		os.Exit(1)
	}
	defer textToSpeech.Destroy()

	// --- 4. Load voice styles --- //
	style, err := LoadVoiceStyle(voiceStylePaths, true)
	if err != nil {
		fmt.Printf("Error loading voice styles: %v\n", err)
		os.Exit(1)
	}
	defer style.Destroy()

	// --- 5. Synthesize speech --- //
	if err := os.MkdirAll(saveDir, 0755); err != nil {
		fmt.Printf("Error creating save directory: %v\n", err)
		os.Exit(1)
	}

	for n := 0; n < nTest; n++ {
		fmt.Printf("\n[%d/%d] Starting synthesis...\n", n+1, nTest)

		var wav []float32
		var duration []float32

		if batch {
			Timer("Generating speech from text", func() interface{} {
				w, d, err := textToSpeech.Batch(textList, langList, style, totalStep, speed)
				if err != nil {
					fmt.Printf("Error generating speech: %v\n", err)
					os.Exit(1)
				}
				wav = w
				duration = d
				return nil
			})
		} else {
			Timer("Generating speech from text", func() interface{} {
				w, d, err := textToSpeech.Call(textList[0], langList[0], style, totalStep, speed, 0.3)
				if err != nil {
					fmt.Printf("Error generating speech: %v\n", err)
					os.Exit(1)
				}
				wav = w
				duration = []float32{d}
				return nil
			})
		}

		// Save outputs
		for i := 0; i < bsz; i++ {
			fname := fmt.Sprintf("%s_%d.wav", sanitizeFilename(textList[i], 20), n+1)
			var wavOut []float64
			
			if batch {
				wavOut = extractWavSegment(wav, duration[i], textToSpeech.SampleRate, i, bsz)
			} else {
				// For non-batch mode, wav is a single concatenated audio
				wavLen := int(float32(textToSpeech.SampleRate) * duration[0])
				wavOut = make([]float64, wavLen)
				for j := 0; j < wavLen && j < len(wav); j++ {
					wavOut[j] = float64(wav[j])
				}
			}
			
			outputPath := filepath.Join(saveDir, fname)
			if err := writeWavFile(outputPath, wavOut, textToSpeech.SampleRate); err != nil {
				fmt.Printf("Error writing wav file: %v\n", err)
				continue
			}
			fmt.Printf("Saved: %s\n", outputPath)
		}
	}

	fmt.Println("\n=== Synthesis completed successfully! ===")
}
