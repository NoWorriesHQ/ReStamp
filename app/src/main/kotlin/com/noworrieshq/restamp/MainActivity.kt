package com.noworrieshq.restamp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.noworrieshq.restamp.ui.FileDetailScreen
import com.noworrieshq.restamp.ui.HistoryScreen
import com.noworrieshq.restamp.ui.HomeScreen
import com.noworrieshq.restamp.ui.Screen
import com.noworrieshq.restamp.ui.SingleCopyScreen
import com.noworrieshq.restamp.ui.ViewFileScreen
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
        setContent {
            ReStampTheme {
                BackHandler(enabled = matches.screen != Screen.Home) { matches.back() }
                when (val screen = matches.screen) {
                    Screen.Home -> HomeScreen(matches)
                    Screen.View -> ViewFileScreen(matches)
                    Screen.Single -> SingleCopyScreen(matches)
                    Screen.Bulk -> MatchScreen(
                        matches,
                        onOpenFile = { matches.screen = Screen.BulkFile(it.id) },
                        onHome = { matches.screen = Screen.Home },
                    )
                    is Screen.BulkFile -> FileDetailScreen(matches, screen.targetId, onBack = { matches.back() })
                    Screen.History -> HistoryScreen(matches.tags, onBack = { matches.back() })
                }
                // Result of the last save or bulk copy, on whichever screen is showing.
                matches.tags.lastMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = matches.tags::dismissMessage,
                        text = { Text(msg) },
                        confirmButton = { TextButton(onClick = matches.tags::dismissMessage) { Text("OK") } },
                        dismissButton = matches.tags.lastChanges.takeIf { it.isNotEmpty() }?.let { changes ->
                            { TextButton(onClick = { matches.tags.undo(changes) }) { Text("Undo") } }
                        },
                    )
                }
            }
        }
    }
}
