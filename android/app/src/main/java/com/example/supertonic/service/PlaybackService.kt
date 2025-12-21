package com.example.supertonic.service

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
import com.example.supertonic.MainActivity
import com.example.supertonic.R
import com.example.supertonic.SupertonicTTS
import kotlinx.coroutines.*

class PlaybackService : Service(), SupertonicTTS.ProgressListener {

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isSynthesizing = false
    private var sampleRate = 24000
    // Removed tts instance variable, using Singleton
    
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
        // SupertonicTTS is a singleton, just set listener
        SupertonicTTS.setProgressListener(this)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock")
        
        mediaSession = MediaSessionCompat(this, "SupertonicMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onStop() {
                    stopPlayback()
                }
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

    fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int) {
        Log.i(TAG, "Starting synthesis: speed=$speed, steps=$steps, textLen=${text.length}")
        
        isSynthesizing = true
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForegroundService("Synthesizing...", false)
        notifyListenerState(false)
        
        wakeLock?.acquire(10 * 60 * 1000L)
        playSilence()

        synthesisJob = serviceScope.launch(Dispatchers.IO) {
            val estimatedDuration = text.length / 15.0f
            Log.i(TAG, "Calling native generateAudio...")
            
            val audioData = SupertonicTTS.generateAudio(text, stylePath, speed, estimatedDuration, steps)
            Log.i(TAG, "Native generateAudio returned: ${audioData?.size} bytes")

            withContext(Dispatchers.Main) {
                if (!isActive || !isSynthesizing) {
                    Log.i(TAG, "Synthesis finished but cancelled or obsolete.")
                    return@withContext
                }
                
                isSynthesizing = false
                if (audioData != null && audioData.isNotEmpty()) {
                    playPcmData(audioData, SupertonicTTS.getAudioSampleRate())
                } else {
                    Log.e(TAG, "Synthesis failed or returned empty data")
                    stopPlayback()
                }
            }
        }
    }
    
    fun cancelSynthesis() {
        if (isSynthesizing) {
            Log.i(TAG, "Cancelling synthesis...")
            SupertonicTTS.setCancelled(true)
            synthesisJob?.cancel()
            isSynthesizing = false
            stopPlayback()
        }
    }

    private fun playSilence() {
        try {
            val sampleRate = 24000
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val bufferSize = minBufferSize * 4
            val silentData = ByteArray(bufferSize)
            
            audioTrack?.release()
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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(silentData, 0, silentData.size)
            audioTrack?.setLoopPoints(0, silentData.size / 2, -1)
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start silent playback", e)
        }
    }

    private fun playPcmData(data: ByteArray, rate: Int) {
        stopPlayback(false) // Stop previous but keep foreground
        sampleRate = rate

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
            .setBufferSizeInBytes(Math.max(minBufferSize, data.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        val frameCount = data.size / 2
        audioTrack?.notificationMarkerPosition = frameCount
        audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                stopPlayback()
            }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })

        audioTrack?.write(data, 0, data.size)
        play()
    }

    override fun onProgress(current: Int, total: Int) {
        listener?.onProgress(current, total)
    }

    override fun onAudioChunk(data: ByteArray) {
        // For main app playback, we currently wait for full generation.
        // We could implement streaming playback here too, but that requires
        // changing AudioTrack mode to STREAM and careful buffer management.
        // For now, we ignore chunks and use the final full blob.
    }

    private var listener: PlaybackListener? = null

    interface PlaybackListener {
        fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean)
        fun onProgress(current: Int, total: Int)
        fun onPlaybackStopped()
    }

    fun setListener(listener: PlaybackListener?) {
        this.listener = listener
        // Initial state update
        listener?.onStateChanged(isPlaying, audioTrack != null, isSynthesizing)
    }

    fun seekRelative(seconds: Int) {
        audioTrack?.let { track ->
            val rate = track.sampleRate
            val currentFrame = track.playbackHeadPosition
            val offsetFrames = seconds * rate
            val totalFrames = track.bufferSizeInFrames
            
            var newPosition = currentFrame + offsetFrames
            if (newPosition < 0) newPosition = 0
            if (newPosition > totalFrames) newPosition = totalFrames
            
            track.pause()
            track.playbackHeadPosition = newPosition
            if (isPlaying) track.play()
        }
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }
    
    fun stop() {
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
        audioTrack?.stop()
        audioTrack?.release()
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
        // Use ServiceCompat for safe foreground service start
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
            // Add a dummy action to keep style happy or show "Stop"
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