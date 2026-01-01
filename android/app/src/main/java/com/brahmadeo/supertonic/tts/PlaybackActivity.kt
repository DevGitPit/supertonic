package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.PorterDuff
import android.content.res.Configuration
import android.os.RemoteException

class PlaybackActivity : AppCompatActivity() {

    private lateinit var sentencesList: RecyclerView
    private lateinit var playStopButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var birdImage: ImageView

    private var playbackService: IPlaybackService? = null
    private var isBound = false
    private lateinit var adapter: SentenceAdapter
    private var currentSentenceIndex = -1

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_VOICE_PATH = "extra_voice_path"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_STEPS = "extra_steps"
    }

    private val playbackListenerStub = object : IPlaybackListener.Stub() {
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

        override fun onExportComplete(success: Boolean, path: String) {
            runOnUiThread {
                exportButton.isEnabled = true
                if (success) {
                    Toast.makeText(this@PlaybackActivity, "Saved to $path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@PlaybackActivity, "Export Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            try {
                playbackService?.setListener(playbackListenerStub)
                isBound = true
                
                if (playbackService?.isServiceActive == true) {
                    // Already playing
                } else {
                    // Check crash recovery
                    val prefs = getSharedPreferences("SupertonicResume", Context.MODE_PRIVATE)
                    val savedText = prefs.getString("last_text", "")
                    val intentText = intent.getStringExtra(EXTRA_TEXT)
                    
                    if (savedText == intentText) {
                        val lastIndex = prefs.getInt("last_index", 0)
                        if (lastIndex > 0) {
                            currentSentenceIndex = lastIndex
                            adapter.setCurrentIndex(lastIndex)
                            sentencesList.scrollToPosition(lastIndex)
                        }
                    } else {
                        startPlaybackFromIntent()
                    }
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
            Toast.makeText(this@PlaybackActivity, "TTS Service restarted. Press Play to resume.", Toast.LENGTH_LONG).show()
            playStopButton.text = "Play"
            
            // Restore last state from prefs in case we crashed
            val prefs = getSharedPreferences("SupertonicResume", Context.MODE_PRIVATE)
            val lastIndex = prefs.getInt("last_index", 0)
            if (lastIndex > 0) {
                currentSentenceIndex = lastIndex
                adapter.setCurrentIndex(lastIndex)
                sentencesList.scrollToPosition(lastIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        sentencesList = findViewById(R.id.sentencesList)
        playStopButton = findViewById(R.id.playStopButton)
        exportButton = findViewById(R.id.exportButton)
        progressBar = findViewById(R.id.progressBar)
        birdImage = findViewById(R.id.birdImage)

        setupBirdTheming()

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        
        val normalizer = TextNormalizer()
        val sentences = normalizer.splitIntoSentences(text)
        
        adapter = SentenceAdapter(sentences) { index ->
            playFromIndex(index)
        }
        sentencesList.layoutManager = LinearLayoutManager(this)
        sentencesList.adapter = adapter

        playStopButton.setOnClickListener {
            try {
                if (playbackService?.isServiceActive == true) {
                    playbackService?.stop()
                } else {
                    if (currentSentenceIndex >= adapter.itemCount) {
                        currentSentenceIndex = 0
                    }
                    if (currentSentenceIndex >= 0) {
                        playFromIndex(currentSentenceIndex)
                    } else {
                        startPlaybackFromIntent()
                    }
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
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
            
            try {
                playbackService?.exportAudio(textToExport, voicePath, speed, steps, file.absolutePath)
            } catch (e: RemoteException) {
                e.printStackTrace()
                exportButton.isEnabled = true
                Toast.makeText(this, "Export Failed (Remote Error)", Toast.LENGTH_SHORT).show()
            }
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val voicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: return
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val steps = intent.getIntExtra(EXTRA_STEPS, 5)
        
        try {
            playbackService?.synthesizeAndPlay(text, voicePath, speed, steps, 0)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun playFromIndex(index: Int) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val voicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: return
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val steps = intent.getIntExtra(EXTRA_STEPS, 5)
        
        try {
            playbackService?.synthesizeAndPlay(text, voicePath, speed, steps, index)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                playbackService?.setListener(null)
            } catch (e: RemoteException) {
                // Service might be dead
            }
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