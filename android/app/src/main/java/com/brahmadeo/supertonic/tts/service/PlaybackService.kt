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
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import com.brahmadeo.supertonic.tts.utils.WavUtils
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackService : Service(), SupertonicTTS.ProgressListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    @Volatile private var isSynthesizing = false
    private var sampleRate = 24000
    private val textNormalizer = TextNormalizer()
    private var totalFramesWritten = 0L
    private var activeSessionId: Long = -1
    private var resumeOnFocusGain = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private data class PlaybackItem(val index: Int, val data: ByteArray)

    companion object {
        const val CHANNEL_ID = "supertonic_playback"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
        const val VOLUME_BOOST_FACTOR = 2.5f // Boost volume by 2.5x
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

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        SupertonicTTS.addProgressListener(this)
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
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
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_PLAYBACK") {
            stopPlayback()
            // Do NOT release engine here, it kills it for the system service too
        } else if (intent?.action == "RESET_ENGINE") {
            stopPlayback()
            SupertonicTTS.release()
        }
        return START_NOT_STICKY
    }

    fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputFile: File, onComplete: (Boolean) -> Unit) {
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            stopPlayback()
            
            SupertonicTTS.setCancelled(false)
            
            startForegroundService("Exporting Audio...", false)
            
            launch(Dispatchers.IO) {
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
                        
                        // Session ID is incremented inside generateAudio, but we don't care for export
                        val audioData = SupertonicTTS.generateAudio(normalizedText, stylePath, speed, estimatedDuration, steps)
                        
                        if (audioData != null) {
                            val boostedData = applyVolumeBoost(audioData, VOLUME_BOOST_FACTOR)
                            outputStream.write(boostedData)
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
        }
    }

    private var synthesisJob: Job? = null

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        Log.i(TAG, "Request synthesis: speed=$speed, start=$startIndex")
        
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            
            stopPlayback(removeNotification = false)
            
            isSynthesizing = true
            isPlaying = true // Mark as playing initially so we don't block on the pause loop
            SupertonicTTS.setCancelled(false) 
            
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService("Synthesizing...", false)
            notifyListenerState(false)
            
            wakeLock?.acquire(10 * 60 * 1000L)
            
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio Focus denied")
            }

            // Defensive: Ensure engine is ready.
            if (SupertonicTTS.getSoC() == -1) {
                val modelPath = File(filesDir, "onnx").absolutePath
                val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
                SupertonicTTS.initialize(modelPath, libPath)
            }

            synthesisJob = launch(Dispatchers.IO) {
                val sentences = textNormalizer.splitIntoSentences(text)
                val totalSentences = sentences.size
                
                // Pipeline buffer: Keeps 2 sentences ahead
                val channel = kotlinx.coroutines.channels.Channel<PlaybackItem>(2)

                // Producer: Synthesis Loop
                val producer = launch {
                    for (index in startIndex until totalSentences) {
                        if (SupertonicTTS.isCancelled() || !isActive) break

                        // Pause logic for producer
                        while (!isPlaying && isSynthesizing && isActive) {
                            delay(100)
                        }
                        if (SupertonicTTS.isCancelled() || !isActive || !isSynthesizing) break

                        val sentence = sentences[index]
                        val normalizedText = textNormalizer.normalize(sentence)
                        val estimatedDuration = normalizedText.length / 15.0f
                        
                        val audioData = SupertonicTTS.generateAudio(normalizedText, stylePath, speed, estimatedDuration, steps)
                        
                        if (audioData != null && audioData.isNotEmpty()) {
                            val boostedData = applyVolumeBoost(audioData, VOLUME_BOOST_FACTOR)
                            channel.send(PlaybackItem(index, boostedData))
                        }
                    }
                    channel.close()
                }

                // Consumer: Playback Loop
                for (item in channel) {
                    if (SupertonicTTS.isCancelled() || !isActive || !isSynthesizing) break

                    withContext(Dispatchers.Main) {
                        listener?.onProgress(item.index, totalSentences)
                    }

                    playAudioDataBlocking(item.data)
                }
                
                withContext(Dispatchers.Main) {
                    if (isSynthesizing && isActive) {
                        isSynthesizing = false
                        listener?.onProgress(totalSentences, totalSentences)
                        notifyListenerState(true)
                        stopPlayback()
                    }
                }
            }
        }
    }
    
    private suspend fun playAudioDataBlocking(data: ByteArray) {
        if (!currentCoroutineContext().isActive) return
        
        val rate = SupertonicTTS.getAudioSampleRate()
        val minBufferSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Use MODE_STATIC for perfect pre-buffering
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(Math.max(minBufferSize, data.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            
        audioTrack = track
        track.write(data, 0, data.size)
        
        // Wait for play state
        withContext(Dispatchers.Main) {
            // Respect the user's current intent (isPlaying flag)
            if (isPlaying) {
                track.play()
                notifyListenerState(true)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            } else {
                notifyListenerState(false)
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }
        
        // Block until finished or cancelled
        while (currentCoroutineContext().isActive && isSynthesizing) {
            if (!isPlaying) {
                // Handle Pause: Pause track if needed and wait
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                delay(100)
                continue
            } else {
                // Handle Resume: Play track if needed
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED || track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    track.play()
                }
            }

            val head = track.playbackHeadPosition.toLong()
            val total = data.size / 2
            if (head >= total) break
            delay(50)
        }
        
        track.release()
        audioTrack = null
    }

    private fun initAudioTrack(rate: Int) { 
        // Deprecated/Unused in sentence-based MODE_STATIC strategy
    }

    override fun onProgress(sessionId: Long, current: Int, total: Int) {}

    override fun onAudioChunk(sessionId: Long, data: ByteArray) {
        // We write audio in the main synthesis loop to ensure full sentence buffering
        // which prevents robotic/stuttering playback.
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

    fun play() {
        resumeOnFocusGain = false
        if (!isPlaying) {
            if (requestAudioFocus()) {
                isPlaying = true
                audioTrack?.play() // Might be null if generating, loop will pick it up
                notifyListenerState(true)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForegroundService("Playing Audio", true)
            }
        }
    }

    fun pause() {
        resumeOnFocusGain = false
        if (isPlaying) {
            isPlaying = false
            audioTrack?.pause() // Might be null if generating, loop will handle it
            notifyListenerState(false)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification("Paused", true)
        }
    }

    fun stopPlayback(removeNotification: Boolean = true) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { }
        
        audioTrack = null
        isPlaying = false
        resumeOnFocusGain = false
        notifyListenerState(false)
        abandonAudioFocus()
        
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

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
                
            val res = audioManager.requestAudioFocus(focusRequest!!)
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    resumeOnFocusGain = true
                    // We call the internal pausing logic directly to avoid clearing the resumeOnFocusGain flag
                    // which our public pause() method does.
                    isPlaying = false
                    audioTrack?.pause()
                    notifyListenerState(false)
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification("Paused (Interrupted)", true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying) {
                    audioTrack?.setVolume(0.2f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (audioTrack != null) {
                    audioTrack?.setVolume(1.0f)
                    if (resumeOnFocusGain) {
                        play()
                        resumeOnFocusGain = false // Reset after consuming
                    }
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        // SupertonicTTS.release() // Don't release, let the process owner manage it or Reset button
        mediaSession.release()
        audioTrack?.release()
        serviceScope.cancel()
        abandonAudioFocus()
    }
}