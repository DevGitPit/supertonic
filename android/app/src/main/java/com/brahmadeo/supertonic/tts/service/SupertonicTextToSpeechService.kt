package com.brahmadeo.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Job? = null

    companion object {
        const val VOLUME_BOOST_FACTOR = 2.5f
    }

    private fun applyVolumeBoost(pcmData: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return pcmData
        
        val size = pcmData.size
        val boosted = ByteArray(size)
        
        // Wrap input and output for easy short access (16-bit PCM)
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBuffer = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        
        val count = size / 2
        for (i in 0 until count) {
            val sample = inBuffer.get(i)
            // Apply gain and clamp (hard clipping)
            var scaled = (sample * gain).toInt()
            if (scaled > 32767) scaled = 32767
            if (scaled < -32768) scaled = -32768
            outBuffer.put(i, scaled.toShort())
        }
        
        return boosted
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("SupertonicTTS", "Service created")
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        
        // Initialize asynchronously to prevent binding timeouts
        initJob = serviceScope.launch(Dispatchers.IO) {
            val modelPath = copyAssets()
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            
            if (modelPath != null) {
                SupertonicTTS.initialize(modelPath, libPath)
            } else {
                Log.e("SupertonicTTS", "Failed to copy assets in Service onCreate")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        // Accept "eng", "en", "en-us", "en_us"
        return if (language.startsWith("en")) {
            if (country != null) {
                // We mainly claim support for US/GB to satisfy apps looking for major English dialects
                if (country.equals("USA", true) || country.equals("US", true) || 
                    country.equals("GBR", true) || country.equals("GB", true)) {
                    TextToSpeech.LANG_COUNTRY_AVAILABLE
                } else {
                    TextToSpeech.LANG_AVAILABLE
                }
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    private fun isValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (voiceName.startsWith("en-us-supertonic-")) {
            val styleName = voiceName.substringAfter("en-us-supertonic-")
            val file = File(filesDir, "voice_styles/$styleName.json")
            return if (file.exists()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        }
        return TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        
        if (voiceName.startsWith("en-us-supertonic-")) {
            val styleName = voiceName.substringAfter("en-us-supertonic-")
            val file = File(filesDir, "voice_styles/$styleName.json")
            if (file.exists()) {
                return TextToSpeech.SUCCESS
            }
        }
        
        return TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        val voiceName = if (selected.endsWith(".json")) selected.substringBeforeLast(".") else selected
        return "en-us-supertonic-$voiceName"
    }

    override fun onGetVoices(): List<Voice> {
        val voicesList = mutableListOf<Voice>()
        val locale = Locale.US
        
        // Hardcoded list for instant binding response
        val voiceNames = listOf(
            "M1", "M2", "M3", "M4", "M5",
            "F1", "F2", "F3", "F4", "F5"
        )
        
        voiceNames.forEach { name ->
            voicesList.add(
                Voice(
                    "en-us-supertonic-$name",
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    setOf()
                )
            )
        }
        
        return voicesList
    }

    override fun onStop() {
        SupertonicTTS.setCancelled(true)
    }

    private val textNormalizer = com.brahmadeo.supertonic.tts.utils.TextNormalizer()

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // Reset cancellation flag for new request
        SupertonicTTS.setCancelled(false)

        // Block until engine is ready (max 5 seconds to prevent ANR)
        runBlocking {
            withTimeoutOrNull(5000) {
                initJob?.join()
            }
        }

        val rawText = request.charSequenceText?.toString() ?: return
        
        // Speed
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)

        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        
        val localListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
                // Apply volume boost before writing to system callback
                val boostedData = applyVolumeBoost(data, VOLUME_BOOST_FACTOR)
                
                // Write streaming audio to system callback
                var offset = 0
                while (offset < boostedData.size) {
                    val length = Math.min(4096, boostedData.size - offset)
                    callback.audioAvailable(boostedData, offset, length)
                    offset += length
                }
            }
        }
        
        SupertonicTTS.addProgressListener(localListener)
        
        // Load requested voice if possible, else fallback to preference
        val requestedVoice = request.voiceName
        val voiceFile = if (requestedVoice != null && requestedVoice.startsWith("en-us-supertonic-")) {
            requestedVoice.substringAfter("en-us-supertonic-") + ".json"
        } else {
            val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
            prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        }
        val stylePath = File(filesDir, "voice_styles/$voiceFile").absolutePath

        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val steps = prefs.getInt("diffusion_steps", 5)
        
        // Defensive Re-init (double check, though runBlocking above should handle it)
        if (SupertonicTTS.getSoC() == -1) {
             val modelPath = File(filesDir, "onnx").absolutePath
             val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
             SupertonicTTS.initialize(modelPath, libPath)
        }
        
        try {
            // PROCESSING: Split into sentences to prevent timeouts on long text
            val sentences = textNormalizer.splitIntoSentences(rawText)
            var success = true
            
            for (sentence in sentences) {
                // Check cancellation
                if (SupertonicTTS.isCancelled()) {
                    success = false
                    break
                }

                val normalizedText = textNormalizer.normalize(sentence)
                // Use 0 buffer for system TTS to reduce latency
                SupertonicTTS.generateAudio(normalizedText, stylePath, effectiveSpeed, 0.0f, steps)
            }
            
            if (success) {
                callback.done()
            } else {
                callback.error()
            }
        } finally {
            SupertonicTTS.removeProgressListener(localListener)
        }
    }

    private fun copyAssets(): String? {
        val filesDir = filesDir
        val targetDir = File(filesDir, "onnx")
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) return null
        }
        
        // Similar copy logic as MainActivity but simplified/robust
        try {
            val assetManager = assets
            val onnxFiles = assetManager.list("onnx") ?: return null
            for (filename in onnxFiles) {
                val file = File(targetDir, filename)
                if (!file.exists()) {
                    val inFile = assetManager.open("onnx/$filename")
                    val outStream = FileOutputStream(file)
                    inFile.copyTo(outStream)
                    inFile.close()
                    outStream.close()
                }
            }
            
            val styleDir = File(filesDir, "voice_styles")
            if (!styleDir.exists()) styleDir.mkdirs()
            val styleFiles = assetManager.list("voice_styles") ?: emptyArray()
             for (filename in styleFiles) {
                val file = File(styleDir, filename)
                if (!file.exists()) {
                    val inFile = assetManager.open("voice_styles/$filename")
                    val outStream = FileOutputStream(file)
                    inFile.copyTo(outStream)
                    inFile.close()
                    outStream.close()
                }
            }
            return targetDir.absolutePath
        } catch (e: IOException) {
            return null
        }
    }
}
