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

class PlaybackService : Service(), SupertonicTTS.ProgressListener {

    // ... (existing members)

    fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputFile: File, onComplete: (Boolean) -> Unit) {
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
                try {
                    val sentences = textNormalizer.splitIntoSentences(text)
                    val outputStream = ByteArrayOutputStream()
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
                            outputStream.write(audioData)
                        } else {
                            Log.w(TAG, "Failed to export sentence: $sentence")
                        }
                    }
                    
                    if (success && outputStream.size() > 0) {
                        WavUtils.saveWav(outputFile, outputStream.toByteArray(), SupertonicTTS.getAudioSampleRate())
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            onComplete(true)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            onComplete(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Export failed", e)
                    withContext(Dispatchers.Main) {
                        stopForeground(true)
                        onComplete(false)
                    }
                }
            }
            // keep reference if we want to cancel export? 
            // For now, simple export.
        }
    }

    // ... (rest of the class)

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isSynthesizing = false
    private var sampleRate = 24000
    private val textNormalizer = TextNormalizer()
    private var totalFramesWritten = 0L
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "supertonic_playback"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
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
    }

    fun initializeEngine(modelPath: String, libPath: String): Boolean {
        return SupertonicTTS.initialize(modelPath, libPath)
    }
    
    fun getSoC(): Int {
        return SupertonicTTS.getSoC()
    }

    private var synthesisJob: Job? = null

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        Log.i(TAG, "Request synthesis: speed=$speed, start=$startIndex")
        
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
            
            wakeLock?.acquire(10 * 60 * 1000L)
            
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
                        listener?.onProgress(index, totalSentences)
                    }

                    val normalizedText = textNormalizer.normalize(sentence)
                    
                    val estimatedDuration = normalizedText.length / 15.0f
                    val audioData = SupertonicTTS.generateAudio(normalizedText, stylePath, speed, estimatedDuration, steps)
                    
                    if (audioData != null && audioData.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (!isPlaying && isSynthesizing) {
                                Log.i(TAG, "First audio chunk received, starting playback.")
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
                        listener?.onProgress(totalSentences, totalSentences)
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

    private fun initAudioTrack(rate: Int) {
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

    override fun onProgress(current: Int, total: Int) {
    }

    override fun onAudioChunk(data: ByteArray) {
        val track = audioTrack ?: return
        // Write data even if not playing yet; it will buffer in the AudioTrack
        val written = track.write(data, 0, data.size)
        if (written > 0) {
            totalFramesWritten += written / 2
        }
    }

    private var listener: PlaybackListener? = null

    interface PlaybackListener {
        fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean)
        fun onProgress(current: Int, total: Int)
        fun onPlaybackStopped()
    }

    fun setListener(listener: PlaybackListener?) {
        this.listener = listener
        listener?.onStateChanged(isPlaying, audioTrack != null, isSynthesizing)
    }

    fun seekRelative(seconds: Int) {
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }
    
    fun stop() {
        cancelSynthesis()
        stopPlayback()
    }

    private fun play() {
        audioTrack?.play()
        isPlaying = true
        notifyListenerState(true)
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForegroundService("Playing Audio", true)
    }

    private fun pause() {
        audioTrack?.pause()
        isPlaying = false
        notifyListenerState(false)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification("Paused", true)
    }

    private fun stopPlayback(removeNotification: Boolean = true) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { }
        
        audioTrack = null
        isPlaying = false
        notifyListenerState(false)
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (removeNotification) {
            listener?.onPlaybackStopped()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(true)
        }
    }
    
    private fun notifyListenerState(playing: Boolean) {
        listener?.onStateChanged(playing, audioTrack != null, isSynthesizing)
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

    override fun onDestroy() {
        super.onDestroy()
        SupertonicTTS.release()
        mediaSession.release()
        audioTrack?.release()
        serviceScope.cancel()
    }
}