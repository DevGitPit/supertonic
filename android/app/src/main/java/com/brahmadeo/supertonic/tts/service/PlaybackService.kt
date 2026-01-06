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
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackService : Service(), SupertonicTTS.ProgressListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = object : IPlaybackService.Stub() {
        override fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            this@PlaybackService.synthesizeAndPlay(text, stylePath, speed, steps, startIndex)
        }

        override fun stop() {
            this@PlaybackService.stopServicePlayback()
        }

        override fun isServiceActive(): Boolean {
            return this@PlaybackService.isServiceActive()
        }

        override fun setListener(listener: IPlaybackListener?) {
            this@PlaybackService.setListener(listener)
        }

        override fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputPath: String) {
            this@PlaybackService.exportAudio(text, stylePath, speed, steps, File(outputPath))
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
    private val textNormalizer = TextNormalizer()
    private var activeSessionId: Long = -1
    private var resumeOnFocusGain = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private data class PlaybackItem(val index: Int, val data: ByteArray)

    // State for resume/recovery if needed cross-process
    private var currentSentenceIndex: Int = 0

    companion object {
        const val CHANNEL_ID = "supertonic_playback"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
        const val VOLUME_BOOST_FACTOR = 2.5f
    }

    private fun applyVolumeBoost(pcmData: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return pcmData
        val size = pcmData.size
        val boosted = ByteArray(size)
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBuffer = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = size / 2
        for (i in 0 until count) {
            val sample = inBuffer.get(i)
            var scaled = (sample * gain).toInt()
            if (scaled > 32767) scaled = 32767
            if (scaled < -32768) scaled = -32768
            outBuffer.put(i, scaled.toShort())
        }
        return boosted
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

        // Cross-process engine init if needed
        val modelPath = copyAssets()
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        if (modelPath != null) {
            SupertonicTTS.initialize(modelPath, libPath)
        }
    }

    private fun copyAssets(): String? {
        val filesDir = filesDir
        val targetDir = File(filesDir, "onnx")
        val styleDir = File(filesDir, "voice_styles")
        if (!targetDir.exists()) targetDir.mkdirs()
        if (!styleDir.exists()) styleDir.mkdirs()
        
        try {
            val assetManager = assets
            val onnxFiles = assetManager.list("onnx") ?: return null
            for (filename in onnxFiles) {
                val file = File(targetDir, filename)
                if (!file.exists()) {
                    assetManager.open("onnx/$filename").use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                }
            }
            val styleFiles = assetManager.list("voice_styles") ?: emptyArray()
            for (filename in styleFiles) {
                val file = File(styleDir, filename)
                if (!file.exists()) {
                    assetManager.open("voice_styles/$filename").use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                }
            }
            return targetDir.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Asset copy failed", e)
            return null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_PLAYBACK") {
            stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    private var synthesisJob: Job? = null

    fun synthesizeAndPlay(text: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            
            stopPlayback(removeNotification = false)
            
            isSynthesizing = true
            isPlaying = true
            SupertonicTTS.setCancelled(false) 
            
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            startForegroundService(getString(R.string.notif_synthesizing), false)
            notifyListenerState(false)
...                startForegroundService(getString(R.string.notif_playing), true)
            }
        }
    }

    fun pause() {
        resumeOnFocusGain = false
        if (isPlaying) {
            isPlaying = false
            audioTrack?.pause()
            notifyListenerState(false)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification(getString(R.string.notif_paused), true)
        }
    }
...    fun exportAudio(text: String, stylePath: String, speed: Float, steps: Int, outputFile: File) {
        serviceScope.launch {
            if (synthesisJob?.isActive == true) {
                SupertonicTTS.setCancelled(true)
                synthesisJob?.cancelAndJoin()
            }
            stopPlayback()
            SupertonicTTS.setCancelled(false)
            startForegroundService(getString(R.string.notif_exporting), false)
...    private fun buildNotification(status: String, showControls: Boolean): android.app.Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))

        if (showControls) {
            if (isPlaying) {
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_paused),
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE))
            } else {
                builder.addAction(android.R.drawable.ic_media_play, getString(R.string.yes), // Yes works as Play context here or I should have used action_play
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY))
            }
        } else {
             builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        audioTrack?.release()
        serviceScope.cancel()
        abandonAudioFocus()
    }
}
