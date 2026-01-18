package com.brahmadeo.supertonic.tts

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.MainScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.HistoryManager
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.brahmadeo.supertonic.tts.utils.QueueManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    // UI State
    private var inputTextState = mutableStateOf("")
    private var isSynthesizingState = mutableStateOf(true) // Start disabled (loading)

    // Settings State
    private var currentLangState = mutableStateOf("en")
    private var selectedVoiceFileState = mutableStateOf("M1.json")
    private var selectedVoiceFile2State = mutableStateOf("M2.json")
    private var isMixingEnabledState = mutableStateOf(false)
    private var mixAlphaState = mutableFloatStateOf(0.5f)
    private var currentSpeedState = mutableFloatStateOf(1.05f)
    private var currentStepsState = mutableIntStateOf(5)

    // Mini Player State
    private var showMiniPlayerState = mutableStateOf(false)
    private var miniPlayerTitleState = mutableStateOf("Now Playing")
    private var miniPlayerIsPlayingState = mutableStateOf(false)

    // Data
    private val voiceFiles = mutableStateMapOf<String, String>()
    private val languages = mapOf(
        "Auto (English)" to "en",
        "French" to "fr",
        "Portuguese" to "pt",
        "Spanish" to "es",
        "Korean" to "ko"
    )

    // Service
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    // Dialog State
    private var showQueueDialog = mutableStateOf(false)
    private var queueDialogText = ""

    private val playbackListener = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                miniPlayerIsPlayingState.value = isPlaying
                if (hasContent || isSynthesizing) {
                    showMiniPlayerState.value = true
                    val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "")
                    if (!lastText.isNullOrEmpty()) {
                        miniPlayerTitleState.value = lastText
                    }
                } else {
                    showMiniPlayerState.value = false
                }
            }
        }
        override fun onProgress(current: Int, total: Int) { }
        override fun onPlaybackStopped() {
            runOnUiThread {
                showMiniPlayerState.value = false
                miniPlayerIsPlayingState.value = false
            }
        }
        override fun onExportComplete(success: Boolean, path: String) { }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                inputTextState.value = selectedText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        checkNotificationPermission()

        val bindIntent = Intent(this, PlaybackService::class.java)
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)

        LexiconManager.load(this)
        QueueManager.initialize(this)

        // Asset Copy Task
        CoroutineScope(Dispatchers.IO).launch {
            val modelPath = copyAssets()
            withContext(Dispatchers.Main) {
                setupVoicesMap()
            }

            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"

            if (modelPath != null) {
                if (SupertonicTTS.initialize(modelPath, libPath)) {
                    withContext(Dispatchers.Main) {
                        isSynthesizingState.value = false // Enable button
                    }
                }
            }
        }

        handleIntent(intent)
        checkResumeState()

        setContent {
            SupertonicTheme {
                if (showQueueDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showQueueDialog.value = false },
                        title = { Text(getString(R.string.playback_active_title)) },
                        text = { Text(getString(R.string.playback_active_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                addToQueue(queueDialogText)
                                showQueueDialog.value = false
                            }) { Text(getString(R.string.add_to_queue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                playNow(queueDialogText)
                                showQueueDialog.value = false
                            }) { Text(getString(R.string.play_now)) }
                        }
                    )
                }

                MainScreen(
                    inputText = inputTextState.value,
                    onInputTextChange = { inputTextState.value = it },
                    isSynthesizing = isSynthesizingState.value,
                    onSynthesizeClick = {
                        if (inputTextState.value.isNotEmpty()) generateAndPlay(inputTextState.value)
                    },

                    languages = languages,
                    currentLangCode = currentLangState.value,
                    onLangChange = {
                        currentLangState.value = it
                        saveStringPref("selected_lang", it)
                    },

                    voices = voiceFiles,
                    selectedVoiceFile = selectedVoiceFileState.value,
                    onVoiceChange = {
                        if (selectedVoiceFileState.value != it) {
                            selectedVoiceFileState.value = it
                            saveStringPref("selected_voice", it)
                            val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                            startService(resetIntent)
                        }
                    },

                    isMixingEnabled = isMixingEnabledState.value,
                    onMixingEnabledChange = { isMixingEnabledState.value = it },
                    selectedVoiceFile2 = selectedVoiceFile2State.value,
                    onVoice2Change = {
                        selectedVoiceFile2State.value = it
                        saveStringPref("selected_voice_2", it)
                    },
                    mixAlpha = mixAlphaState.value,
                    onMixAlphaChange = { mixAlphaState.value = it },

                    speed = currentSpeedState.value,
                    onSpeedChange = { currentSpeedState.value = it },
                    steps = currentStepsState.value,
                    onStepsChange = {
                        currentStepsState.value = it
                        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putInt("diffusion_steps", it).apply()
                    },

                    onResetClick = {
                        inputTextState.value = ""
                        val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                        startService(stopIntent)
                    },
                    onSavedAudioClick = { startActivity(Intent(this, SavedAudioActivity::class.java)) },
                    onHistoryClick = { historyLauncher.launch(Intent(this, HistoryActivity::class.java)) },
                    onQueueClick = { startActivity(Intent(this, QueueActivity::class.java)) },
                    onLexiconClick = { startActivity(Intent(this, LexiconActivity::class.java)) },

                    showMiniPlayer = showMiniPlayerState.value,
                    miniPlayerTitle = miniPlayerTitleState.value,
                    miniPlayerIsPlaying = miniPlayerIsPlayingState.value,
                    onMiniPlayerClick = {
                        val intent = Intent(this, PlaybackActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        startActivity(intent)
                    },
                    onMiniPlayerPlayPauseClick = {
                         if (playbackService?.isServiceActive == true) {
                            try {
                                if (miniPlayerIsPlayingState.value) playbackService?.pause() else playbackService?.play()
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBound && playbackService != null) {
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        currentStepsState.intValue = prefs.getInt("diffusion_steps", 5)
        currentLangState.value = prefs.getString("selected_lang", "en") ?: "en"
        selectedVoiceFileState.value = prefs.getString("selected_voice", "M1.json") ?: "M1.json"
        selectedVoiceFile2State.value = prefs.getString("selected_voice_2", "M2.json") ?: "M2.json"
    }

    private fun saveStringPref(key: String, value: String) {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupVoicesMap() {
        val voiceResources = mapOf(
            "M1.json" to R.string.voice_m1,
            "M2.json" to R.string.voice_m2,
            "M3.json" to R.string.voice_m3,
            "M4.json" to R.string.voice_m4,
            "M5.json" to R.string.voice_m5,
            "F1.json" to R.string.voice_f1,
            "F2.json" to R.string.voice_f2,
            "F3.json" to R.string.voice_f3,
            "F4.json" to R.string.voice_f4,
            "F5.json" to R.string.voice_f5
        )

        voiceResources.forEach { (filename, resId) ->
            voiceFiles[getString(resId)] = filename
        }

        val voiceDir = File(filesDir, "voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                if (!voiceResources.containsKey(file.name)) {
                    val friendlyName = file.name.removeSuffix(".json")
                    voiceFiles[friendlyName] = file.name
                }
            }
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
        var stylePath = File(filesDir, "voice_styles/${selectedVoiceFileState.value}").absolutePath
        if (!File(stylePath).exists()) {
             Toast.makeText(this, "Voice style not found", Toast.LENGTH_SHORT).show()
             return
        }

        if (isMixingEnabledState.value) {
            val stylePath2 = File(filesDir, "voice_styles/${selectedVoiceFile2State.value}").absolutePath
            if (File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
            }
        }

        // Generate friendly voice name
        val v1Name = voiceFiles.entries.find { it.value == selectedVoiceFileState.value }?.key ?: "Voice 1"
        val v2Name = voiceFiles.entries.find { it.value == selectedVoiceFile2State.value }?.key ?: "Voice 2"
        val voiceName = if (isMixingEnabledState.value) "Mixed: $v1Name + $v2Name" else v1Name

        HistoryManager.saveItem(this, text, voiceName)

        try {
            if (playbackService?.isServiceActive == true) {
                queueDialogText = text
                showQueueDialog.value = true
            } else {
                launchPlaybackActivity(text, stylePath)
            }
        } catch (e: Exception) {
            launchPlaybackActivity(text, stylePath)
        }
    }

    private fun addToQueue(text: String) {
        var stylePath = File(filesDir, "voice_styles/${selectedVoiceFileState.value}").absolutePath
        if (isMixingEnabledState.value) {
            val stylePath2 = File(filesDir, "voice_styles/${selectedVoiceFile2State.value}").absolutePath
            stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
        }

        try {
            playbackService?.addToQueue(
                text,
                currentLangState.value,
                stylePath,
                currentSpeedState.value,
                currentStepsState.intValue,
                0
            )
            Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playNow(text: String) {
        var stylePath = File(filesDir, "voice_styles/${selectedVoiceFileState.value}").absolutePath
        if (isMixingEnabledState.value) {
            val stylePath2 = File(filesDir, "voice_styles/${selectedVoiceFile2State.value}").absolutePath
            stylePath = "$stylePath;$stylePath2;${mixAlphaState.value}"
        }
        launchPlaybackActivity(text, stylePath)
    }

    private fun launchPlaybackActivity(text: String, stylePath: String) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
            putExtra(PlaybackActivity.EXTRA_SPEED, currentSpeedState.value)
            putExtra(PlaybackActivity.EXTRA_STEPS, currentStepsState.intValue)
            putExtra(PlaybackActivity.EXTRA_LANG, currentLangState.value)
        }
        startActivity(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                inputTextState.value = sharedText
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                inputTextState.value = text
            }
        }
    }

    private fun checkResumeState() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlaying = prefs.getBoolean("is_playing", false)

        if (!lastText.isNullOrEmpty() && isPlaying) {
             AlertDialog.Builder(this)
                .setTitle(getString(R.string.resume_title))
                .setMessage(getString(R.string.resume_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val intent = Intent(this, PlaybackActivity::class.java)
                    intent.putExtra("is_resume", true)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.no)) { _, _ ->
                    getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply()
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        // scope.cancel() // Don't cancel IO scope to allow asset copy to finish if backgrounded
    }
}