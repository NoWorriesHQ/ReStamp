package com.noworrieshq.restamp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.core.MetadataEngine
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.core.plan.FieldStatus
import com.noworrieshq.restamp.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Step 1 test screen: pick an original and an edited copy, see what each carries, and copy
 * date taken and GPS across. The folder matching and comparison screens come in later steps.
 */
@Composable
fun EngineCheckScreen(files: MediaFiles) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Picked?>(null) }
    var target by remember { mutableStateOf<Picked?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<CopyPlan?>(null) }

    fun load(uri: Uri, set: (Picked) -> Unit) = scope.launch {
        val picked = withContext(Dispatchers.IO) {
            runCatching { files.inspect(uri) }.fold({ Picked(uri, it, null) }, { Picked(uri, null, it.message) })
        }
        set(picked)
    }

    val types = arrayOf("image/jpeg", "video/mp4")
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it) { p -> source = p } }
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it) { p -> target = p } }
    }

    val plan = source?.report?.let { s -> target?.report?.let { t -> CopyPlan.between(s.metadata, t.metadata) } }

    fun copy(confirmed: Set<MetadataField>) {
        val p = plan ?: return
        val t = target ?: return
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { files.write(t.uri, p.toUpdate(confirmed), confirmed) }
                    .fold({ r -> "Written: ${r.written.label()}. Already matching: ${r.alreadyMatching.label()}." }, { "Failed: ${it.message}" })
            }
            load(t.uri) { target = it }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Metadata engine check", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickSource.launch(types) }) { Text("Pick original") }
                OutlinedButton(onClick = { pickTarget.launch(types) }) { Text("Pick edited copy") }
            }
            FileCard("Original", source)
            FileCard("Edited copy", target)

            if (plan != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Copy plan", style = MaterialTheme.typography.titleMedium)
                        plan.fields.forEach { Text("${it.field.label()}: ${it.status.label()}") }
                        val writable = plan.fieldsToWrite(plan.needsConfirmation).isNotEmpty()
                        Button(
                            enabled = writable,
                            onClick = { if (plan.needsConfirmation.isEmpty()) copy(emptySet()) else confirming = plan },
                        ) { Text("Copy to edited copy") }
                    }
                }
            }
            message?.let { Text(it) }
        }
    }

    confirming?.let { p -> OverwriteDialog(p, onDismiss = { confirming = null }) { confirmed -> confirming = null; copy(confirmed) } }
}

private data class Picked(val uri: Uri, val report: MetadataEngine.Report?, val error: String?)

@Composable
private fun FileCard(title: String, picked: Picked?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when {
                picked == null -> Text("Not picked yet")
                picked.report == null -> Text("Can't read: ${picked.error}", color = MaterialTheme.colorScheme.error)
                else -> {
                    val m = picked.report.metadata
                    Text("Type: ${picked.report.type}")
                    Text("Date taken: " + (m.dateTaken?.let { d ->
                        d.toInstant(ZoneId.systemDefault()).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)
                    } ?: "missing"))
                    Text(
                        "Location: " + when {
                            m.location != null -> "%.5f, %.5f".format(m.location!!.latitude, m.location!!.longitude)
                            m.locationRedacted -> "hidden by Android (allow location access)"
                            else -> "missing"
                        },
                    )
                    if (picked.report.hasContentCredentials) {
                        Text("Signed by the camera (Content Credentials). Editing it would break the signature.")
                    }
                }
            }
        }
    }
}

@Composable
private fun OverwriteDialog(plan: CopyPlan, onDismiss: () -> Unit, onConfirm: (Set<MetadataField>) -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<MetadataField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overwrite existing tags?") },
        text = {
            Column {
                Text("The edited copy already has these tags with different values. Tick the ones to replace.")
                plan.fields.filter { it.status == FieldStatus.DIFFERENT_NEEDS_CONFIRMATION }.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.field in chosen, onCheckedChange = { on -> chosen = if (on) chosen + f.field else chosen - f.field })
                        Text(f.field.label())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z")

private fun MetadataField.label() = when (this) {
    MetadataField.DATE_TAKEN -> "Date taken"
    MetadataField.LOCATION -> "Location"
}

private fun Set<MetadataField>.label() = if (isEmpty()) "none" else joinToString { it.label() }

private fun FieldStatus.label() = when (this) {
    FieldStatus.MISSING_IN_SOURCE -> "not in original"
    FieldStatus.WILL_ADD -> "will be added"
    FieldStatus.ALREADY_SAME -> "already the same"
    FieldStatus.DIFFERENT_NEEDS_CONFIRMATION -> "different, needs your OK to overwrite"
}
