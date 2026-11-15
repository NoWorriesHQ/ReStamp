package com.noworrieshq.restamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.plan.BatchPlan
import com.noworrieshq.restamp.core.plan.CopyJob
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.core.plan.FieldStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a tag looks in lists. Each has a symbol as well as a colour, so it reads without colour too. */
enum class TagLook { PRESENT, MISSING, DIFFERENT, UNAVAILABLE }

@Composable
fun TagBadge(text: String, look: TagLook) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (look) {
        TagLook.PRESENT -> c.primaryContainer to c.onPrimaryContainer
        TagLook.MISSING -> c.errorContainer to c.onErrorContainer
        TagLook.DIFFERENT -> c.tertiaryContainer to c.onTertiaryContainer
        TagLook.UNAVAILABLE -> c.surfaceVariant to c.onSurfaceVariant
    }
    val symbol = when (look) {
        TagLook.PRESENT -> "✓"
        TagLook.MISSING -> "✗"
        TagLook.DIFFERENT -> "≠"
        TagLook.UNAVAILABLE -> "–"
    }
    Text(
        "$symbol $text",
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Status badges for a matched pair: what copying would do to each tag. */
@Composable
fun PairTagBadges(plan: CopyPlan?, sourceTags: FileTags, targetTags: FileTags) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            plan != null -> plan.fields.forEach { f ->
                val name = f.field.shortName()
                when (f.status) {
                    FieldStatus.ALREADY_SAME -> TagBadge(name, TagLook.PRESENT)
                    FieldStatus.WILL_ADD -> TagBadge("$name missing", TagLook.MISSING)
                    FieldStatus.DIFFERENT_NEEDS_CONFIRMATION -> TagBadge("$name differs", TagLook.DIFFERENT)
                    FieldStatus.MISSING_IN_SOURCE ->
                        TagBadge(if (f.target.has(f.field)) name else "no $name in original", if (f.target.has(f.field)) TagLook.PRESENT else TagLook.UNAVAILABLE)
                }
            }
            sourceTags is FileTags.Failed || targetTags is FileTags.Failed -> Text(
                "Can't read: " + ((targetTags as? FileTags.Failed)?.message ?: (sourceTags as FileTags.Failed).message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text("Reading tags…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Status badges for a single file. */
@Composable
fun FileTagBadges(fileTags: FileTags) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (fileTags) {
            is FileTags.Loaded -> MetadataField.entries.forEach { f ->
                val m = fileTags.report.metadata
                if (m.has(f)) TagBadge(f.shortName(), TagLook.PRESENT) else TagBadge("${f.shortName()} missing", TagLook.MISSING)
            }
            is FileTags.Failed -> Text("Can't read: ${fileTags.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FileTags.Loading -> Text("Reading tags…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Confirms a bulk copy. Missing tags are added; replacing tags that differ is opt-in per tag,
 * because edited copies usually carry the edit time as their date.
 */
@Composable
fun BulkCopyDialog(
    batch: BatchPlan,
    stillReading: Int,
    onDismiss: () -> Unit,
    onRun: (List<CopyJob>) -> Unit,
) {
    var copy by remember { mutableStateOf(MetadataField.entries.toSet()) }
    var overwrite by remember { mutableStateOf(emptySet<MetadataField>()) }
    val jobs = batch.jobs(copy, overwrite)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy tags from originals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetadataField.entries.forEach { field ->
                    val adds = batch.canAdd(field)
                    val differs = batch.differing(field)
                    CheckRow(
                        checked = field in copy,
                        label = "Copy ${field.fieldName()} ($adds missing)",
                        onChange = { on -> copy = if (on) copy + field else copy - field },
                    )
                    if (differs > 0) {
                        CheckRow(
                            checked = field in overwrite,
                            enabled = field in copy,
                            label = "Also replace ${field.fieldName()} in $differs files that already have a different one",
                            indent = true,
                            onChange = { on -> overwrite = if (on) overwrite + field else overwrite - field },
                        )
                    }
                }
                if (batch.differing(MetadataField.DATE_TAKEN) > 0) {
                    Text(
                        "Edited copies usually carry the time they were edited, not taken, so their date shows as different.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (batch.nothingToDo > 0) Text("${batch.nothingToDo} already complete or nothing to copy.", style = MaterialTheme.typography.bodySmall)
                if (stillReading > 0) Text("$stillReading files are still being read and aren't included.", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (jobs.isEmpty()) "Nothing to change with these options." else "${jobs.size} files will be changed.",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = { TextButton(enabled = jobs.isNotEmpty(), onClick = { onRun(jobs) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit, enabled: Boolean = true, indent: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = if (indent) Modifier.padding(start = 24.dp) else Modifier) {
        Checkbox(checked = checked && enabled, enabled = enabled, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)
    }
}

fun MetadataField.shortName() = when (this) {
    MetadataField.DATE_TAKEN -> "Date"
    MetadataField.LOCATION -> "GPS"
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss")

/** "5 Oct 2024, 14:30:12 (UTC+10:00)", in the file's own offset when it has one. */
fun DateTaken.display(): String {
    val offset = utcOffset
    return if (offset != null) {
        "${localDateTime.format(DATE_FORMAT)} (UTC${if (offset.totalSeconds == 0) "" else offset.id})"
    } else {
        "${localDateTime.format(DATE_FORMAT)} (no time zone)"
    }
}

fun DateTaken.displayLocal(zone: ZoneId = ZoneId.systemDefault()): String =
    toInstant(zone).atZone(zone).toLocalDateTime().format(DATE_FORMAT)

fun GeoLocation.display(): String = "%.5f, %.5f".format(latitude, longitude)

fun MediaMetadata.describe(field: MetadataField): String = when (field) {
    MetadataField.DATE_TAKEN -> dateTaken?.display() ?: "missing"
    MetadataField.LOCATION -> location?.display() ?: if (locationRedacted) "hidden by Android" else "missing"
}
