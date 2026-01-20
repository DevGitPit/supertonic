package com.brahmadeo.supertonic.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brahmadeo.supertonic.tts.ui.HistoryScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

class HistoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SupertonicTheme {
                HistoryScreen(
                    onBackClick = { finish() },
                    onItemClick = { item ->
                        val resultIntent = Intent()
                        resultIntent.putExtra("selected_text", item.text)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    }
                )
            }
        }
    }
}
