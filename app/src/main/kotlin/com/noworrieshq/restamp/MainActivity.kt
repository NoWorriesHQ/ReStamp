package com.noworrieshq.restamp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.noworrieshq.restamp.media.MediaFiles
import com.noworrieshq.restamp.ui.EngineCheckScreen
import com.noworrieshq.restamp.ui.FileDetailScreen
import com.noworrieshq.restamp.ui.MatchScreen
import com.noworrieshq.restamp.ui.MatchViewModel
import com.noworrieshq.restamp.ui.ReStampTheme

class MainActivity : ComponentActivity() {
    private val requestLocationAccess =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val matches: MatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Needed to see the real GPS in originals (Android blanks it otherwise).
        requestLocationAccess.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val files = MediaFiles(applicationContext)
        setContent {
            ReStampTheme {
                var engineCheck by rememberSaveable { mutableStateOf(false) }
                var openFile by rememberSaveable { mutableStateOf<String?>(null) }
                when {
                    engineCheck -> {
                        BackHandler { engineCheck = false }
                        EngineCheckScreen(files)
                    }
                    openFile != null -> {
                        BackHandler { openFile = null }
                        FileDetailScreen(matches, openFile!!, onBack = { openFile = null })
                    }
                    else -> MatchScreen(
                        matches,
                        onOpenFile = { openFile = it.id },
                        onOpenEngineCheck = { engineCheck = true },
                    )
                }
            }
        }
    }
}
