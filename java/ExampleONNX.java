import ai.onnxruntime.*;

import java.io.File;
import java.util.*;

/**
 * TTS Inference Example with ONNX Runtime (Java)
 */
public class ExampleONNX {
    
    /**
     * Command line arguments
     */
    static class Args {
        boolean useGpu = false;
        String onnxDir = "assets/onnx";
        int totalStep = 5;
        float speed = 1.05f;
        int nTest = 4;
        List<String> voiceStyle = Arrays.asList("assets/voice_styles/M1.json");
        List<String> text = Arrays.asList(
            "This morning, I took a walk in the park, and the sound of the birds and the breeze was so pleasant that I stopped for a long time just to listen."
        );
        List<String> lang = Arrays.asList("en");
        String saveDir = "results";
        boolean batch = false;
    }
    
    /**
     * Parse command line arguments
     */
    private static Args parseArgs(String[] args) {
        Args result = new Args();
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--use-gpu":
                    result.useGpu = true;
                    break;
                case "--onnx-dir":
                    if (i + 1 < args.length) result.onnxDir = args[++i];
                    break;
                case "--total-step":
                    if (i + 1 < args.length) result.totalStep = Integer.parseInt(args[++i]);
                    break;
                case "--speed":
                    if (i + 1 < args.length) result.speed = Float.parseFloat(args[++i]);
                    break;
                case "--n-test":
                    if (i + 1 < args.length) result.nTest = Integer.parseInt(args[++i]);
                    break;
                case "--voice-style":
                    if (i + 1 < args.length) {
                        result.voiceStyle = Arrays.asList(args[++i].split(","));
                    }
                    break;
                case "--text":
                    if (i + 1 < args.length) {
                        result.text = Arrays.asList(args[++i].split("\\|"));
                    }
                    break;
                case "--lang":
                    if (i + 1 < args.length) {
                        result.lang = Arrays.asList(args[++i].split(","));
                    }
                    break;
                case "--save-dir":
                    if (i + 1 < args.length) result.saveDir = args[++i];
                    break;
                case "--batch":
                    result.batch = true;
                    break;
            }
        }
        
        return result;
    }
    
    /**
     * Main inference function
     */
    public static void main(String[] args) {
        try {
            System.out.println("=== TTS Inference with ONNX Runtime (Java) ===\n");
            
            // --- 1. Parse arguments --- //
            Args parsedArgs = parseArgs(args);
            int totalStep = parsedArgs.totalStep;
            float speed = parsedArgs.speed;
            int nTest = parsedArgs.nTest;
            String saveDir = parsedArgs.saveDir;
            List<String> voiceStylePaths = parsedArgs.voiceStyle;
            List<String> textList = parsedArgs.text;
            List<String> langList = parsedArgs.lang;
            boolean batch = parsedArgs.batch;
            
            if (batch) {
                if (voiceStylePaths.size() != textList.size()) {
                    throw new RuntimeException("Number of voice styles (" + voiceStylePaths.size() + 
                        ") must match number of texts (" + textList.size() + ")");
                }
                if (langList.size() != textList.size()) {
                    throw new RuntimeException("Number of languages (" + langList.size() + 
                        ") must match number of texts (" + textList.size() + ")");
                }
            }
            
            int bsz = voiceStylePaths.size();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            
            // --- 2. Load TTS components --- //
            TextToSpeech textToSpeech = Helper.loadTextToSpeech(parsedArgs.onnxDir, parsedArgs.useGpu, env);
            
            // --- 3. Load voice styles --- //
            Style style = Helper.loadVoiceStyle(voiceStylePaths, true, env);
            
            // --- 4. Synthesize speech --- //
            File saveDirFile = new File(saveDir);
            if (!saveDirFile.exists()) {
                saveDirFile.mkdirs();
            }
            
            for (int n = 0; n < nTest; n++) {
                System.out.println("\n[" + (n + 1) + "/" + nTest + "] Starting synthesis...");
                
                TTSResult ttsResult;
                if (batch) {
                    ttsResult = Helper.timer("Generating speech from text", () -> {
                        try {
                            return textToSpeech.batch(textList, langList, style, totalStep, speed, env);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } else {
                    ttsResult = Helper.timer("Generating speech from text", () -> {
                        try {
                            return textToSpeech.call(textList.get(0), langList.get(0), style, totalStep, speed, 0.3f, env);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                
                float[] wav = ttsResult.wav;
                float[] duration = ttsResult.duration;
                
                // Save outputs
                for (int i = 0; i < bsz; i++) {
                    String fname = Helper.sanitizeFilename(textList.get(i), 20) + "_" + (n + 1) + ".wav";
                    float[] wavOut;
                    
                    if (batch) {
                        int wavLen = wav.length / bsz;
                        int actualLen = (int) (textToSpeech.sampleRate * duration[i]);
                        wavOut = new float[actualLen];
                        System.arraycopy(wav, i * wavLen, wavOut, 0, Math.min(actualLen, wavLen));
                    } else {
                        // For non-batch mode, wav is a single concatenated audio
                        int actualLen = (int) (textToSpeech.sampleRate * duration[0]);
                        wavOut = new float[Math.min(actualLen, wav.length)];
                        System.arraycopy(wav, 0, wavOut, 0, wavOut.length);
                    }
                    
                    String outputPath = saveDir + "/" + fname;
                    Helper.writeWavFile(outputPath, wavOut, textToSpeech.sampleRate);
                    System.out.println("Saved: " + outputPath);
                }
            }
            
            // Clean up
            style.close();
            textToSpeech.close();
            
            System.out.println("\n=== Synthesis completed successfully! ===");
            
        } catch (Exception e) {
            System.err.println("Error during inference: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
