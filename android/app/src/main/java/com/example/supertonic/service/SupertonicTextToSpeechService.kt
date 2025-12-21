package com.example.supertonic.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.example.supertonic.SupertonicTTS
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
        // We only support en-US mostly, but we'll accept any English
        return if ("eng".equals(lang, ignoreCase = true) || "en".equals(lang, ignoreCase = true)) {
            if ("USA".equals(country, ignoreCase = true) || "US".equals(country, ignoreCase = true) || country.isNullOrEmpty()) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
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
        
        // Load preferred voice
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val voiceFile = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
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
