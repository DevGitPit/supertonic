package com.brahmadeo.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {

    override fun onCreate() {
        super.onCreate()
        Log.i("SupertonicTTS", "Service created")
        
        // Ensure assets are copied
        val modelPath = copyAssets()
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        
        if (modelPath != null) {
            SupertonicTTS.initialize(modelPath, libPath)
        } else {
            Log.e("SupertonicTTS", "Failed to copy assets in Service onCreate")
        }
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
        
        val voiceStylesDir = File(filesDir, "voice_styles")
        val voiceFiles = voiceStylesDir.listFiles { file -> file.extension == "json" }
        
        voiceFiles?.forEach { file ->
            val voiceName = file.nameWithoutExtension
            voicesList.add(
                Voice(
                    "en-us-supertonic-$voiceName",
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    setOf()
                )
            )
        }
        
        // If no files found yet (e.g. during first start), return at least a default
        if (voicesList.isEmpty()) {
            voicesList.add(
                Voice(
                    "en-us-supertonic-M1",
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    setOf()
                )
            )
        }
        
        return voicesList.sortedBy { it.name }
    }

    override fun onStop() {
        SupertonicTTS.setCancelled(true)
    }

    private var silenceTrack: android.media.AudioTrack? = null

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // HACK: Play silence locally to keep app "active" in audio focus
        playSilence()

        val text = request.charSequenceText?.toString() ?: return
        
        // Speed
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)

        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        
        SupertonicTTS.setProgressListener(object : SupertonicTTS.ProgressListener {
            override fun onProgress(current: Int, total: Int) {
                // Ignore chunk progress for system TTS
            }

            override fun onAudioChunk(data: ByteArray) {
                // Write streaming audio to callback
                var offset = 0
                while (offset < data.size) {
                    val length = Math.min(4096, data.size - offset)
                    callback.audioAvailable(data, offset, length)
                    offset += length
                }
            }
        })
        
        // Load requested voice if possible, else fallback to preference
        val requestedVoice = request.voiceName
        val voiceFile = if (requestedVoice != null && requestedVoice.startsWith("en-us-supertonic-")) {
            requestedVoice.substringAfter("en-us-supertonic-") + ".json"
        } else {
            val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
            prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        }
        val stylePath = File(filesDir, "voice_styles/$voiceFile").absolutePath
        
        val audioData = SupertonicTTS.generateAudio(text, stylePath, effectiveSpeed, 0.0f, 5)
        
        SupertonicTTS.setProgressListener(null) // Cleanup
        stopSilence()
        
        if (audioData != null) {
            callback.done()
        } else {
            callback.error()
        }
    }

    private fun playSilence() {
        try {
            val sampleRate = 24000
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            
            silenceTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()

            val silentData = ByteArray(minBufferSize)
            silenceTrack?.write(silentData, 0, silentData.size)
            silenceTrack?.setLoopPoints(0, silentData.size / 2, -1)
            silenceTrack?.play()
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Failed to play silence", e)
        }
    }

    private fun stopSilence() {
        try {
            silenceTrack?.stop()
            silenceTrack?.release()
            silenceTrack = null
        } catch (e: Exception) { }
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
