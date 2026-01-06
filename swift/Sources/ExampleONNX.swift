import Foundation
import OnnxRuntimeBindings

struct Args {
    var useGpu: Bool = false
    var onnxDir: String = "assets/onnx"
    var totalStep: Int = 5
    var speed: Float = 1.05
    var nTest: Int = 4
    var voiceStyle: [String] = ["assets/voice_styles/M1.json"]
    var text: [String] = ["This morning, I took a walk in the park, and the sound of the birds and the breeze was so pleasant that I stopped for a long time just to listen."]
    var lang: [String] = ["en"]
    var saveDir: String = "results"
    var batch: Bool = false
}

func parseArgs() -> Args {
    var args = Args()
    let arguments = CommandLine.arguments
    
    var i = 1
    while i < arguments.count {
        let arg = arguments[i]
        
        switch arg {
        case "--use-gpu":
            args.useGpu = true
        case "--onnx-dir":
            if i + 1 < arguments.count {
                args.onnxDir = arguments[i + 1]
                i += 1
            }
        case "--total-step":
            if i + 1 < arguments.count {
                args.totalStep = Int(arguments[i + 1]) ?? 5
                i += 1
            }
        case "--speed":
            if i + 1 < arguments.count {
                args.speed = Float(arguments[i + 1]) ?? 1.05
                i += 1
            }
        case "--n-test":
            if i + 1 < arguments.count {
                args.nTest = Int(arguments[i + 1]) ?? 4
                i += 1
            }
        case "--voice-style":
            if i + 1 < arguments.count {
                args.voiceStyle = arguments[i + 1].components(separatedBy: ",")
                i += 1
            }
        case "--text":
            if i + 1 < arguments.count {
                args.text = arguments[i + 1].components(separatedBy: "|")
                i += 1
            }
        case "--lang":
            if i + 1 < arguments.count {
                args.lang = arguments[i + 1].components(separatedBy: ",")
                i += 1
            }
        case "--save-dir":
            if i + 1 < arguments.count {
                args.saveDir = arguments[i + 1]
                i += 1
            }
        case "--batch":
            args.batch = true
        default:
            break
        }
        
        i += 1
    }
    
    return args
}

@main
struct ExampleONNX {
    static func main() async {
        print("=== TTS Inference with ONNX Runtime (Swift) ===\n")
        
        // --- 1. Parse arguments --- //
        let args = parseArgs()
        
        if args.batch {
            guard args.voiceStyle.count == args.text.count else {
                print("Error: Number of voice styles (\(args.voiceStyle.count)) must match number of texts (\(args.text.count))")
                return
            }
            guard args.lang.count == args.text.count else {
                print("Error: Number of languages (\(args.lang.count)) must match number of texts (\(args.text.count))")
                return
            }
        }
        
        let bsz = args.voiceStyle.count
        
        do {
            let env = try ORTEnv(loggingLevel: .warning)
            
            // --- 2. Load TTS components --- //
            let textToSpeech = try loadTextToSpeech(args.onnxDir, args.useGpu, env)
            
            // --- 3. Load voice styles --- //
            let style = try loadVoiceStyle(args.voiceStyle, verbose: true)
            
            // --- 4. Synthesize speech --- //
            try? FileManager.default.createDirectory(atPath: args.saveDir, withIntermediateDirectories: true)
            
            for n in 0..<args.nTest {
                print("\n[\(n + 1)/\(args.nTest)] Starting synthesis...")
                
                let wav: [Float]
                let duration: [Float]
                
                if args.batch {
                    let result = try timer("Generating speech from text") {
                        try textToSpeech.batch(args.text, args.lang, style, args.totalStep, speed: args.speed)
                    }
                    wav = result.wav
                    duration = result.duration
                } else {
                    let result = try timer("Generating speech from text") {
                        try textToSpeech.call(args.text[0], args.lang[0], style, args.totalStep, speed: args.speed, silenceDuration: 0.3)
                    }
                    wav = result.wav
                    duration = [result.duration]
                }
                
                // Save outputs
                for i in 0..<bsz {
                    let fname = "\(sanitizeFilename(args.text[i], maxLen: 20))_\(n + 1).wav"
                    let wavOut: [Float]
                    
                    if args.batch {
                        let wavLen = wav.count / bsz
                        let actualLen = Int(Float(textToSpeech.sampleRate) * duration[i])
                        let wavStart = i * wavLen
                        let wavEnd = min(wavStart + actualLen, wavStart + wavLen)
                        wavOut = Array(wav[wavStart..<wavEnd])
                    } else {
                        // For non-batch mode, wav is a single concatenated audio
                        let actualLen = Int(Float(textToSpeech.sampleRate) * duration[0])
                        wavOut = Array(wav.prefix(actualLen))
                    }
                    
                    let outputPath = "\(args.saveDir)/\(fname)"
                    try writeWavFile(outputPath, wavOut, textToSpeech.sampleRate)
                    print("Saved: \(outputPath)")
                }
            }
            
            print("\n=== Synthesis completed successfully! ===")
            
        } catch {
            print("Error during inference: \(error)")
            exit(1)
        }
    }
}
