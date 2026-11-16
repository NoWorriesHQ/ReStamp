package com.noworrieshq.restamp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.core.match.MediaFile

private val MEDIA_TYPES = arrayOf("image/jpeg", "video/mp4")

/** Main menu: view or edit one file, copy between two files, or copy across folders. */
@Composable
fun HomeScreen(vm: MatchViewModel) {
    val pickToView = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::view) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ReStamp", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Put the date taken and GPS location back into edited photos and videos.",
                style = MaterialTheme.typography.bodyMedium,
            )
            MenuCard(
                title = "View or edit metadata",
                detail = "Pick one photo or video to see its date taken and location, and set them by hand.",
                onClick = { pickToView.launch(MEDIA_TYPES) },
            )
            MenuCard(
                title = "Single file copy",
                detail = "Pick an original and its edited copy, and copy the tags across.",
                onClick = { vm.screen = Screen.Single },
            )
            MenuCard(
                title = "Bulk file copy",
                detail = "Pick an originals folder and an edited copies folder. Files are matched by name and fixed together.",
                onClick = { vm.screen = Screen.Bulk },
            )
            MenuCard(
                title = "Recent changes",
                detail = "See what ReStamp changed in the last 30 days and put files back as they were.",
                onClick = { vm.screen = Screen.History },
            )
        }
    }
}

@Composable
private fun MenuCard(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** View or edit one picked file. */
@Composable
fun ViewFileScreen(vm: MatchViewModel) {
    val file = vm.viewing
    if (file == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    FileEditor(vm.tags, target = file, source = null, backLabel = "‹ Home", showOriginalSlot = false, onBack = { vm.back() })
}

/** Pick an original and an edited copy, then compare and copy as in the bulk list. */
@Composable
fun SingleCopyScreen(vm: MatchViewModel) {
    val source = vm.singleSource
    val target = vm.singleTarget
    if (source != null && target != null) {
        // Back goes to the file choice, so either file can be swapped.
        FileEditor(vm.tags, target = target, source = source, backLabel = "‹ Choose other files", showOriginalSlot = true, onBack = vm::clearSinglePair)
        return
    }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickSingleSource) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickSingleTarget) }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = { vm.back() }) { Text("‹ Home") }
            Text("Single file copy", style = MaterialTheme.typography.headlineSmall)
            PickRow("Original", "The file straight from the camera", source) { pickSource.launch(MEDIA_TYPES) }
            PickRow("Edited copy", "The file that lost its tags", target) { pickTarget.launch(MEDIA_TYPES) }
        }
    }
}

@Composable
private fun PickRow(title: String, hint: String, file: MediaFile?, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(file?.name ?: hint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onPick) { Text(if (file == null) "Choose" else "Change") }
        }
    }
}
