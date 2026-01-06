using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Supertonic
{
    class Program
    {
        class Args
        {
            public bool UseGpu { get; set; } = false;
            public string OnnxDir { get; set; } = "assets/onnx";
            public int TotalStep { get; set; } = 5;
            public float Speed { get; set; } = 1.05f;
            public int NTest { get; set; } = 4;
            public List<string> VoiceStyle { get; set; } = new List<string> { "assets/voice_styles/M1.json" };
            public List<string> Text { get; set; } = new List<string> 
            { 
                "This morning, I took a walk in the park, and the sound of the birds and the breeze was so pleasant that I stopped for a long time just to listen." 
            };
            public List<string> Lang { get; set; } = new List<string> { "en" };
            public string SaveDir { get; set; } = "results";
            public bool Batch { get; set; } = false;
        }

        static Args ParseArgs(string[] args)
        {
            var result = new Args();
            
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i])
                {
                    case "--use-gpu":
                        result.UseGpu = true;
                        break;
                    case "--batch":
                        result.Batch = true;
                        break;
                    case "--onnx-dir" when i + 1 < args.Length:
                        result.OnnxDir = args[++i];
                        break;
                    case "--total-step" when i + 1 < args.Length:
                        result.TotalStep = int.Parse(args[++i]);
                        break;
                    case "--speed" when i + 1 < args.Length:
                        result.Speed = float.Parse(args[++i]);
                        break;
                    case "--n-test" when i + 1 < args.Length:
                        result.NTest = int.Parse(args[++i]);
                        break;
                    case "--voice-style" when i + 1 < args.Length:
                        result.VoiceStyle = args[++i].Split(',').ToList();
                        break;
                    case "--text" when i + 1 < args.Length:
                        result.Text = args[++i].Split('|').ToList();
                        break;
                    case "--lang" when i + 1 < args.Length:
                        result.Lang = args[++i].Split(',').ToList();
                        break;
                    case "--save-dir" when i + 1 < args.Length:
                        result.SaveDir = args[++i];
                        break;
                }
            }
            
            return result;
        }

        static void Main(string[] args)
        {
            Console.WriteLine("=== TTS Inference with ONNX Runtime (C#) ===\n");

            // --- 1. Parse arguments --- //
            var parsedArgs = ParseArgs(args);
            int totalStep = parsedArgs.TotalStep;
            float speed = parsedArgs.Speed;
            int nTest = parsedArgs.NTest;
            string saveDir = parsedArgs.SaveDir;
            var voiceStylePaths = parsedArgs.VoiceStyle;
            var textList = parsedArgs.Text;
            var langList = parsedArgs.Lang;
            bool batch = parsedArgs.Batch;

            if (voiceStylePaths.Count != textList.Count)
            {
                throw new ArgumentException(
                    $"Number of voice styles ({voiceStylePaths.Count}) must match number of texts ({textList.Count})");
            }
            int bsz = voiceStylePaths.Count;

            // --- 2. Load Text to Speech --- //
            var textToSpeech = Helper.LoadTextToSpeech(parsedArgs.OnnxDir, parsedArgs.UseGpu);
            Console.WriteLine();

            // --- 3. Load Voice Style --- //
            var style = Helper.LoadVoiceStyle(voiceStylePaths, verbose: true);

            // --- 4. Synthesize speech --- //
            for (int n = 0; n < nTest; n++)
            {
                Console.WriteLine($"\n[{n + 1}/{nTest}] Starting synthesis...");
                
                var (wav, duration) = Helper.Timer("Generating speech from text", () =>
                {
                    if (batch)
                    {
                        return textToSpeech.Batch(textList, langList, style, totalStep, speed);
                    }
                    else
                    {
                        return textToSpeech.Call(textList[0], langList[0], style, totalStep, speed);
                    }
                });

                if (!Directory.Exists(saveDir))
                {
                    Directory.CreateDirectory(saveDir);
                }

                for (int b = 0; b < bsz; b++)
                {
                    string fname = $"{Helper.SanitizeFilename(textList[b], 20)}_{n + 1}.wav";
                    
                    int wavLen = (int)(textToSpeech.SampleRate * duration[b]);
                    var wavOut = new float[wavLen];
                    Array.Copy(wav, b * wav.Length / bsz, wavOut, 0, Math.Min(wavLen, wav.Length / bsz));

                    string outputPath = Path.Combine(saveDir, fname);
                    Helper.WriteWavFile(outputPath, wavOut, textToSpeech.SampleRate);
                    Console.WriteLine($"Saved: {outputPath}");
                }
            }

            Console.WriteLine("\n=== Synthesis completed successfully! ===");
        }
    }
}

