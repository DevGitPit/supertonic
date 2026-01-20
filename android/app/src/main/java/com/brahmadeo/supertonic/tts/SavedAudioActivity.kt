package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import com.brahmadeo.supertonic.tts.ui.SavedAudioScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import java.io.File

class SavedAudioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SupertonicTheme {
                SavedAudioScreen(
                    onBackClick = { finish() },
                    onPlayAudio = { file -> playAudio(file) }
                )
            }
        }
    }

    private fun playAudio(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "audio/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val chooser = Intent.createChooser(intent, "Play with...")
            startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}