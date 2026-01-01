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

class PlaybackService : Service(), SupertonicTTS.ProgressListener, AudioManager.OnAudioFocusChangeListener {

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
    
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

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
            SupertonicTTS.release()
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
            SupertonicTTS.setCancelled(false) 
            
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService("Synthesizing...", false)
            notifyListenerState(false)
            
            wakeLock?.acquire(10 * 60 * 1000L)
            
            initAudioTrack(SupertonicTTS.getAudioSampleRate())
            
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio Focus denied")
            }

            synthesisJob = launch(Dispatchers.IO) {
                val sentences = textNormalizer.splitIntoSentences(text)
                val totalSentences = sentences.size

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
                                audioTrack?.play()
                                isPlaying = true
                                notifyListenerState(true)
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                startForegroundService("Playing Audio", true)
                            }
                        }
                    }
                    
                    if (SupertonicTTS.isCancelled()) break
                }
                
                withContext(Dispatchers.Main) {
                    if (isSynthesizing && isActive) {
                        isSynthesizing = false
                        listener?.onProgress(totalSentences, totalSentences)
                        notifyListenerState(true)
                        
                        if (!isPlaying) {
                            stopPlayback()
                        }
                    }
                }

                while (isActive && isPlaying) {
                    val track = audioTrack
                    if (track == null || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= totalFramesWritten && totalFramesWritten > 0) {
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
        val bufferSize = Math.max(minBufferSize, 32768 * 8)

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
        
        isPlaying = false
    }

    override fun onProgress(current: Int, total: Int) {}

    override fun onAudioChunk(data: ByteArray) {
        val track = audioTrack ?: return
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

    fun play() {
        if (!isPlaying && audioTrack != null && requestAudioFocus()) {
            audioTrack?.play()
            isPlaying = true
            notifyListenerState(true)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundService("Playing Audio", true)
        }
    }

    fun pause() {
        if (isPlaying && audioTrack != null) {
            audioTrack?.pause()
            isPlaying = false
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
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying) {
                    audioTrack?.setVolume(0.2f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (audioTrack != null) {
                    audioTrack?.setVolume(1.0f)
                    if (!isPlaying) play()
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
        SupertonicTTS.release()
        mediaSession.release()
        audioTrack?.release()
        serviceScope.cancel()
        abandonAudioFocus()
    }
}