package com.noworrieshq.restamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.media.UndoEntry
import java.text.DateFormat
import java.util.Date

/** Recent changes, a bulk copy shown as one row, each with Undo. */
@Composable
fun HistoryScreen(tags: TagStore, onBack: () -> Unit) {
    LaunchedEffect(Unit) { tags.refreshHistory() }
    // A bulk copy's files share a batch id; single saves stand alone.
    val groups = tags.history.groupBy { it.batch ?: it.id }.values.sortedByDescending { g -> g.maxOf { it.time } }
    var confirm by remember { mutableStateOf<List<UndoEntry>?>(null) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("‹ Home") }
            Text("Recent changes", style = MaterialTheme.typography.headlineSmall)
            Text(
                "ReStamp keeps a copy of what it changed for 30 days. Undo puts the file back exactly as it was, " +
                    "unless something else has changed it since.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (groups.isEmpty()) Text("Nothing to undo yet.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.first().batch ?: it.first().id }) { group ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val title = if (group.size == 1) group[0].name else "Bulk copy: ${group.size} files"
                                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val fields = group.flatMap { it.fields }.toSet()
                                val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(group.maxOf { it.time }))
                                Text("${fields.fieldNames().replaceFirstChar { it.uppercase() }} · $time", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { confirm = group }, enabled = tags.batchProgress == null) { Text("Undo") }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { group ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(if (group.size == 1) "Put ${group[0].name} back as it was?" else "Put all ${group.size} files back as they were?") },
            confirmButton = { TextButton(onClick = { tags.undo(group); confirm = null }) { Text("Undo") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
