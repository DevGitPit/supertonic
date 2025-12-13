#include "helper.h"
#include <fstream>
#include <iostream>
#include <cmath>
#include <algorithm>
#include <random>
#include <sstream>
#include <regex>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

// Global tensor buffers for memory management
static std::vector<std::vector<float>> g_tensor_buffers_float;
static std::vector<std::vector<int64_t>> g_tensor_buffers_int64;

void clearTensorBuffers() {
    g_tensor_buffers_float.clear();
    g_tensor_buffers_int64.clear();
}

// ============================================================================
// Helper function - trim
// ============================================================================

static std::string trim(const std::string& str) {
    size_t start = 0;
    while (start < str.size() && std::isspace(static_cast<unsigned char>(str[start]))) {
        start++;
    }
    
    size_t end = str.size();
    while (end > start && std::isspace(static_cast<unsigned char>(str[end - 1]))) {
        end--;
    }
    
    return str.substr(start, end - start);
}

// ============================================================================
// UnicodeProcessor implementation
// ============================================================================

UnicodeProcessor::UnicodeProcessor(const std::string& unicode_indexer_json_path) {
    indexer_ = loadJsonInt64(unicode_indexer_json_path);
}

std::string UnicodeProcessor::preprocessText(const std::string& text) {
    // TODO: Need advanced normalizer for better performance
    // NOTE: C++ doesn't have built-in Unicode normalization like Python's NFKD
    // For full Unicode normalization, consider using ICU library
    // This implementation handles basic text preprocessing
    
    std::string result = text;
    
    // FIXME: this should be fixed for non-English languages
    
    // Remove emojis and various Unicode symbols
    // Using regex to remove common emoji ranges and special symbols
    // Note: This is a simplified version - full emoji support needs UTF-8 handling
    std::regex emoji_pattern(
        "[\xF0\x9F][\x80-\xBF]{2}|"  // Common emoji pattern in UTF-8
        "[\xE2][\x80-\xBF]{2}|"       // Various symbols
        "[\xE2][\x98-\x9E][\x80-\xBF]" // More symbols
    );
    result = std::regex_replace(result, emoji_pattern, "");
    
    // Replace various dashes and symbols
    struct Replacement {
        const char* from;
        const char* to;
    };
    
    const Replacement replacements[] = {
        {"–", "-"},      // en dash
        {"‑", "-"},      // non-breaking hyphen
        {"—", "-"},      // em dash
        {"¯", " "},      // macron
        {"_", " "},      // underscore
        { u8"\u201C", "\"" },   // left double quote “
        { u8"\u201D", "\"" },   // right double quote ”
        { u8"\u2018", "'"  },   // left single quote ‘
        { u8"\u2019", "'"  },   // right single quote ’
        {"´", "'"},      // acute accent
        {"`", "'"},      // grave accent
        {"[", " "},      // left bracket
        {"]", " "},      // right bracket
        {"|", " "},      // vertical bar
        {"/", " "},      // slash
        {"#", " "},      // hash
        {"→", " "},      // right arrow
        {"←", " "},      // left arrow
    };
    
    for (const auto& repl : replacements) {
        size_t pos = 0;
        while ((pos = result.find(repl.from, pos)) != std::string::npos) {
            result.replace(pos, strlen(repl.from), repl.to);
            pos += strlen(repl.to);
        }
    }
    
    // Remove combining diacritics (common combining marks in UTF-8)
    // FIXME: this should be fixed for non-English languages
    std::regex diacritics_pattern(
        "[\xCC\xCD][\x80-\xBF]"  // Combining diacritical marks range
    );
    result = std::regex_replace(result, diacritics_pattern, "");
    
    // Remove special symbols
    const char* special_symbols[] = {"♥", "☆", "♡", "©", "\\"};
    for (const char* symbol : special_symbols) {
        size_t pos = 0;
        while ((pos = result.find(symbol, pos)) != std::string::npos) {
            result.erase(pos, strlen(symbol));
        }
    }
    
    // Replace known expressions
    const Replacement expr_replacements[] = {
        {"@", " at "},
        {"e.g.,", "for example, "},
        {"i.e.,", "that is, "},
    };
    
    for (const auto& repl : expr_replacements) {
        size_t pos = 0;
        while ((pos = result.find(repl.from, pos)) != std::string::npos) {
            result.replace(pos, strlen(repl.from), repl.to);
            pos += strlen(repl.to);
        }
    }
    
    // Fix spacing around punctuation
    result = std::regex_replace(result, std::regex(" ,"), ",");
    result = std::regex_replace(result, std::regex(" \\."), ".");
    result = std::regex_replace(result, std::regex(" !"), "!");
    result = std::regex_replace(result, std::regex(" \\?"), "?");
    result = std::regex_replace(result, std::regex(" ;"), ";");
    result = std::regex_replace(result, std::regex(" :"), ":");
    result = std::regex_replace(result, std::regex(" '"), "'");
    
    // Remove duplicate quotes
    while (result.find("\"\"") != std::string::npos) {
        size_t pos = result.find("\"\"");
        result.replace(pos, 2, "\"");
    }
    while (result.find("''") != std::string::npos) {
        size_t pos = result.find("''");
        result.replace(pos, 2, "'");
    }
    while (result.find("``") != std::string::npos) {
        size_t pos = result.find("``");
        result.replace(pos, 2, "`");
    }
    
    // Remove extra spaces
    result = std::regex_replace(result, std::regex("\\s+"), " ");
    result = trim(result);
    
    // If text doesn't end with punctuation, quotes, or closing brackets, add a period
    if (!result.empty()) {
        char last_char = result.back();
        bool ends_with_punct = (
            last_char == '.' || last_char == '!' || last_char == '?' ||
            last_char == ';' || last_char == ':' || last_char == ',' ||
            last_char == '\'' || last_char == '"' || last_char == ')' ||
            last_char == ']' || last_char == '}' || last_char == '>'
        );
        
        // Check for UTF-8 multibyte ending punctuation (e.g., …, 。, curly quotes, etc.)
        if (!ends_with_punct && result.size() >= 3) {
            std::string last_three = result.substr(result.size() - 3);
            if (last_three == "…" || last_three == "。" || 
                last_three == "」" || last_three == "』" ||
                last_three == "】" || last_three == "〉" ||
                last_three == "》" || last_three == "›" ||
                last_three == "»" || last_three == u8"\u201C" ||
                last_three == u8"\u201D" || last_three == u8"\u2018" ||
                last_three == u8"\u2019") {
                ends_with_punct = true;
            }
        }
        
        if (!ends_with_punct) {
            result += ".";
        }
    }
    
    return result;
}

std::vector<uint16_t> UnicodeProcessor::textToUnicodeValues(const std::string& text) {
    std::vector<uint16_t> unicode_values;
    for (char c : text) {
        unicode_values.push_back(static_cast<uint16_t>(static_cast<unsigned char>(c)));
    }
    return unicode_values;
}

std::vector<std::vector<std::vector<float>>> UnicodeProcessor::getTextMask(
    const std::vector<int64_t>& text_ids_lengths
) {
    return lengthToMask(text_ids_lengths);
}

void UnicodeProcessor::call(
    const std::vector<std::string>& text_list,
    std::vector<std::vector<int64_t>>& text_ids,
    std::vector<std::vector<std::vector<float>>>& text_mask
) {
    std::vector<std::string> processed_texts;
    for (const auto& text : text_list) {
        processed_texts.push_back(preprocessText(text));
    }
    
    std::vector<int64_t> text_ids_lengths;
    for (const auto& text : processed_texts) {
        text_ids_lengths.push_back(static_cast<int64_t>(text.length()));
    }
    
    int64_t max_len = *std::max_element(text_ids_lengths.begin(), text_ids_lengths.end());
    
    text_ids.resize(text_list.size());
    for (size_t i = 0; i < processed_texts.size(); i++) {
        text_ids[i].resize(max_len, 0);
        auto unicode_vals = textToUnicodeValues(processed_texts[i]);
        for (size_t j = 0; j < unicode_vals.size(); j++) {
            if (unicode_vals[j] < indexer_.size()) {
                text_ids[i][j] = indexer_[unicode_vals[j]];
            }
        }
    }
    
    text_mask = getTextMask(text_ids_lengths);
}

// ============================================================================
// Style implementation
// ============================================================================

Style::Style(const std::vector<float>& ttl_data, const std::vector<int64_t>& ttl_shape,
             const std::vector<float>& dp_data, const std::vector<int64_t>& dp_shape)
    : ttl_data_(ttl_data), ttl_shape_(ttl_shape), dp_data_(dp_data), dp_shape_(dp_shape) {}

// ============================================================================
// TextToSpeech implementation
// ============================================================================

TextToSpeech::TextToSpeech(
    const Config& cfgs,
    UnicodeProcessor* text_processor,
    Ort::Session* dp_ort,
    Ort::Session* text_enc_ort,
    Ort::Session* vector_est_ort,
    Ort::Session* vocoder_ort
) : cfgs_(cfgs),
    text_processor_(text_processor),
    dp_ort_(dp_ort),
    text_enc_ort_(text_enc_ort),
    vector_est_ort_(vector_est_ort),
    vocoder_ort_(vocoder_ort) {
    
    sample_rate_ = cfgs.ae.sample_rate;
    base_chunk_size_ = cfgs.ae.base_chunk_size;
    chunk_compress_factor_ = cfgs.ttl.chunk_compress_factor;
    ldim_ = cfgs.ttl.latent_dim;
}

void TextToSpeech::sampleNoisyLatent(
    const std::vector<float>& duration,
    std::vector<std::vector<std::vector<float>>>& noisy_latent,
    std::vector<std::vector<std::vector<float>>>& latent_mask
) {
    int bsz = duration.size();
    float wav_len_max = *std::max_element(duration.begin(), duration.end()) * sample_rate_;
    
    std::vector<int64_t> wav_lengths;
    for (float d : duration) {
        wav_lengths.push_back(static_cast<int64_t>(d * sample_rate_));
    }
    
    int chunk_size = base_chunk_size_ * chunk_compress_factor_;
    int latent_len = static_cast<int>((wav_len_max + chunk_size - 1) / chunk_size);
    int latent_dim = ldim_ * chunk_compress_factor_;
    
    // Generate random noise with normal distribution
    std::random_device rd;
    std::mt19937 gen(rd());
    std::normal_distribution<float> dist(0.0f, 1.0f);
    
    noisy_latent.resize(bsz);
    for (int b = 0; b < bsz; b++) {
        noisy_latent[b].resize(latent_dim);
        for (int d = 0; d < latent_dim; d++) {
            noisy_latent[b][d].resize(latent_len);
            for (int t = 0; t < latent_len; t++) {
                noisy_latent[b][d][t] = dist(gen);
            }
        }
    }
    
    latent_mask = getLatentMask(wav_lengths, base_chunk_size_, chunk_compress_factor_);
    
    // Apply mask
    for (int b = 0; b < bsz; b++) {
        for (int d = 0; d < latent_dim; d++) {
            for (size_t t = 0; t < noisy_latent[b][d].size(); t++) {
                noisy_latent[b][d][t] *= latent_mask[b][0][t];
            }
        }
    }
}

TextToSpeech::SynthesisResult TextToSpeech::_infer(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::string>& text_list,
    const Style& style,
    int total_step,
    float speed
) {
    int bsz = text_list.size();
    
    if (bsz != style.getTtlShape()[0]) {
        throw std::runtime_error("Number of texts must match number of style vectors");
    }
    
    // Process text
    std::vector<std::vector<int64_t>> text_ids;
    std::vector<std::vector<std::vector<float>>> text_mask;
    text_processor_->call(text_list, text_ids, text_mask);
    
    std::vector<int64_t> text_ids_shape = {bsz, static_cast<int64_t>(text_ids[0].size())};
    std::vector<int64_t> text_mask_shape = {bsz, 1, static_cast<int64_t>(text_mask[0][0].size())};
    
    auto text_ids_tensor = intArrayToTensor(memory_info, text_ids, text_ids_shape);
    auto text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
    
    // Create style tensors
    auto style_ttl_tensor = Ort::Value::CreateTensor<float>(
        memory_info,
        const_cast<float*>(style.getTtlData().data()),
        style.getTtlData().size(),
        style.getTtlShape().data(),
        style.getTtlShape().size()
    );
    
    auto style_dp_tensor = Ort::Value::CreateTensor<float>(
        memory_info,
        const_cast<float*>(style.getDpData().data()),
        style.getDpData().size(),
        style.getDpShape().data(),
        style.getDpShape().size()
    );
    
    // Run duration predictor
    const char* dp_input_names[] = {"text_ids", "style_dp", "text_mask"};
    const char* dp_output_names[] = {"duration"};
    std::vector<Ort::Value> dp_inputs;
    dp_inputs.push_back(std::move(text_ids_tensor));
    dp_inputs.push_back(std::move(style_dp_tensor));
    dp_inputs.push_back(std::move(text_mask_tensor));
    
    auto dp_outputs = dp_ort_->Run(
        Ort::RunOptions{nullptr},
        dp_input_names, dp_inputs.data(), dp_inputs.size(),
        dp_output_names, 1
    );
    
    auto* dur_data = dp_outputs[0].GetTensorMutableData<float>();
    std::vector<float> duration(dur_data, dur_data + bsz);
    
    // Apply speed factor to duration
    for (auto& dur : duration) {
        dur /= speed;
    }
    
    // Create new tensors for text encoder (previous ones were moved)
    text_ids_tensor = intArrayToTensor(memory_info, text_ids, text_ids_shape);
    text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
    style_ttl_tensor = Ort::Value::CreateTensor<float>(
        memory_info,
        const_cast<float*>(style.getTtlData().data()),
        style.getTtlData().size(),
        style.getTtlShape().data(),
        style.getTtlShape().size()
    );
    
    // Run text encoder
    const char* text_enc_input_names[] = {"text_ids", "style_ttl", "text_mask"};
    const char* text_enc_output_names[] = {"text_emb"};
    std::vector<Ort::Value> text_enc_inputs;
    text_enc_inputs.push_back(std::move(text_ids_tensor));
    text_enc_inputs.push_back(std::move(style_ttl_tensor));
    text_enc_inputs.push_back(std::move(text_mask_tensor));
    
    auto text_enc_outputs = text_enc_ort_->Run(
        Ort::RunOptions{nullptr},
        text_enc_input_names, text_enc_inputs.data(), text_enc_inputs.size(),
        text_enc_output_names, 1
    );
    
    // Sample noisy latent
    std::vector<std::vector<std::vector<float>>> xt, latent_mask;
    sampleNoisyLatent(duration, xt, latent_mask);
    
    std::vector<int64_t> latent_shape = {
        bsz,
        static_cast<int64_t>(xt[0].size()),
        static_cast<int64_t>(xt[0][0].size())
    };
    std::vector<int64_t> latent_mask_shape = {
        bsz, 1,
        static_cast<int64_t>(latent_mask[0][0].size())
    };
    
    // Prepare scalar tensors
    std::vector<float> total_step_vec(bsz, static_cast<float>(total_step));
    auto total_step_tensor = Ort::Value::CreateTensor<float>(
        memory_info,
        total_step_vec.data(),
        total_step_vec.size(),
        std::vector<int64_t>{bsz}.data(),
        1
    );
    
    // Store text_emb data to reuse across iterations
    auto text_emb_info = text_enc_outputs[0].GetTensorTypeAndShapeInfo();
    size_t text_emb_size = text_emb_info.GetElementCount();
    auto* text_emb_data = text_enc_outputs[0].GetTensorMutableData<float>();
    std::vector<float> text_emb_vec(text_emb_data, text_emb_data + text_emb_size);
    auto text_emb_shape = text_emb_info.GetShape();
    
    // Iterative denoising
    for (int step = 0; step < total_step; step++) {
        std::vector<float> current_step_vec(bsz, static_cast<float>(step));
        
        text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
        auto latent_mask_tensor = arrayToTensor(memory_info, latent_mask, latent_mask_shape);
        auto noisy_latent_tensor = arrayToTensor(memory_info, xt, latent_shape);
        style_ttl_tensor = Ort::Value::CreateTensor<float>(
            memory_info,
            const_cast<float*>(style.getTtlData().data()),
            style.getTtlData().size(),
            style.getTtlShape().data(),
            style.getTtlShape().size()
        );
        
        auto text_emb_tensor = Ort::Value::CreateTensor<float>(
            memory_info,
            text_emb_vec.data(),
            text_emb_vec.size(),
            text_emb_shape.data(),
            text_emb_shape.size()
        );
        
        auto current_step_tensor = Ort::Value::CreateTensor<float>(
            memory_info,
            current_step_vec.data(),
            current_step_vec.size(),
            std::vector<int64_t>{bsz}.data(),
            1
        );
        
        const char* vector_est_input_names[] = {
            "noisy_latent", "text_emb", "style_ttl", "text_mask", "latent_mask", "total_step", "current_step"
        };
        const char* vector_est_output_names[] = {"denoised_latent"};
        
        std::vector<Ort::Value> vector_est_inputs;
        vector_est_inputs.push_back(std::move(noisy_latent_tensor));
        vector_est_inputs.push_back(std::move(text_emb_tensor));
        vector_est_inputs.push_back(std::move(style_ttl_tensor));
        vector_est_inputs.push_back(std::move(text_mask_tensor));
        vector_est_inputs.push_back(std::move(latent_mask_tensor));
        
        // Create a new total_step tensor for each iteration
        auto total_step_tensor_iter = Ort::Value::CreateTensor<float>(
            memory_info,
            total_step_vec.data(),
            total_step_vec.size(),
            std::vector<int64_t>{bsz}.data(),
            1
        );
        vector_est_inputs.push_back(std::move(total_step_tensor_iter));
        vector_est_inputs.push_back(std::move(current_step_tensor));
        
        auto vector_est_outputs = vector_est_ort_->Run(
            Ort::RunOptions{nullptr},
            vector_est_input_names, vector_est_inputs.data(), vector_est_inputs.size(),
            vector_est_output_names, 1
        );
        
        // Update xt with denoised output
        auto* denoised_data = vector_est_outputs[0].GetTensorMutableData<float>();
        size_t idx = 0;
        for (int b = 0; b < bsz; b++) {
            for (size_t d = 0; d < xt[b].size(); d++) {
                for (size_t t = 0; t < xt[b][d].size(); t++) {
                    xt[b][d][t] = denoised_data[idx++];
                }
            }
        }
    }
    
    // Run vocoder
    auto latent_tensor = arrayToTensor(memory_info, xt, latent_shape);
    const char* vocoder_input_names[] = {"latent"};
    const char* vocoder_output_names[] = {"wav_tts"};
    std::vector<Ort::Value> vocoder_inputs;
    vocoder_inputs.push_back(std::move(latent_tensor));
    
    auto vocoder_outputs = vocoder_ort_->Run(
        Ort::RunOptions{nullptr},
        vocoder_input_names, vocoder_inputs.data(), vocoder_inputs.size(),
        vocoder_output_names, 1
    );
    
    auto wav_info = vocoder_outputs[0].GetTensorTypeAndShapeInfo();
    size_t wav_size = wav_info.GetElementCount();
    auto* wav_data = vocoder_outputs[0].GetTensorMutableData<float>();
    
    SynthesisResult result;
    result.wav.assign(wav_data, wav_data + wav_size);
    result.duration = duration;
    
    return result;
}

TextToSpeech::SynthesisResult TextToSpeech::call(
    Ort::MemoryInfo& memory_info,
    const std::string& text,
    const Style& style,
    int total_step,
    float speed,
    float silence_duration
) {
    if (style.getTtlShape()[0] != 1) {
        throw std::runtime_error("Single speaker text to speech only supports single style");
    }
    
    auto text_list = chunkText(text);
    std::vector<float> wav_cat;
    float dur_cat = 0.0f;
    
    for (const auto& chunk : text_list) {
        auto result = _infer(memory_info, {chunk}, style, total_step, speed);
        
        if (wav_cat.empty()) {
            wav_cat = result.wav;
            dur_cat = result.duration[0];
        } else {
            int silence_len = static_cast<int>(silence_duration * sample_rate_);
            std::vector<float> silence(silence_len, 0.0f);
            wav_cat.insert(wav_cat.end(), silence.begin(), silence.end());
            wav_cat.insert(wav_cat.end(), result.wav.begin(), result.wav.end());
            dur_cat += result.duration[0] + silence_duration;
        }
    }
    
    SynthesisResult final_result;
    final_result.wav = wav_cat;
    final_result.duration = {dur_cat};
    
    return final_result;
}

TextToSpeech::SynthesisResult TextToSpeech::batch(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::string>& text_list,
    const Style& style,
    int total_step,
    float speed
) {
    return _infer(memory_info, text_list, style, total_step, speed);
}

// ============================================================================
// Utility functions
// ============================================================================

std::vector<std::vector<std::vector<float>>> lengthToMask(
    const std::vector<int64_t>& lengths, int max_len
) {
    if (max_len == -1) {
        max_len = *std::max_element(lengths.begin(), lengths.end());
    }
    
    std::vector<std::vector<std::vector<float>>> mask;
    for (auto len : lengths) {
        std::vector<std::vector<float>> batch_mask(1);
        batch_mask[0].resize(max_len);
        for (int i = 0; i < max_len; i++) {
            batch_mask[0][i] = (i < len) ? 1.0f : 0.0f;
        }
        mask.push_back(batch_mask);
    }
    return mask;
}

std::vector<std::vector<std::vector<float>>> getLatentMask(
    const std::vector<int64_t>& wav_lengths,
    int base_chunk_size,
    int chunk_compress_factor
) {
    int latent_size = base_chunk_size * chunk_compress_factor;
    std::vector<int64_t> latent_lengths;
    for (auto len : wav_lengths) {
        latent_lengths.push_back((len + latent_size - 1) / latent_size);
    }
    return lengthToMask(latent_lengths);
}

// ============================================================================
// ONNX model loading
// ============================================================================

std::unique_ptr<Ort::Session> loadOnnx(
    Ort::Env& env,
    const std::string& onnx_path,
    const Ort::SessionOptions& opts
) {
    return std::make_unique<Ort::Session>(env, onnx_path.c_str(), opts);
}

OnnxModels loadOnnxAll(
    Ort::Env& env,
    const std::string& onnx_dir,
    const Ort::SessionOptions& opts
) {
    OnnxModels models;
    models.dp = loadOnnx(env, onnx_dir + "/duration_predictor.onnx", opts);
    models.text_enc = loadOnnx(env, onnx_dir + "/text_encoder.onnx", opts);
    models.vector_est = loadOnnx(env, onnx_dir + "/vector_estimator.onnx", opts);
    models.vocoder = loadOnnx(env, onnx_dir + "/vocoder.onnx", opts);
    return models;
}

// ============================================================================
// Configuration and processor loading
// ============================================================================

Config loadCfgs(const std::string& onnx_dir) {
    std::string cfg_path = onnx_dir + "/tts.json";
    std::ifstream file(cfg_path);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open config file: " + cfg_path);
    }
    
    json j;
    file >> j;
    
    Config cfg;
    cfg.ae.sample_rate = j["ae"]["sample_rate"];
    cfg.ae.base_chunk_size = j["ae"]["base_chunk_size"];
    cfg.ttl.chunk_compress_factor = j["ttl"]["chunk_compress_factor"];
    cfg.ttl.latent_dim = j["ttl"]["latent_dim"];
    
    return cfg;
}

std::unique_ptr<UnicodeProcessor> loadTextProcessor(const std::string& onnx_dir) {
    std::string unicode_indexer_path = onnx_dir + "/unicode_indexer.json";
    return std::make_unique<UnicodeProcessor>(unicode_indexer_path);
}

// ============================================================================
// Voice style loading
// ============================================================================

Style loadVoiceStyle(const std::vector<std::string>& voice_style_paths, bool verbose) {
    int bsz = voice_style_paths.size();
    
    // Read first file to get dimensions
    std::ifstream first_file(voice_style_paths[0]);
    if (!first_file.is_open()) {
        throw std::runtime_error("Failed to open voice style file: " + voice_style_paths[0]);
    }
    json first_json;
    first_file >> first_json;
    
    auto ttl_dims = first_json["style_ttl"]["dims"].get<std::vector<int64_t>>();
    auto dp_dims = first_json["style_dp"]["dims"].get<std::vector<int64_t>>();
    
    int64_t ttl_dim1 = ttl_dims[1];
    int64_t ttl_dim2 = ttl_dims[2];
    int64_t dp_dim1 = dp_dims[1];
    int64_t dp_dim2 = dp_dims[2];
    
    // Pre-allocate arrays with full batch size
    size_t ttl_size = bsz * ttl_dim1 * ttl_dim2;
    size_t dp_size = bsz * dp_dim1 * dp_dim2;
    std::vector<float> ttl_flat(ttl_size);
    std::vector<float> dp_flat(dp_size);
    
    // Fill in the data
    for (int i = 0; i < bsz; i++) {
        std::ifstream file(voice_style_paths[i]);
        if (!file.is_open()) {
            throw std::runtime_error("Failed to open voice style file: " + voice_style_paths[i]);
        }
        
        json j;
        file >> j;
        
        // Flatten data
        auto ttl_data_nested = j["style_ttl"]["data"].get<std::vector<std::vector<std::vector<float>>>>();
        std::vector<float> ttl_data;
        for (const auto& batch : ttl_data_nested) {
            for (const auto& row : batch) {
                ttl_data.insert(ttl_data.end(), row.begin(), row.end());
            }
        }
        
        auto dp_data_nested = j["style_dp"]["data"].get<std::vector<std::vector<std::vector<float>>>>();
        std::vector<float> dp_data;
        for (const auto& batch : dp_data_nested) {
            for (const auto& row : batch) {
                dp_data.insert(dp_data.end(), row.begin(), row.end());
            }
        }
        
        // Copy to pre-allocated array
        size_t ttl_offset = i * ttl_dim1 * ttl_dim2;
        std::copy(ttl_data.begin(), ttl_data.end(), ttl_flat.begin() + ttl_offset);
        
        size_t dp_offset = i * dp_dim1 * dp_dim2;
        std::copy(dp_data.begin(), dp_data.end(), dp_flat.begin() + dp_offset);
    }
    
    std::vector<int64_t> ttl_shape = {bsz, ttl_dim1, ttl_dim2};
    std::vector<int64_t> dp_shape = {bsz, dp_dim1, dp_dim2};
    
    if (verbose) {
        std::cerr << "Loaded " << bsz << " voice styles" << std::endl;
    }
    
    return Style(ttl_flat, ttl_shape, dp_flat, dp_shape);
}

// ============================================================================
// TextToSpeech loading
// ============================================================================


// ============================================================================
// TextToSpeech loading (OPTIMIZED FOR TERMUX/ANDROID)
// ============================================================================

std::unique_ptr<TextToSpeech> loadTextToSpeech(
    Ort::Env& env,
    const std::string& onnx_dir,
    bool use_gpu
) {
    Ort::SessionOptions opts;
    
    // --- ANDROID/TERMUX OPTIMIZATIONS ---
    // Default (0) uses all cores, which hits the slow "efficiency" cores on phones.
    // Setting this to 2 or 4 forces execution on the "Performance" cores.
    opts.SetIntraOpNumThreads(5); 
    
    // Enable full graph fusion (merges multiple math ops into one)
    opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    // ------------------------------------

    if (use_gpu) {
        throw std::runtime_error("GPU mode is not supported yet");
    } else {
        std::cerr << "Using CPU for inference [Optimized: Threads=5]" << std::endl;
    }
    
    auto cfgs = loadCfgs(onnx_dir);
    // Pass the optimized 'opts' to all models
    auto models = loadOnnxAll(env, onnx_dir, opts);
    auto text_processor = loadTextProcessor(onnx_dir);
    
    // Transfer ownership to TextToSpeech (use raw pointers internally)
    auto tts = std::make_unique<TextToSpeech>(
        cfgs,
        text_processor.get(),
        models.dp.get(),
        models.text_enc.get(),
        models.vector_est.get(),
        models.vocoder.get()
    );
    
    // Keep the models and processor alive by storing them
    static OnnxModels static_models;
    static std::unique_ptr<UnicodeProcessor> static_text_processor;
    static_models = std::move(models);
    static_text_processor = std::move(text_processor);
    
    return tts;
}

// ============================================================================
// WAV file writing
// ============================================================================

void writeWavFile(
    const std::string& filename,
    const std::vector<float>& audio_data,
    int sample_rate
) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open file for writing: " + filename);
    }
    
    int num_channels = 1;
    int bits_per_sample = 16;
    int byte_rate = sample_rate * num_channels * bits_per_sample / 8;
    int block_align = num_channels * bits_per_sample / 8;
    int data_size = audio_data.size() * bits_per_sample / 8;
    
    // RIFF header
    file.write("RIFF", 4);
    int32_t chunk_size = 36 + data_size;
    file.write(reinterpret_cast<char*>(&chunk_size), 4);
    file.write("WAVE", 4);
    
    // fmt chunk
    file.write("fmt ", 4);
    int32_t fmt_chunk_size = 16;
    file.write(reinterpret_cast<char*>(&fmt_chunk_size), 4);
    int16_t audio_format = 1; // PCM
    file.write(reinterpret_cast<char*>(&audio_format), 2);
    int16_t num_channels_16 = num_channels;
    file.write(reinterpret_cast<char*>(&num_channels_16), 2);
    file.write(reinterpret_cast<char*>(&sample_rate), 4);
    file.write(reinterpret_cast<char*>(&byte_rate), 4);
    int16_t block_align_16 = block_align;
    file.write(reinterpret_cast<char*>(&block_align_16), 2);
    int16_t bits_per_sample_16 = bits_per_sample;
    file.write(reinterpret_cast<char*>(&bits_per_sample_16), 2);
    
    // data chunk
    file.write("data", 4);
    file.write(reinterpret_cast<char*>(&data_size), 4);
    
    // Write audio data
    for (float sample : audio_data) {
        float clamped = std::max(-1.0f, std::min(1.0f, sample));
        int16_t int_sample = static_cast<int16_t>(clamped * 32767);
        file.write(reinterpret_cast<char*>(&int_sample), 2);
    }
}

// ============================================================================
// Tensor conversion utilities
// ============================================================================

Ort::Value arrayToTensor(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::vector<std::vector<float>>>& array,
    const std::vector<int64_t>& dims
) {
    // Flatten the array
    std::vector<float> flat;
    for (const auto& batch : array) {
        for (const auto& row : batch) {
            for (float val : row) {
                flat.push_back(val);
            }
        }
    }
    
    // Store in global buffer to keep data alive
    g_tensor_buffers_float.push_back(std::move(flat));
    auto& buffer = g_tensor_buffers_float.back();
    
    return Ort::Value::CreateTensor<float>(
        memory_info,
        buffer.data(),
        buffer.size(),
        dims.data(),
        dims.size()
    );
}

Ort::Value intArrayToTensor(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::vector<int64_t>>& array,
    const std::vector<int64_t>& dims
) {
    // Flatten the array
    std::vector<int64_t> flat;
    for (const auto& row : array) {
        for (int64_t val : row) {
            flat.push_back(val);
        }
    }
    
    // Store in global buffer to keep data alive
    g_tensor_buffers_int64.push_back(std::move(flat));
    auto& buffer = g_tensor_buffers_int64.back();
    
    return Ort::Value::CreateTensor<int64_t>(
        memory_info,
        buffer.data(),
        buffer.size(),
        dims.data(),
        dims.size()
    );
}

// ============================================================================
// JSON loading helpers
// ============================================================================

std::vector<int64_t> loadJsonInt64(const std::string& file_path) {
    std::ifstream file(file_path);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open file: " + file_path);
    }
    
    json j;
    file >> j;
    
    return j.get<std::vector<int64_t>>();
}

// ============================================================================
// Sanitize filename
// ============================================================================

std::string sanitizeFilename(const std::string& text, int max_len) {
    std::string result;
    int count = 0;
    for (char c : text) {
        if (count >= max_len) break;
        if (std::isalnum(static_cast<unsigned char>(c))) {
            result += c;
        } else {
            result += '_';
        }
        count++;
    }
    return result;
}

// ============================================================================
// Chunk text
// ============================================================================

std::vector<std::string> chunkText(const std::string& text, int max_len) {
    std::vector<std::string> chunks;
    
    // Split by paragraph (two or more newlines)
    std::regex paragraph_regex(R"(\n\s*\n+)");
    std::sregex_token_iterator iter(text.begin(), text.end(), paragraph_regex, -1);
    std::sregex_token_iterator end;
    
    std::vector<std::string> paragraphs;
    for (; iter != end; ++iter) {
        std::string para = trim(*iter);
        if (!para.empty()) {
            paragraphs.push_back(para);
        }
    }
    
    // Split by sentence boundaries, excluding abbreviations
    // This is a simplified version - C++ negative lookbehind is more complex
    std::regex sentence_regex(R"([.!?]\s+)");
    
    for (const auto& paragraph : paragraphs) {
        std::sregex_token_iterator sent_iter(paragraph.begin(), paragraph.end(), sentence_regex, -1);
        std::sregex_token_iterator sent_end;
        
        std::vector<std::string> sentences;
        std::string current = "";
        
        for (; sent_iter != sent_end; ++sent_iter) {
            std::string sentence = *sent_iter;
            if (!sentence.empty()) {
                // Add back the punctuation
                if (sent_iter != sent_end) {
                    std::smatch match;
                    if (std::regex_search(sent_iter->first, paragraph.end(), match, sentence_regex)) {
                        sentence += match.str();
                    }
                }
                sentences.push_back(sentence);
            }
        }
        
        // Combine sentences into chunks
        std::string current_chunk = "";
        
        for (const auto& sentence : sentences) {
            if (static_cast<int>(current_chunk.length() + sentence.length() + 1) <= max_len) {
                if (!current_chunk.empty()) {
                    current_chunk += " ";
                }
                current_chunk += sentence;
            } else {
                if (!current_chunk.empty()) {
                    chunks.push_back(trim(current_chunk));
                }
                current_chunk = sentence;
            }
        }
        
        if (!current_chunk.empty()) {
            chunks.push_back(trim(current_chunk));
        }
    }
    
    // If no chunks were created, return the original text
    if (chunks.empty()) {
        chunks.push_back(trim(text));
    }
    
    return chunks;
}
