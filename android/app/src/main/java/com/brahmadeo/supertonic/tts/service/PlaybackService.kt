package com.brahmadeo.supertonic.tts.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.brahmadeo.supertonic.tts.MainActivity
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import kotlinx.coroutines.*
import java.util.regex.Pattern

import com.brahmadeo.supertonic.tts.utils.WavUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class PlaybackService : Service(), SupertonicTTS.ProgressListener {

    private val binder = object : IPlaybackService.Stub() {
        override fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            serviceScope.launch {
                this@PlaybackService.synthesizeAndPlay(text, stylePath, speed, steps, startIndex)
            }
        }

        override fun stop() {
            serviceScope.launch {
                this@PlaybackService.stopServicePlayback()
            }
        }

        override fun isServiceActive(): Boolean {
            return this@PlaybackService.isServiceActive()
        }

        override fun setListener(listener: IPlaybackListener?) {
            serviceScope.launch {
                this@PlaybackService.setListener(listener)
            }
        }

        override fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputPath: String) {
            serviceScope.launch {
                this@PlaybackService.exportAudio(text, stylePath, speed, steps, File(outputPath))
            }
        }

        override fun getCurrentIndex(): Int {
            return currentSentenceIndex
        }
    }

    private var listener: IPlaybackListener? = null

    fun setListener(listener: IPlaybackListener?) {
        this.listener = listener
        notifyListenerState(isPlaying)
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    @Volatile private var isSynthesizing = false
    private var sampleRate = 24000
    private val textNormalizer = TextNormalizer()
    private var totalFramesWritten = 0L
    private var synthesisJob: Job? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val audioLock = Any()
    
    // Playback state for resume
    private var currentText: String = ""
    private var currentVoicePath: String = ""
    private var currentSpeed: Float = 1.0f
    private var currentSteps: Int = 5
    private var currentSentenceIndex: Int = 0

    companion object {
        const val CHANNEL_ID = "supertonic_playback"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SupertonicTTS.setProgressListener(this)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock")
        
        mediaSession = MediaSessionCompat(this, "SupertonicMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }

        // Initialize engine in this process
        val modelPath = copyAssets()
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        if (modelPath != null) {
            SupertonicTTS.initialize(modelPath, libPath)
        } else {
            Log.e(TAG, "Failed to copy assets/initialize engine in PlaybackService")
        }
    }

    private fun copyAssets(): String? {
        val filesDir = filesDir
        val targetDir = File(filesDir, "onnx")
        val styleDir = File(filesDir, "voice_styles")
        
        // Version Check
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt("assets_version", 0)
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 1 }

        val needsUpdate = lastVersion < currentVersion
        
        if (!targetDir.exists()) targetDir.mkdirs()
        if (!styleDir.exists()) styleDir.mkdirs()
        
        try {
            val assetManager = assets
            val onnxFiles = assetManager.list("onnx") ?: return null
            for (filename in onnxFiles) {
                val file = File(targetDir, filename)
                if (needsUpdate || !file.exists()) {
                    assetManager.open("onnx/$filename").use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                }
            }
            
            val styleFiles = assetManager.list("voice_styles") ?: emptyArray()
             for (filename in styleFiles) {
                val file = File(styleDir, filename)
                if (needsUpdate || !file.exists()) {
                    assetManager.open("voice_styles/$filename").use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                }
            }
            
            if (needsUpdate) {
                prefs.edit().putInt("assets_version", currentVersion).apply()
            }
            
            return targetDir.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Asset copy failed", e)
            return null
        }
    }

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        Log.i(TAG, "Request synthesis: speed=$speed, start=$startIndex")
        
        // Store params for resume
        this.currentText = text
        this.currentVoicePath = stylePath
        this.currentSpeed = speed
        this.currentSteps = steps
        this.currentSentenceIndex = startIndex
        
        // Launch coordinator on Main to serialize teardown/setup
        serviceScope.launch {
            // 1. Tear down previous
            if (synthesisJob?.isActive == true) {
                Log.i(TAG, "Cancelling active job...")
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
                Log.i(TAG, "Previous job joined.")
            }
            
            // Force stop playback to clear buffers/tracks
            stopPlayback(removeNotification = false)
            
            // 2. Setup new
            isSynthesizing = true
            SupertonicTTS.setCancelled(false) 
            
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService("Synthesizing...", false)
            notifyListenerState(false)
            
            wakeLock?.acquire()
            
            initAudioTrack(SupertonicTTS.getAudioSampleRate())

            // 3. Launch processing
            synthesisJob = launch(Dispatchers.IO) {
                val sentences = textNormalizer.splitIntoSentences(text)
                val totalSentences = sentences.size
                Log.i(TAG, "Processing $totalSentences sentences")

                for (index in startIndex until totalSentences) {
                    val sentence = sentences[index]
                    if (!isActive) break
                    
                    withContext(Dispatchers.Main) {
                        currentSentenceIndex = index // Update progress for resume
                        savePlaybackState(index)
                        try {
                            listener?.onProgress(index, totalSentences)
                        } catch (e: Exception) {
                            listener = null
                        }
                    }

                    val normalizedText = textNormalizer.normalize(sentence)
                    
                    val estimatedDuration = normalizedText.length / 15.0f
                    val audioData = SupertonicTTS.generateAudio(normalizedText, stylePath, speed, estimatedDuration, steps)
                    
                    if (audioData != null && audioData.isNotEmpty()) {
                        // Write audio data directly to track
                        synchronized(audioLock) {
                            val track = audioTrack
                            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                                val written = track.write(audioData, 0, audioData.size)
                                if (written > 0) {
                                    totalFramesWritten += written / 2
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (!isPlaying && isSynthesizing) {
                                Log.i(TAG, "Audio data generated, starting playback.")
                                audioTrack?.play()
                                isPlaying = true
                                notifyListenerState(true)
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                startForegroundService("Playing Audio", true)
                            }
                        }
                    } else {
                        Log.w(TAG, "No audio data for sentence $index")
                    }
                    
                    if (SupertonicTTS.isCancelled()) break
                }
                
                withContext(Dispatchers.Main) {
                    if (isSynthesizing && isActive) {
                        isSynthesizing = false
                        Log.i(TAG, "Synthesis loop complete.")
                        try {
                            listener?.onProgress(totalSentences, totalSentences)
                        } catch (e: Exception) { listener = null }
                        notifyListenerState(true)
                        
                        // If we finished synthesis but never started playing (e.g. all empty)
                        if (!isPlaying) {
                            Log.i(TAG, "Finished synthesis without audio, stopping service.")
                            stopPlayback()
                        }
                    }
                }

                // Wait for playback to finish
                while (isActive && isPlaying) {
                    val track = audioTrack
                    if (track == null || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= totalFramesWritten && totalFramesWritten > 0) {
                        Log.i(TAG, "Playback reached written frame count ($totalFramesWritten).")
                        withContext(Dispatchers.Main) {
                            stopPlayback()
                        }
                        break
                    }
                    delay(200)
                }
            }
        }
    }
    
    fun cancelSynthesis() {
        serviceScope.launch {
            if (isSynthesizing || synthesisJob?.isActive == true) {
                Log.i(TAG, "Cancelling synthesis...")
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
                isSynthesizing = false
                stopPlayback()
            }
        }
    }

    private fun savePlaybackState(index: Int) {
        val prefs = getSharedPreferences("SupertonicResume", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_text", currentText)
            .putString("last_voice", currentVoicePath)
            .putFloat("last_speed", currentSpeed)
            .putInt("last_steps", currentSteps)
            .putInt("last_index", index)
            .apply()
    }

    private fun initAudioTrack(rate: Int) {
        synchronized(audioLock) {
            if (audioTrack != null) {
                try { audioTrack?.release() } catch(e:Exception){}
            }
            sampleRate = rate
            totalFramesWritten = 0L

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = Math.max(minBufferSize, 32768 * 4)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            // Wait for first data chunk to call play()
            isPlaying = false
        }
    }

    override fun onProgress(current: Int, total: Int) {
    }

    override fun onAudioChunk(data: ByteArray) {
        // Ignored; handled in synthesis loop
    }

    fun stopServicePlayback() {
        cancelSynthesis()
        stopPlayback()
    }

    private fun play() {
        if (synthesisJob?.isActive != true && currentText.isNotEmpty()) {
            // Resume synthesis if stopped/paused
            wakeLock?.acquire()
            synthesizeAndPlay(currentText, currentVoicePath, currentSpeed, currentSteps, currentSentenceIndex)
        } else {
            audioTrack?.play()
            isPlaying = true
            notifyListenerState(true)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundService("Playing Audio", true)
        }
    }

    private fun pause() {
        audioTrack?.pause()
        isPlaying = false
        
        // Release resources to save battery
        cancelSynthesis()
        
        notifyListenerState(false)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification("Paused", true)
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun stopPlayback(removeNotification: Boolean = true) {
        synchronized(audioLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) { }
            
            audioTrack = null
        }
        isPlaying = false
        notifyListenerState(false)
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (removeNotification) {
            try {
                listener?.onPlaybackStopped()
            } catch (e: Exception) { listener = null }
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(true)
        }
    }
    
    private fun notifyListenerState(playing: Boolean) {
        try {
            listener?.onStateChanged(playing, audioTrack != null, isSynthesizing)
        } catch (e: Exception) {
            // Listener might be dead
            listener = null
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun startForegroundService(status: String, showControls: Boolean) {
        val notification = buildNotification(status, showControls)
        ServiceCompat.startForeground(
            this, 
            NOTIFICATION_ID, 
            notification, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String, showControls: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status, showControls))
    }

    private fun buildNotification(status: String, showControls: Boolean): android.app.Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Supertonic TTS")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )

        if (showControls) {
            if (isPlaying) {
                builder.addAction(
                    android.R.drawable.ic_media_pause, "Pause",
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PAUSE
                    )
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_play, "Play",
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PLAY
                    )
                )
            }
        } else {
             builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP
                )
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputFile: File) {
        // Use a coordinator launch to ensure clean state
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            stopPlayback()
            
            SupertonicTTS.setCancelled(false) // Reset for export
            
            startForegroundService("Exporting Audio...", false)
            
            // Launch export on IO
            val exportJob = launch(Dispatchers.IO) {
                var randomAccessFile: RandomAccessFile? = null
                try {
                    val sentences = textNormalizer.splitIntoSentences(text)
                    if (outputFile.exists()) outputFile.delete()
                    
                    randomAccessFile = RandomAccessFile(outputFile, "rw")
                    
                    // Write placeholder header (44 bytes)
                    randomAccessFile.write(ByteArray(44))
                    var totalDataSize = 0
                    
                    var success = true
                    
                    for (sentence in sentences) {
                        if (!isActive) {
                            success = false
                            break
                        }
                        val normalizedText = textNormalizer.normalize(sentence)
                        val estimatedDuration = normalizedText.length / 15.0f
                        
                        // Use standard buffer/steps for high quality export
                        val audioData = SupertonicTTS.generateAudio(normalizedText, stylePath, speed, estimatedDuration, steps)
                        
                        if (audioData != null) {
                            randomAccessFile.write(audioData)
                            totalDataSize += audioData.size
                        } else {
                            Log.w(TAG, "Failed to export sentence: $sentence")
                        }
                    }
                    
                    if (success && totalDataSize > 0) {
                        // Write real header
                        randomAccessFile.seek(0)
                        val header = createWavHeader(totalDataSize, SupertonicTTS.getAudioSampleRate())
                        randomAccessFile.write(header)
                        
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            try {
                                listener?.onExportComplete(true, outputFile.absolutePath)
                            } catch(e: Exception) {}
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            try {
                                listener?.onExportComplete(false, outputFile.absolutePath)
                            } catch(e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Export failed", e)
                    withContext(Dispatchers.Main) {
                        stopForeground(true)
                        try {
                            listener?.onExportComplete(false, outputFile.absolutePath)
                        } catch(e: Exception) {}
                    }
                } finally {
                    try { randomAccessFile?.close() } catch (e: Exception) {}
                }
            }
        }
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        
        val header = java.nio.ByteBuffer.allocate(44)
        header.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        
        return header.array()
    }

    override fun onDestroy() {
        super.onDestroy()
        SupertonicTTS.setProgressListener(null)
        SupertonicTTS.release()
        mediaSession.release()
        audioTrack?.release()
        serviceScope.cancel()
    }
}