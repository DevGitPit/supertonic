#include "helper.h"
#include <iostream>
#include <string>
#include <vector>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <fstream>

using json = nlohmann::json;
namespace fs = std::filesystem;

// Base64 encoding
static const std::string base64_chars = 
             "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
             "abcdefghijklmnopqrstuvwxyz"
             "0123456789+/";

std::string base64_encode(unsigned char const* bytes_to_encode, unsigned int in_len) {
    std::string ret;
    int i = 0;
    int j = 0;
    unsigned char char_array_3[3];
    unsigned char char_array_4[4];

    while (in_len--) {
        char_array_3[i++] = *(bytes_to_encode++);
        if (i == 3) {
            char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
            char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
            char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
            char_array_4[3] = char_array_3[2] & 0x3f;

            for(i = 0; (i <4) ; i++)
                ret += base64_chars[char_array_4[i]];
            i = 0;
        }
    }

    if (i) {
        for(j = i; j < 3; j++)
            char_array_3[j] = '\0';

        char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
        char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
        char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
        char_array_4[3] = char_array_3[2] & 0x3f;

        for (j = 0; (j < i + 1); j++)
            ret += base64_chars[char_array_4[j]];

        while((i++ < 3))
            ret += '=';
    }

    return ret;
}

// Global TTS objects
std::unique_ptr<TextToSpeech> g_tts;
Ort::Env g_env(ORT_LOGGING_LEVEL_WARNING, "TTS_Host");
std::unique_ptr<Ort::MemoryInfo> g_memory_info;

void sendMessage(const json& msg) {
    std::string s = msg.dump();
    uint32_t len = static_cast<uint32_t>(s.length());
    std::cout.write(reinterpret_cast<char*>(&len), 4);
    std::cout << s;
    std::cout.flush();
}

int main() {
    // Disable syncing with stdio to avoid buffering issues, 
    // but we need to be careful with mix of C and C++ IO if any.
    // std::ios::sync_with_stdio(false); 
    // Actually, for binary reading on stdin/stdout, we might want to ensure binary mode on Windows, 
    // but on Linux/Termux it's fine.
    
    std::cerr << "Starting Native Messaging Host..." << std::endl;

    // Initialize MemoryInfo once
    g_memory_info = std::make_unique<Ort::MemoryInfo>(Ort::MemoryInfo::CreateCpu(
        OrtAllocatorType::OrtArenaAllocator, OrtMemType::OrtMemTypeDefault
    ));

    while (true) {
        // 1. Read length
        uint32_t length = 0;
        std::cin.read(reinterpret_cast<char*>(&length), 4);
        
        if (std::cin.eof()) {
            std::cerr << "EOF received, exiting." << std::endl;
            break;
        }

        // 2. Read message
        std::string msg_str(length, ' ');
        std::cin.read(&msg_str[0], length);

        json request;
        try {
            request = json::parse(msg_str);
        } catch (const std::exception& e) {
            json error_msg = {{"error", std::string("JSON parse error: ") + e.what()}};
            sendMessage(error_msg);
            continue;
        }

        // 3. Process request
        try {
            std::string command = request.value("command", "");
            
            if (command == "initialize") {
                std::string onnx_dir = request.value("onnx_dir", "../../assets/onnx");
                if (!fs::exists(onnx_dir)) {
                    // Try absolute path if provided, or default
                     if (fs::exists("../assets/onnx")) onnx_dir = "../assets/onnx";
                     else if (fs::exists("assets/onnx")) onnx_dir = "assets/onnx";
                }
                
                std::cerr << "Initializing TTS with models in: " << onnx_dir << std::endl;
                g_tts = loadTextToSpeech(g_env, onnx_dir, false);
                sendMessage({{"status", "initialized"}});
                
            } else if (command == "synthesize") {
                if (!g_tts) {
                    throw std::runtime_error("TTS not initialized. Send 'initialize' command first.");
                }
                
                std::string text = request.value("text", "");
                std::string lang = request.value("lang", "en");
                std::string voice_style_path = request.value("voice_style_path", "");
                float speed = request.value("speed", 1.0f);
                int total_step = request.value("total_step", 5);
                
                if (text.empty()) throw std::runtime_error("Text is empty");
                if (voice_style_path.empty()) throw std::runtime_error("Voice style path is empty");
                
                // Load voice style
                // For the host, we might want to cache styles, but for now load every time
                std::vector<std::string> styles = {voice_style_path};
                auto style = loadVoiceStyle(styles, false);
                
                // Inference
                auto result = g_tts->call(*g_memory_info, text, lang, style, total_step, speed);
                
                // Convert float audio to 16-bit PCM for playback
                std::vector<unsigned char> pcm_data;
                pcm_data.reserve(result.wav.size() * 2);
                
                for (float sample : result.wav) {
                    float clamped = std::max(-1.0f, std::min(1.0f, sample));
                    int16_t int_sample = static_cast<int16_t>(clamped * 32767);
                    pcm_data.push_back(static_cast<unsigned char>(int_sample & 0xFF));
                    pcm_data.push_back(static_cast<unsigned char>((int_sample >> 8) & 0xFF));
                }
                
                // Base64 encode
                std::string b64_audio = base64_encode(pcm_data.data(), pcm_data.size());
                
                sendMessage({
                    {"status", "success"},
                    {"audio", b64_audio},
                    {"sample_rate", g_tts->getSampleRate()}
                });
                
                // Clean up tensors to save memory
                clearTensorBuffers();
                
            } else if (command == "ping") {
                sendMessage({{"status", "pong"}});
            } else {
                sendMessage({{"error", "Unknown command"}});
            }
            
        } catch (const std::exception& e) {
            std::cerr << "Error processing request: " << e.what() << std::endl;
            sendMessage({{"error", e.what()}});
        }
    }
    
    return 0;
}
