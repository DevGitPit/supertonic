package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.TextNormalizer

import android.os.Environment
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackActivity : AppCompatActivity(), PlaybackService.PlaybackListener {

    private lateinit var sentencesList: RecyclerView
    private lateinit var playStopButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar
    
    // ... (existing members)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        sentencesList = findViewById(R.id.sentencesList)
        playStopButton = findViewById(R.id.playStopButton)
        exportButton = findViewById(R.id.exportButton)
        progressBar = findViewById(R.id.progressBar)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        // ... (rest of onCreate)

        playStopButton.setOnClickListener {
            // ... (existing logic)
        }

        exportButton.setOnClickListener {
            val textToExport = intent.getStringExtra(EXTRA_TEXT) ?: return@setOnClickListener
            val voicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: return@setOnClickListener
            val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
            val steps = intent.getIntExtra(EXTRA_STEPS, 5)
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "Supertonic_TTS_$timestamp.wav"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, filename)
            
            Toast.makeText(this, "Exporting to Downloads...", Toast.LENGTH_SHORT).show()
            exportButton.isEnabled = false
            
            playbackService?.exportAudio(textToExport, voicePath, speed, steps, file) { success ->
                runOnUiThread {
                    exportButton.isEnabled = true
                    if (success) {
                        Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Export Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val intent = Intent(this, PlaybackService::class.java)
        // ...
    }
    // ...


    private fun startPlaybackFromIntent() {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val voicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: return
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val steps = intent.getIntExtra(EXTRA_STEPS, 5)
        
        playbackService?.synthesizeAndPlay(text, voicePath, speed, steps)
    }

    private fun playFromIndex(index: Int) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val voicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: return
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val steps = intent.getIntExtra(EXTRA_STEPS, 5)
        
        playbackService?.synthesizeAndPlay(text, voicePath, speed, steps, index)
    }

    override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
        runOnUiThread {
            if (isPlaying || isSynthesizing) {
                playStopButton.text = "Stop"
            } else {
                playStopButton.text = "Play"
            }
        }
    }

    override fun onProgress(current: Int, total: Int) {
        runOnUiThread {
            currentSentenceIndex = current
            adapter.setCurrentIndex(current)
            progressBar.max = total
            progressBar.progress = current
            sentencesList.smoothScrollToPosition(current)
        }
    }

    override fun onPlaybackStopped() {
        runOnUiThread {
            playStopButton.text = "Play"
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
            
            if (position == currentIndex) {
                holder.textView.setBackgroundColor(0xFFE0E0E0.toInt()) // Light Gray Highlight
                holder.textView.setTextColor(0xFF6200EE.toInt()) // Purple Text
            } else {
                holder.textView.background = null
                holder.textView.setTextColor(0xFF000000.toInt())
            }
        }

        override fun getItemCount() = sentences.size
    }
}
