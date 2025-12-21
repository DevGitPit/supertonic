package com.example.supertonic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.supertonic.service.PlaybackService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity(), PlaybackService.PlaybackListener {

    private lateinit var statusText: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var qualitySeekBar: SeekBar
    private lateinit var qualityValue: TextView
    private lateinit var inputText: EditText
    private lateinit var synthButton: Button
    
    // Layouts
    private lateinit var inputContainer: LinearLayout
    private lateinit var playbackControls: LinearLayout
    
    // Playback Buttons
    private lateinit var btnRewind: Button
    private lateinit var btnPlayPause: Button
    private lateinit var btnForward: Button
    private lateinit var btnStop: Button
    private lateinit var btnCancel: Button
    
    // Progress
    private lateinit var nowPlayingText: TextView
    private lateinit var synthesisProgressBar: ProgressBar
    private lateinit var synthesisProgressText: TextView

    private var currentSteps = 5

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var selectedVoiceFile = "M1.json"
    private var currentSpeed = 1.05f

    private var playbackService: PlaybackService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            playbackService?.setListener(this@MainActivity)
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean -> }

    private val voices = mapOf(
        "M1 - Lively, Upbeat" to "M1.json",
        "M2 - Deep, Calm" to "M2.json",
        "M3 - Polished, Authoritative" to "M3.json",
        "M4 - Soft, Youthful" to "M4.json",
        "M5 - Warm, Soothing" to "M5.json",
        "F1 - Calm, Professional" to "F1.json",
        "F2 - Bright, Playful" to "F2.json",
        "F3 - Broadcast, Clear" to "F3.json",
        "F4 - Crisp, Confident" to "F4.json",
        "F5 - Kind, Gentle" to "F5.json"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        statusText = findViewById(R.id.statusText)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedValue = findViewById(R.id.speedValue)
        qualitySeekBar = findViewById(R.id.qualitySeekBar)
        qualityValue = findViewById(R.id.qualityValue)
        inputText = findViewById(R.id.inputText)
        synthButton = findViewById(R.id.synthButton)
        
        inputContainer = findViewById(R.id.inputContainer)
        playbackControls = findViewById(R.id.playbackControls)
        
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnStop = findViewById(R.id.btnStop)
        btnCancel = findViewById(R.id.btnCancel)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        synthesisProgressBar = findViewById(R.id.synthesisProgressBar)
        synthesisProgressText = findViewById(R.id.synthesisProgressText)

        val placeholderText = "Hello world, this is Supertonic TTS on Android. Select a voice and speed above!"
        inputText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && inputText.text.toString() == placeholderText) {
                inputText.setText("")
            }
        }

        setupVoiceSpinner()
        setupSpeedControl()
        setupQualityControl()
        setupPlaybackControls()
        checkNotificationPermission()

        synthButton.isEnabled = false
        statusText.text = "Initializing..."

        Intent(this, PlaybackService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        // Wait for service binding
        scope.launch {
            while (!isBound || playbackService == null) {
                delay(100)
            }
            
            withContext(Dispatchers.IO) {
                val modelPath = copyAssets()
                val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
                if (modelPath != null) {
                    if (playbackService?.initializeEngine(modelPath, libPath) == true) {
                        val socClass = playbackService?.getSoC() ?: -1
                        val socName = when(socClass) {
                            3 -> "Flagship"
                            2 -> "High-End"
                            1 -> "Mid-Range"
                            0 -> "Low-End"
                            else -> "Unknown"
                        }
                        
                        withContext(Dispatchers.Main) {
                            statusText.text = "Initialized (SoC: $socName)"
                            synthButton.isEnabled = true
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Initialization Failed"
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Failed to copy assets"
                    }
                }
            }
        }

        synthButton.setOnClickListener {
            val text = inputText.text.toString()
            if (text.isNotEmpty()) {
                generateAndPlay(text)
            }
        }
    }

    private fun setupQualityControl() {
        // Range 2-10
        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSteps = progress + 2
                qualityValue.text = "$currentSteps steps"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupPlaybackControls() {
        btnRewind.setOnClickListener { playbackService?.seekRelative(-10) }
        btnForward.setOnClickListener { playbackService?.seekRelative(10) }
        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnStop.setOnClickListener { playbackService?.stop() }
        btnCancel.setOnClickListener { playbackService?.cancelSynthesis() }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupVoiceSpinner() {
        val voiceNames = voices.keys.toList().sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceNames)
        voiceSpinner.adapter = adapter
        val defaultIndex = voiceNames.indexOf("M1 - Lively, Upbeat")
        if (defaultIndex >= 0) voiceSpinner.setSelection(defaultIndex)

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = voiceNames[position]
                selectedVoiceFile = voices[selectedName] ?: "M1.json"
                // Save to preferences
                val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("selected_voice", selectedVoiceFile).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpeedControl() {
        // Range: 0.9x to 1.5x with 0.05 step
        // Min = 0.9, Max = 1.5, Range = 0.6
        // Steps = 0.6 / 0.05 = 12
        speedSeekBar.max = 12
        
        // Default 1.05x: (1.05 - 0.9) / 0.05 = 3
        speedSeekBar.progress = 3
        currentSpeed = 1.05f
        speedValue.text = "1.05x"

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeed = 0.9f + (progress * 0.05f)
                speedValue.text = String.format("%.2fx", currentSpeed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun copyAssets(): String? {
        val filesDir = filesDir
        val targetDir = File(filesDir, "onnx")
        if (!targetDir.exists()) targetDir.mkdirs()
        
        try {
            val assetManager = assets
            val onnxFiles = assetManager.list("onnx") ?: return null
            for (filename in onnxFiles) {
                val file = File(targetDir, filename)
                if (!file.exists()) { // Don't overwrite to save startup time
                    val inFile = assetManager.open("onnx/$filename")
                    val outStream = FileOutputStream(file)
                    inFile.copyTo(outStream)
                    inFile.close()
                    outStream.close()
                }
            }
            
            val styleDir = File(filesDir, "voice_styles")
            if (!styleDir.exists()) styleDir.mkdirs()
            val styleFiles = assetManager.list("voice_styles") ?: emptyArray()
             for (filename in styleFiles) {
                val file = File(styleDir, filename)
                if (!file.exists()) {
                    val inFile = assetManager.open("voice_styles/$filename")
                    val outStream = FileOutputStream(file)
                    inFile.copyTo(outStream)
                    inFile.close()
                    outStream.close()
                }
            }
            return targetDir.absolutePath
        } catch (e: IOException) {
            return null
        }
    }

    private fun generateAndPlay(text: String) {
        if (!isBound || playbackService == null) {
            statusText.text = "Service not bound"
            return
        }

        synthButton.isEnabled = false
        statusText.text = "Starting Synthesis..."
        
        scope.launch(Dispatchers.IO) {
            val stylePath = File(filesDir, "voice_styles/$selectedVoiceFile").absolutePath
            
            if (!File(stylePath).exists()) {
                withContext(Dispatchers.Main) {
                     statusText.text = "Error: Voice style '$selectedVoiceFile' not found"
                     synthButton.isEnabled = true
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                playbackService?.synthesizeAndPlay(text, stylePath, currentSpeed, currentSteps)
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        inputContainer.alpha = if (enabled) 1.0f else 0.3f
        
        // Recursively disable/enable all children
        fun setEnableRecursive(view: View, enable: Boolean) {
            view.isEnabled = enable
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    setEnableRecursive(view.getChildAt(i), enable)
                }
            }
        }
        setEnableRecursive(inputContainer, enabled)
    }

    private fun showPlaybackUI() {
        setInputEnabled(false)
        playbackControls.visibility = View.VISIBLE
    }

    private fun hidePlaybackUI() {
        setInputEnabled(true)
        playbackControls.visibility = View.GONE
        synthButton.isEnabled = true
        statusText.text = "Idle"
    }

    // PlaybackListener Implementation
    override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
        runOnUiThread {
            if (isSynthesizing) {
                // Show loading state
                statusText.text = "Synthesizing..."
                nowPlayingText.text = "Generating Audio..."
                
                setInputEnabled(false)
                
                playbackControls.visibility = View.VISIBLE
                
                // Show progress/cancel, hide playback buttons
                findViewById<View>(R.id.mediaButtons).visibility = View.GONE
                btnStop.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                synthesisProgressBar.visibility = View.VISIBLE
                synthesisProgressText.visibility = View.VISIBLE
                // Don't force indeterminate if we already have progress? 
                // Resetting here is fine for start.
                if (synthesisProgressBar.progress == 0) {
                     synthesisProgressBar.isIndeterminate = true
                     synthesisProgressText.text = "Processing..."
                }
                
            } else if (hasContent) {
                // Playback ready or playing
                val stateText = if (isPlaying) "Playing..." else "Paused"
                statusText.text = stateText
                nowPlayingText.text = stateText
                
                setInputEnabled(false)
                
                playbackControls.visibility = View.VISIBLE
                
                findViewById<View>(R.id.mediaButtons).visibility = View.VISIBLE
                btnStop.visibility = View.VISIBLE
                btnCancel.visibility = View.GONE
                synthesisProgressBar.visibility = View.GONE
                synthesisProgressText.visibility = View.GONE
                
                btnPlayPause.text = if (isPlaying) "Pause" else "Play"
            } else {
                // Idle / Stopped
                hidePlaybackUI()
            }
        }
    }

    override fun onPlaybackStopped() {
        runOnUiThread {
            hidePlaybackUI()
        }
    }

    override fun onProgress(current: Int, total: Int) {
        runOnUiThread {
            if (total > 0) {
                synthesisProgressBar.isIndeterminate = false
                synthesisProgressBar.max = total
                synthesisProgressBar.progress = current
                synthesisProgressText.text = "$current / $total chunks"
            } else {
                synthesisProgressBar.isIndeterminate = true
                synthesisProgressText.text = "Processing..."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            playbackService?.setListener(null)
            unbindService(connection)
            isBound = false
        }
        scope.cancel()
    }
}