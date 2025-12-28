package com.brahmadeo.supertonic.tts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.PlaybackService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var qualitySeekBar: SeekBar
    private lateinit var qualityValue: TextView
    private lateinit var inputText: EditText
    private lateinit var synthButton: Button
    
    private var currentSteps = 5
    private var selectedVoiceFile = "M1.json"
    private var currentSpeed = 1.05f

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
        
        // Hide overlay views if they exist in layout (we aren't using them anymore)
        findViewById<View>(R.id.playbackControls)?.visibility = View.GONE

        val placeholderText = "Hello world, this is Supertonic TTS on Android. Select a voice and speed above!"
        inputText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && inputText.text.toString() == placeholderText) {
                inputText.setText("")
            }
        }

        setupVoiceSpinner()
        setupSpeedControl()
        setupQualityControl()
        checkNotificationPermission()

        synthButton.isEnabled = false
        statusText.text = "Initializing..."

        // Start service to ensure it's alive (optional but good practice)
        startService(Intent(this, PlaybackService::class.java))

        // Initialize Engine
        scope.launch(Dispatchers.IO) {
            val modelPath = copyAssets()
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            
            // We need to access SupertonicTTS directly to initialize, or use Service binder.
            // Since MainActivity doesn't bind anymore (PlaybackActivity does), we can init via singleton helper or static call?
            // Actually SupertonicTTS object is accessible.
            // But good practice to do it via Service if we want to keep logic encapsulated.
            // However, MainActivity initialization logic was fine before.
            // Let's bind temporarily or just assume SupertonicTTS singleton works.
            // Yes, SupertonicTTS is an object.
            
            if (modelPath != null) {
                if (SupertonicTTS.initialize(modelPath, libPath)) {
                    val socClass = SupertonicTTS.getSoC()
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

        synthButton.setOnClickListener {
            val text = inputText.text.toString()
            if (text.isNotEmpty()) {
                generateAndPlay(text)
            }
        }
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.data?.getQueryParameter("text")
        if (!text.isNullOrEmpty()) {
            inputText.setText(text)
            statusText.text = "Received text from browser"
        }
    }

    private fun setupQualityControl() {
        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSteps = progress + 2
                qualityValue.text = "$currentSteps steps"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                selectedVoiceFile = voices[voiceNames[position]] ?: "M1.json"
                getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
                    .edit().putString("selected_voice", selectedVoiceFile).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpeedControl() {
        speedSeekBar.max = 12
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
                if (!file.exists()) {
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
        val stylePath = File(filesDir, "voice_styles/$selectedVoiceFile").absolutePath
        
        if (!File(stylePath).exists()) {
             statusText.text = "Error: Voice style not found"
             return
        }

        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
            putExtra(PlaybackActivity.EXTRA_SPEED, currentSpeed)
            putExtra(PlaybackActivity.EXTRA_STEPS, currentSteps)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}