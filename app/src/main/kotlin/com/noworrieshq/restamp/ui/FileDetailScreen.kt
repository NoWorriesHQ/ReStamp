package com.noworrieshq.restamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.core.MetadataEngine
import com.noworrieshq.restamp.core.match.MediaFile
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.core.plan.FieldStatus
import com.noworrieshq.restamp.core.plan.overwritesIn
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One edited copy, next to its original when it has one. Each tag can be copied from the
 * original or set by hand. Replacing a tag the copy already has always asks first.
 */
@Composable
fun FileDetailScreen(vm: MatchViewModel, targetId: String, onBack: () -> Unit) {
    val found = vm.findTarget(targetId)
    if (found == null) {
        // The file left the list (for example after a rescan).
        LaunchedEffect(targetId) { onBack() }
        return
    }
    val (target, source) = found
    val tags = vm.tags
    val targetReport = tags.report(target)
    val sourceReport = tags.report(source)
    val plan = if (sourceReport != null && targetReport != null) CopyPlan.between(sourceReport.metadata, targetReport.metadata) else null

    var pending by remember { mutableStateOf<MetadataUpdate?>(null) }
    var editingDate by remember { mutableStateOf(false) }
    var pickingLocation by remember { mutableStateOf(false) }

    fun propose(update: MetadataUpdate) {
        val current = targetReport?.metadata ?: return
        if (overwritesIn(current, update).isEmpty()) tags.write(target, update, emptySet()) else pending = update
    }

    if (pickingLocation) {
        LocationPickerScreen(
            initial = targetReport?.metadata?.location ?: sourceReport?.metadata?.location,
            onCancel = { pickingLocation = false },
            onPicked = { loc -> pickingLocation = false; propose(MetadataUpdate(location = loc)) },
        )
        return
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Back to list") }
            Text(target.name, style = MaterialTheme.typography.titleLarge)
            Text(if (source != null) "Original: ${source.name}" else "No original paired. You can still set the tags by hand.", style = MaterialTheme.typography.bodyMedium)

            when {
                targetReport == null -> FileTagBadges(tags[target])
                else -> {
                    FieldCard(
                        field = MetadataField.DATE_TAKEN,
                        source = sourceReport?.metadata,
                        target = targetReport.metadata,
                        status = plan?.fields?.first { it.field == MetadataField.DATE_TAKEN }?.status,
                        onCopy = { propose(MetadataUpdate(dateTaken = sourceReport?.metadata?.dateTaken)) },
                        setByHandLabel = "Set date and time",
                        onSetByHand = { editingDate = true },
                    )
                    FieldCard(
                        field = MetadataField.LOCATION,
                        source = sourceReport?.metadata,
                        target = targetReport.metadata,
                        status = plan?.fields?.first { it.field == MetadataField.LOCATION }?.status,
                        onCopy = { propose(MetadataUpdate(location = sourceReport?.metadata?.location)) },
                        setByHandLabel = "Pick on map",
                        onSetByHand = { pickingLocation = true },
                    )
                    if (plan != null && plan.fieldsToWrite(plan.needsConfirmation).size > 1) {
                        Button(onClick = { propose(plan.toUpdate(plan.needsConfirmation)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copy both from original")
                        }
                    }
                    Notes(sourceReport, targetReport)
                }
            }
        }
    }

    if (editingDate) {
        val initial = targetReport?.metadata?.dateTaken ?: sourceReport?.metadata?.dateTaken
        DateTimeDialog(
            initial = initial,
            onDismiss = { editingDate = false },
            onPicked = { d -> editingDate = false; propose(MetadataUpdate(dateTaken = d)) },
        )
    }

    pending?.let { update ->
        val current = targetReport?.metadata
        if (current == null) {
            pending = null
        } else {
            OverwriteConfirmDialog(
                current = current,
                update = update,
                onDismiss = { pending = null },
                onConfirm = { confirmed -> pending = null; tags.write(target, update, confirmed) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldCard(
    field: MetadataField,
    source: MediaMetadata?,
    target: MediaMetadata,
    status: FieldStatus?,
    onCopy: () -> Unit,
    setByHandLabel: String,
    onSetByHand: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(field.fieldName().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (status) {
                    FieldStatus.ALREADY_SAME -> TagBadge("matches", TagLook.PRESENT)
                    FieldStatus.WILL_ADD -> TagBadge("missing", TagLook.MISSING)
                    FieldStatus.DIFFERENT_NEEDS_CONFIRMATION -> TagBadge("differs", TagLook.DIFFERENT)
                    FieldStatus.MISSING_IN_SOURCE -> TagBadge("not in original", TagLook.UNAVAILABLE)
                    null -> if (target.has(field)) TagBadge("present", TagLook.PRESENT) else TagBadge("missing", TagLook.MISSING)
                }
            }
            if (source != null) ValueLine("Original", source.describe(field))
            ValueLine("Edited copy", target.describe(field))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canCopy = status == FieldStatus.WILL_ADD || status == FieldStatus.DIFFERENT_NEEDS_CONFIRMATION
                if (source != null) Button(enabled = canCopy, onClick = onCopy) { Text("Copy from original") }
                OutlinedButton(onClick = onSetByHand) { Text(setByHandLabel) }
            }
        }
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Notes(source: MetadataEngine.Report?, target: MetadataEngine.Report) {
    val notes = buildList {
        if (source?.metadata?.locationRedacted == true || target.metadata.locationRedacted) {
            add("Android is hiding a location. Allow ReStamp to access photo locations in system settings to see it.")
        }
        if (target.hasContentCredentials) {
            add("This file is signed by the camera (Content Credentials). Changing it will break that signature.")
        }
    }
    notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Asks before replacing tags the edited copy already has, one tick per tag. */
@Composable
fun OverwriteConfirmDialog(
    current: MediaMetadata,
    update: MetadataUpdate,
    onDismiss: () -> Unit,
    onConfirm: (Set<MetadataField>) -> Unit,
) {
    val conflicts = overwritesIn(current, update)
    var chosen by remember { mutableStateOf(conflicts) }
    val adds = update.fields - conflicts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace existing tags?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                conflicts.forEach { f ->
                    CheckRow(
                        checked = f in chosen,
                        label = "Replace ${f.fieldName()}",
                        onChange = { on -> chosen = if (on) chosen + f else chosen - f },
                    )
                    Text("Now: ${current.describe(f)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                    Text("New: ${MediaMetadata(update.dateTaken, update.location).describe(f)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                }
                if (adds.isNotEmpty()) Text("${adds.fieldNames().replaceFirstChar { it.uppercase() }} will be added either way.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty() || adds.isNotEmpty(), onClick = { onConfirm(chosen) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks a date, then a time, in the phone's time zone. Seconds are kept from [initial], since
 * the time picker has no seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(initial: DateTaken?, onDismiss: () -> Unit, onPicked: (DateTaken) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start: LocalDateTime = initial?.let { LocalDateTime.ofInstant(it.toInstant(zone), zone) } ?: LocalDateTime.now(zone).withNano(0)
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    var choosingTime by remember { mutableStateOf(false) }

    if (!choosingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(enabled = dateState.selectedDateMillis != null, onClick = { choosingTime = true }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time taken") },
            text = {
                Column {
                    TimePicker(state = timeState)
                    Text("Time zone: ${zone.id}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val date = Instant.ofEpochMilli(dateState.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    val local = date.atTime(timeState.hour, timeState.minute, start.second)
                    onPicked(DateTaken(local, zone.rules.getOffset(local)))
                }) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { choosingTime = false }) { Text("Back") } },
        )
    }
}
