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

class PlaybackActivity : AppCompatActivity(), PlaybackService.PlaybackListener {

    private lateinit var sentencesList: RecyclerView
    private lateinit var playStopButton: Button
    private lateinit var progressBar: ProgressBar
    
    private var playbackService: PlaybackService? = null
    private var isBound = false
    private var sentences = listOf<String>()
    private var currentSentenceIndex = -1
    private val textNormalizer = TextNormalizer()
    private lateinit var adapter: SentenceAdapter

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
            
            if (playbackService?.isServiceActive() != true) {
                startPlaybackFromIntent()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        sentencesList = findViewById(R.id.sentencesList)
        playStopButton = findViewById(R.id.playStopButton)
        progressBar = findViewById(R.id.progressBar)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        sentences = textNormalizer.splitIntoSentences(text)
        
        adapter = SentenceAdapter(sentences) { index ->
            playFromIndex(index)
        }
        sentencesList.layoutManager = LinearLayoutManager(this)
        sentencesList.adapter = adapter

        playStopButton.setOnClickListener {
            if (playStopButton.text == "Stop") {
                playbackService?.stop()
            } else {
                playFromIndex(if (currentSentenceIndex >= 0) currentSentenceIndex else 0)
            }
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

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
