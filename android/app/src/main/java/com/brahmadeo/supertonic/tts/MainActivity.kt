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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.utils.HistoryManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var importVoiceBtn: Button
    private lateinit var historyBtn: Button
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

    private val importVoiceLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val contentResolver = applicationContext.contentResolver
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    // Ignore if unable to take persistable permission
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    var filename = uri.lastPathSegment ?: "imported_voice.json"
                    if (!filename.endsWith(".json")) filename += ".json"
                    filename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    
                    val voiceDir = File(filesDir, "voice_styles")
                    if (!voiceDir.exists()) voiceDir.mkdirs()
                    
                    val outFile = File(voiceDir, filename)
                    val outputStream = FileOutputStream(outFile)
                    
                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Imported $filename", Toast.LENGTH_SHORT).show()
                        setupVoiceSpinner()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Import Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                inputText.setText(selectedText)
                Toast.makeText(this, "Loaded from History", Toast.LENGTH_SHORT).show()
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

        statusText = findViewById(R.id.statusText)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        importVoiceBtn = findViewById(R.id.importVoiceBtn)
        historyBtn = findViewById(R.id.historyBtn)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedValue = findViewById(R.id.speedValue)
        qualitySeekBar = findViewById(R.id.qualitySeekBar)
        qualityValue = findViewById(R.id.qualityValue)
        inputText = findViewById(R.id.inputText)
        synthButton = findViewById(R.id.synthButton)
        
        findViewById<View>(R.id.playbackControls)?.visibility = View.GONE

        val placeholderText = "Hello world, this is Supertonic TTS on Android. Select a voice and speed above!"
        inputText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && inputText.text.toString() == placeholderText) {
                inputText.setText("")
            }
        }
        
        importVoiceBtn.setOnClickListener {
            importVoiceLauncher.launch("application/json")
        }

        historyBtn.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        setupSpeedControl()
        setupQualityControl()
        checkNotificationPermission()

        synthButton.isEnabled = false
        statusText.text = "Initializing..."

        startService(Intent(this, PlaybackService::class.java))

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
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                inputText.setText(sharedText)
                statusText.text = "Received shared text"
            }
        } else {
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                inputText.setText(text)
                statusText.text = "Received text from browser"
            }
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceNames)
        voiceSpinner.adapter = adapter
        
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val savedFile = prefs.getString("selected_voice", "M1.json")
        
        val savedName = voiceFiles.entries.find { it.value == savedFile }?.key
        val defaultIndex = if (savedName != null) voiceNames.indexOf(savedName) else 0
        
        if (defaultIndex >= 0 && defaultIndex < voiceNames.size) {
            voiceSpinner.setSelection(defaultIndex)
        }

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = voiceNames[position]
                selectedVoiceFile = voiceFiles[name] ?: "M1.json"
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

        // Save to History
        val voiceName = voiceSpinner.selectedItem.toString()
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