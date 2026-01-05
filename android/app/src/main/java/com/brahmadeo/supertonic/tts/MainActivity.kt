package com.brahmadeo.supertonic.tts

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.HistoryManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var voiceSpinner: AutoCompleteTextView
    private lateinit var speedSeekBar: Slider
    private lateinit var speedValue: TextView
    private lateinit var qualitySeekBar: Slider
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

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                inputText.setText(selectedText)
                
                AlertDialog.Builder(this)
                    .setTitle("Play Selected Text?")
                    .setMessage("Would you like to start synthesis for this text immediately?")
                    .setPositiveButton("Yes") { _, _ ->
                        generateAndPlay(selectedText)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }
    }

    private val defaultVoiceNames = mapOf(
        "M1.json" to "M1 - Lively, Upbeat",
        "M2.json" to "M2 - Deep, Calm",
        "M3.json" to "M3 - Polished, Authoritative",
        "M4.json" to "M4 - Soft, Youthful",
        "M5.json" to "M5 - Warm, Soothing",
        "F1.json" to "F1 - Calm, Professional",
        "F2.json" to "F2 - Bright, Playful",
        "F3.json" to "F3 - Broadcast, Clear",
        "F4.json" to "F4 - Crisp, Confident",
        "F5.json" to "F5 - Kind, Gentle"
    )
    
    private var voiceFiles = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.topAppBar)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedValue = findViewById(R.id.speedValue)
        qualitySeekBar = findViewById(R.id.qualitySeekBar)
        qualityValue = findViewById(R.id.qualityValue)
        inputText = findViewById(R.id.inputText)
        synthButton = findViewById(R.id.synthButton)

        inputText.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        // Load saved preferences
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        currentSteps = prefs.getInt("diffusion_steps", 5)
        qualitySeekBar.value = currentSteps.toFloat()
        qualityValue.text = "$currentSteps steps"

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_reset -> {
                    inputText.setText("")
                    val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                    startService(stopIntent)
                    Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_saved -> {
                    startActivity(Intent(this, SavedAudioActivity::class.java))
                    true
                }
                R.id.action_history -> {
                    historyLauncher.launch(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_lexicon -> {
                    startActivity(Intent(this, LexiconActivity::class.java))
                    true
                }
                else -> false
            }
        }

        setupSpeedControl()
        setupQualityControl()
        checkNotificationPermission()

        // Explicitly disable and show loading state
        synthButton.isEnabled = false
        synthButton.text = "Loading Engine..."
        toolbar.title = "Initializing..."

        startService(Intent(this, PlaybackService::class.java))
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)

        scope.launch(Dispatchers.IO) {
            val modelPath = copyAssets()
            withContext(Dispatchers.Main) {
                setupVoiceSpinner()
            }
            
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            
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
                        toolbar.title = "Ready (SoC: $socName)"
                        synthButton.text = "Synthesize"
                        synthButton.isEnabled = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toolbar.title = "Initialization Failed"
                        synthButton.text = "Engine Error"
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    toolbar.title = "Failed to copy assets"
                    synthButton.text = "Asset Error"
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
        checkResumeState()
    }

    private fun checkResumeState() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlaying = prefs.getBoolean("is_playing", false)
        
        if (!lastText.isNullOrEmpty() && isPlaying) {
            AlertDialog.Builder(this)
                .setTitle("Resume Playback?")
                .setMessage("Would you like to continue reading from where you left off?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(this, PlaybackActivity::class.java)
                    intent.putExtra("is_resume", true)
                    startActivity(intent)
                }
                .setNegativeButton("No") { _, _ ->
                    getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply()
                    // Housekeeping stop
                    val stopIntent = Intent(this, PlaybackService::class.java)
                    stopIntent.action = "STOP_PLAYBACK"
                    startService(stopIntent)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                inputText.setText(sharedText)
                toolbar.title = "Shared Text"
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            
            if (!text.isNullOrEmpty()) {
                inputText.setText(text)
                toolbar.title = "Fetched Web Text"
            }
        }
    }

    private fun setupQualityControl() {
        qualitySeekBar.addOnChangeListener { _, value, _ ->
            currentSteps = value.toInt()
            qualityValue.text = "$currentSteps steps"
            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("diffusion_steps", currentSteps)
                .apply()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupVoiceSpinner() {
        voiceFiles.clear()
        val voiceDir = File(filesDir, "voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                val friendlyName = defaultVoiceNames[file.name] ?: file.name.removeSuffix(".json")
                voiceFiles[friendlyName] = file.name
            }
        }
        
        if (voiceFiles.isEmpty()) {
            defaultVoiceNames.forEach { (filename, name) -> voiceFiles[name] = filename }
        }

        val voiceNames = voiceFiles.keys.toList().sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, voiceNames)
        voiceSpinner.setAdapter(adapter)
        
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val savedFile = prefs.getString("selected_voice", "M1.json")
        val savedName = voiceFiles.entries.find { it.value == savedFile }?.key ?: voiceNames.firstOrNull()
        
        if (savedName != null) {
            voiceSpinner.setText(savedName, false)
            selectedVoiceFile = voiceFiles[savedName] ?: "M1.json"
        }

        voiceSpinner.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val name = voiceNames[position]
            val newVoice = voiceFiles[name] ?: "M1.json"
            
            if (selectedVoiceFile != newVoice) {
                selectedVoiceFile = newVoice
                getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
                    .edit().putString("selected_voice", selectedVoiceFile).apply()
                
                val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                startService(resetIntent)
            }
        }
    }

    private fun setupSpeedControl() {
        speedSeekBar.addOnChangeListener { _, value, _ ->
            currentSpeed = value
            speedValue.text = String.format("%.2fx", currentSpeed)
        }
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
                val inFile = assetManager.open("onnx/$filename")
                val outStream = FileOutputStream(file)
                inFile.copyTo(outStream)
                inFile.close()
                outStream.close()
            }
            
            val styleDir = File(filesDir, "voice_styles")
            if (!styleDir.exists()) styleDir.mkdirs()
            val styleFiles = assetManager.list("voice_styles") ?: emptyArray()
             for (filename in styleFiles) {
                val file = File(styleDir, filename)
                val inFile = assetManager.open("voice_styles/$filename")
                val outStream = FileOutputStream(file)
                inFile.copyTo(outStream)
                inFile.close()
                outStream.close()
            }
            return targetDir.absolutePath
        } catch (e: IOException) {
            return null
        }
    }

    private fun generateAndPlay(text: String) {
        val stylePath = File(filesDir, "voice_styles/$selectedVoiceFile").absolutePath
        
        if (!File(stylePath).exists()) {
             toolbar.subtitle = "Error: Voice style not found"
             return
        }

        val voiceName = voiceSpinner.text.toString()
        HistoryManager.saveItem(this, text, voiceName)

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