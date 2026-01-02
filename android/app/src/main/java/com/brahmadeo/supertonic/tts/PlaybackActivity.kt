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
import android.util.TypedValue
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
    private lateinit var playStopButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: com.google.android.material.progressindicator.LinearProgressIndicator
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
    
    // UI State tracking
    private var isPlaying = false
    private var isServiceActive = false

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
        exportOverlay = findViewById(R.id.exportOverlay)
        cancelExportBtn = findViewById(R.id.cancelExportBtn)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.playbackToolbar)
        toolbar.setNavigationOnClickListener { finish() }

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
            if (isPlaying) {
                playbackService?.pause()
            } else if (isServiceActive) {
                playbackService?.play()
            } else {
                if (currentSentenceIndex >= 0) {
                    playFromIndex(currentSentenceIndex)
                } else {
                    startPlaybackFromIntent()
                }
            }
        }
        
        stopButton.setOnClickListener {
            playbackService?.stopPlayback()
            clearState()
            finish()
        }

        exportButton.setOnClickListener {
            showExportOverlay()
            startExport()
        }
        
        cancelExportBtn.setOnClickListener {
            playbackService?.stopPlayback() // Cancels export
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
            this.isPlaying = isPlaying
            this.isServiceActive = isPlaying || isSynthesizing
            
            if (isPlaying) {
                playStopButton.setImageResource(android.R.drawable.ic_media_pause)
                stopButton.visibility = View.GONE
                exportButton.visibility = View.GONE
            } else if (isServiceActive) {
                // Paused state
                playStopButton.setImageResource(android.R.drawable.ic_media_play)
                stopButton.visibility = View.VISIBLE
                exportButton.visibility = View.VISIBLE
                exportButton.isEnabled = true
            } else {
                // Stopped state
                playStopButton.setImageResource(android.R.drawable.ic_media_play)
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
            
            // Simple scroll to keep active sentence visible
            sentencesList.smoothScrollToPosition(current)
        }
    }

    override fun onPlaybackStopped() {
        runOnUiThread {
            isPlaying = false
            isServiceActive = false
            playStopButton.setImageResource(android.R.drawable.ic_media_play)
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
            val cardView: com.google.android.material.card.MaterialCardView = view as com.google.android.material.card.MaterialCardView
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
                val bgColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.LTGRAY)
                val textColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, android.graphics.Color.BLACK)
                
                holder.cardView.setCardBackgroundColor(bgColor)
                holder.cardView.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics).toInt()
                holder.cardView.strokeColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, bgColor)
                
                holder.textView.setTextColor(textColor)
                holder.textView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                val surfaceColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
                val textColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
                
                holder.cardView.setCardBackgroundColor(surfaceColor)
                holder.cardView.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics).toInt()
                holder.cardView.strokeColor = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.TRANSPARENT)
                
                holder.textView.setTextColor(textColor)
                holder.textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        override fun getItemCount() = sentences.size
    }
}