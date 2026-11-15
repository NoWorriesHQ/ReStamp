package com.noworrieshq.restamp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.noworrieshq.restamp.media.MediaFiles
import com.noworrieshq.restamp.ui.EngineCheckScreen
import com.noworrieshq.restamp.ui.ReStampTheme

class MainActivity : ComponentActivity() {
    private val requestLocationAccess =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Needed to see the real GPS in originals (Android blanks it otherwise).
        requestLocationAccess.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val files = MediaFiles(applicationContext)
        setContent {
            ReStampTheme { EngineCheckScreen(files) }
        }
    }
}
