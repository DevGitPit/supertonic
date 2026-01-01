package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackActivity : AppCompatActivity(), PlaybackService.PlaybackListener {

    private lateinit var sentencesList: RecyclerView
    private lateinit var playStopButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var birdImage: ImageView
    private lateinit var exportOverlay: RelativeLayout
    private lateinit var cancelExportBtn: Button

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private lateinit var adapter: SentenceAdapter
    private var currentSentenceIndex = -1
    
    // State persistence
    private var currentText = ""
    private var currentVoicePath = ""
    private var currentSpeed = 1.0f
    private var currentSteps = 5

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_VOICE_PATH = "extra_voice_path"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_STEPS = "extra_steps"
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            playbackService?.setListener(this@PlaybackActivity)
            isBound = true
            
            if (intent.getBooleanExtra("is_resume", false)) {
                restoreState()
            } else {
                startPlaybackFromIntent()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        sentencesList = findViewById(R.id.sentencesList)
        playStopButton = findViewById(R.id.playStopButton)
        stopButton = findViewById(R.id.stopButton)
        exportButton = findViewById(R.id.exportButton)
        progressBar = findViewById(R.id.progressBar)
        birdImage = findViewById(R.id.birdImage)
        exportOverlay = findViewById(R.id.exportOverlay)
        cancelExportBtn = findViewById(R.id.cancelExportBtn)

        setupBirdTheming()

        currentText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        currentVoicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: ""
        currentSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        currentSteps = intent.getIntExtra(EXTRA_STEPS, 5)
        
        if (intent.getBooleanExtra("is_resume", false) && currentText.isEmpty()) {
             val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
             currentText = prefs.getString("last_text", "") ?: ""
             currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
             currentSpeed = prefs.getFloat("last_speed", 1.0f)
             currentSteps = prefs.getInt("last_steps", 5)
             currentSentenceIndex = prefs.getInt("last_index", 0)
        }

        setupList(currentText)

        playStopButton.setOnClickListener {
            if (playbackService?.isServiceActive() == true) {
                playbackService?.pause()
            } else {
                if (currentSentenceIndex >= 0) {
                    playFromIndex(currentSentenceIndex)
                } else {
                    startPlaybackFromIntent()
                }
            }
        }
        
        stopButton.setOnClickListener {
            playbackService?.stop()
            clearState()
            finish()
        }

        exportButton.setOnClickListener {
            showExportOverlay()
            startExport()
        }
        
        cancelExportBtn.setOnClickListener {
            playbackService?.stop() // Cancels export
            hideExportOverlay()
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupList(text: String) {
        val normalizer = TextNormalizer()
        val sentences = normalizer.splitIntoSentences(text)
        adapter = SentenceAdapter(sentences) { index ->
            playFromIndex(index)
        }
        sentencesList.layoutManager = LinearLayoutManager(this)
        sentencesList.adapter = adapter
        
        if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
            adapter.setCurrentIndex(currentSentenceIndex)
            sentencesList.scrollToPosition(currentSentenceIndex)
        }
    }

    private fun setupBirdTheming() {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val goldColor = ContextCompat.getColor(this, R.color.accent_gold)
        if (isDarkMode) {
            birdImage.setColorFilter(goldColor, PorterDuff.Mode.SCREEN)
        } else {
            birdImage.setColorFilter(goldColor, PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun startPlaybackFromIntent() {
        if (currentText.isEmpty()) return
        saveState()
        playbackService?.synthesizeAndPlay(currentText, currentVoicePath, currentSpeed, currentSteps)
    }

    private fun playFromIndex(index: Int) {
        if (currentText.isEmpty()) return
        saveState()
        playbackService?.synthesizeAndPlay(currentText, currentVoicePath, currentSpeed, currentSteps, index)
    }
    
    private fun saveState() {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putString("last_text", currentText)
            .putString("last_voice_path", currentVoicePath)
            .putFloat("last_speed", currentSpeed)
            .putInt("last_steps", currentSteps)
            .putBoolean("is_playing", true)
            .apply()
    }
    
    private fun updateIndexState(index: Int) {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putInt("last_index", index)
            .apply()
    }
    
    private fun clearState() {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_playing", false)
            .apply()
    }
    
    private fun restoreState() {
        if (playbackService?.isServiceActive() == false) {
             onStateChanged(false, true, false)
        }
    }

    private fun startExport() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Supertonic_TTS_$timestamp.wav"
        
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val appDir = File(musicDir, "Supertonic Audio")
        if (!appDir.exists()) appDir.mkdirs()
        
        val file = File(appDir, filename)
        
        playbackService?.exportAudio(currentText, currentVoicePath, currentSpeed, currentSteps, file) { success ->
            runOnUiThread {
                hideExportOverlay()
                if (success) {
                    Toast.makeText(this, "Saved to Music/Supertonic Audio", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Export Cancelled or Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showExportOverlay() {
        exportOverlay.visibility = View.VISIBLE
    }
    
    private fun hideExportOverlay() {
        exportOverlay.visibility = View.GONE
    }

    override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
        runOnUiThread {
            if (isPlaying || isSynthesizing) {
                playStopButton.text = "Pause"
                stopButton.visibility = View.GONE
                exportButton.visibility = View.GONE
            } else {
                playStopButton.text = "Resume"
                stopButton.visibility = View.VISIBLE
                exportButton.visibility = View.VISIBLE
                exportButton.isEnabled = true
            }
        }
    }

    override fun onProgress(current: Int, total: Int) {
        runOnUiThread {
            currentSentenceIndex = current
            updateIndexState(current)
            adapter.setCurrentIndex(current)
            progressBar.max = total
            progressBar.progress = current
            
            // Smooth centering scroll
            val smoothScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
                override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                    return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
                }
            }
            smoothScroller.targetPosition = current
            sentencesList.layoutManager?.startSmoothScroll(smoothScroller)
        }
    }

    override fun onPlaybackStopped() {
        runOnUiThread {
            playStopButton.text = "Play"
            stopButton.visibility = View.VISIBLE
            exportButton.visibility = View.VISIBLE
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            playbackService?.setListener(null)
            unbindService(connection)
            isBound = false
        }
    }

    inner class SentenceAdapter(private val sentences: List<String>, private val onClick: (Int) -> Unit) : 
        RecyclerView.Adapter<SentenceAdapter.ViewHolder>() {
        
        private var currentIndex = -1

        fun setCurrentIndex(index: Int) {
            if (index == currentIndex) return
            val prev = currentIndex
            currentIndex = index
            notifyItemChanged(prev)
            notifyItemChanged(currentIndex)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.sentenceText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sentence, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = sentences[position]
            holder.textView.setOnClickListener { onClick(position) }
            
            val context = holder.itemView.context
            if (position == currentIndex) {
                holder.textView.setBackgroundColor(0x33C5A059.toInt())
                holder.textView.setTextColor(ContextCompat.getColor(context, R.color.accent_gold))
                holder.textView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                holder.textView.background = null
                holder.textView.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                holder.textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        override fun getItemCount() = sentences.size
    }
}