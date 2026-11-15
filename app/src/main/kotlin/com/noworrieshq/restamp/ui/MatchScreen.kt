package com.noworrieshq.restamp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noworrieshq.restamp.core.match.FilePair
import com.noworrieshq.restamp.core.match.MatchConfidence
import com.noworrieshq.restamp.core.match.MediaFile
import com.noworrieshq.restamp.media.FolderScanner

/**
 * Step 2 screen: pick the originals folder and the edited copies folder, and see how the files
 * pair up. Pairs can be undone or made by hand. Copying the tags comes in step 3.
 */
@Composable
fun MatchScreen(vm: MatchViewModel, onOpenFile: (MediaFile) -> Unit, onOpenEngineCheck: () -> Unit) {
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickSource) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickTarget) }
    var choosingFor by remember { mutableStateOf<MediaFile?>(null) }
    var bulkCopying by remember { mutableStateOf(false) }
    val result = vm.result

    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Match originals to edited copies", style = MaterialTheme.typography.headlineSmall) }
            item {
                FolderCard(
                    title = "Originals",
                    hint = "Usually DCIM/Camera",
                    folder = vm.sourceFolder?.let(FolderScanner::displayName),
                    onPick = { pickSource.launch(vm.sourceFolder) },
                )
            }
            item {
                FolderCard(
                    title = "Edited copies",
                    hint = "Where your editor saved them. It can be the same folder.",
                    folder = vm.targetFolder?.let(FolderScanner::displayName),
                    onPick = { pickTarget.launch(vm.targetFolder ?: vm.sourceFolder) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include subfolders", Modifier.weight(1f))
                    Switch(checked = vm.includeSubfolders, onCheckedChange = vm::changeIncludeSubfolders)
                }
            }
            if (vm.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }

            if (result != null) {
                item {
                    Summary(
                        matched = result.pairs.size,
                        toChoose = result.ambiguous.size,
                        noOriginal = result.unmatchedTargets.size,
                        unusedOriginals = result.unmatchedSources.size,
                        skipped = vm.skipped,
                    )
                }
                if (!vm.manual.isEmpty) {
                    item { TextButton(onClick = vm::resetAll) { Text("Undo all my pairing changes") } }
                }

                section("Pick the original", result.ambiguous.size)
                items(result.ambiguous, key = { "a" + it.target.id }) { a ->
                    FileRow(a.target.name, "Several originals fit equally well") {
                        a.candidates.forEach { c ->
                            OutlinedButton(onClick = { vm.pair(a.target, c) }) { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }

                section("Edited copies with no original", result.unmatchedTargets.size)
                items(result.unmatchedTargets, key = { "u" + it.id }) { t ->
                    FileRow(t.name, if (t.id in vm.manual.unpaired) "You unpaired this" else "No original found") {
                        FileTagBadges(vm.tags[t])
                        Row {
                            TextButton(onClick = { choosingFor = t }) { Text("Pick original") }
                            TextButton(onClick = { onOpenFile(t) }) { Text("Set by hand") }
                            if (t.id in vm.manual.unpaired) TextButton(onClick = { vm.resetToAutomatic(t) }) { Text("Auto") }
                        }
                    }
                }

                section("Matched", result.pairs.size)
                if (result.pairs.isNotEmpty()) item(key = "bulk") { BulkBar(vm) { bulkCopying = true } }
                items(result.pairs, key = { "p" + it.target.id }) { p ->
                    PairRow(p, vm, onOpen = { onOpenFile(p.target) }, onChange = { choosingFor = p.target })
                }

                section("Originals with no edited copy", result.unmatchedSources.size)
                items(result.unmatchedSources, key = { "s" + it.id }) { s ->
                    Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (!vm.scanning) {
                item { Text("Choose both folders to see how the files pair up.") }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onOpenEngineCheck) { Text("Test one pair of files") }
            }
        }
    }

    if (bulkCopying) {
        val selected = vm.selectedPairs
        val batch = vm.tags.batch(selected)
        BulkCopyDialog(
            batch = batch,
            stillReading = selected.size - batch.items.size,
            onDismiss = { bulkCopying = false },
            onRun = { jobs ->
                bulkCopying = false
                vm.tags.run(jobs, selected.associate { it.target.id to it.target.name })
            },
        )
    }

    vm.tags.lastMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = vm.tags::dismissMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm.tags::dismissMessage) { Text("OK") } },
        )
    }

    choosingFor?.let { target ->
        ChooseOriginalDialog(
            target = target,
            suggestions = vm.suggestionsFor(target),
            all = vm.sources.filter { it.id != target.id }.sortedBy { it.name.lowercase() },
            onDismiss = { choosingFor = null },
            onChoose = { source -> vm.pair(target, source); choosingFor = null },
        )
    }
}

private fun LazyListScope.section(title: String, count: Int) {
    if (count == 0) return
    item(key = "h$title") {
        Text("$title ($count)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun FolderCard(title: String, hint: String, folder: String?, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(folder ?: hint, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onPick) { Text(if (folder == null) "Choose" else "Change") }
        }
    }
}

@Composable
private fun Summary(matched: Int, toChoose: Int, noOriginal: Int, unusedOriginals: Int, skipped: Int) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$matched matched", fontWeight = FontWeight.SemiBold)
            if (toChoose > 0) Text("$toChoose need you to pick the original")
            if (noOriginal > 0) Text("$noOriginal edited copies have no original")
            Text("$unusedOriginals originals have no edited copy")
            if (skipped > 0) Text("$skipped other files skipped (only JPEG and MP4 are supported)", style = MaterialTheme.typography.bodySmall)
            Text("Tag badges show the date taken and GPS stored inside each file (EXIF for photos), not file dates.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileRow(name: String, detail: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}

@Composable
private fun BulkBar(vm: MatchViewModel, onCopy: () -> Unit) {
    val pairs = vm.result?.pairs.orEmpty()
    val selected = vm.selectedPairs.size
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected == pairs.size,
                    onCheckedChange = { vm.includeAll(it) },
                )
                Text("$selected of ${pairs.size} selected", Modifier.weight(1f))
                Button(enabled = selected > 0 && vm.tags.batchProgress == null, onClick = onCopy) { Text("Copy tags") }
            }
            vm.tags.batchProgress?.let { (done, total) ->
                LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Updating $done of $total…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PairRow(pair: FilePair, vm: MatchViewModel, onOpen: () -> Unit, onChange: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
            Checkbox(checked = pair.target.id !in vm.excluded, onCheckedChange = { vm.setIncluded(pair.target, it) })
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pair.target.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("from ${pair.source.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PairTagBadges(vm.tags.planFor(pair), vm.tags[pair.source], vm.tags[pair.target])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pair.confidence.label(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onChange) { Text("Change") }
                    if (pair.confidence == MatchConfidence.MANUAL) {
                        TextButton(onClick = { vm.resetToAutomatic(pair.target) }) { Text("Auto") }
                    }
                    TextButton(onClick = { vm.unpair(pair.target) }) { Text("Unpair") }
                }
            }
        }
    }
}

@Composable
private fun ChooseOriginalDialog(
    target: MediaFile,
    suggestions: List<MediaFile>,
    all: List<MediaFile>,
    onDismiss: () -> Unit,
    onChoose: (MediaFile) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Original for ${target.name}") },
        text = {
            // Lazy, since a camera folder can hold thousands of originals.
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (suggestions.isNotEmpty()) {
                    item { Text("Closest names", style = MaterialTheme.typography.labelLarge) }
                    items(suggestions, key = { "s" + it.id }) { s -> TextButton(onClick = { onChoose(s) }) { Text(s.name) } }
                    item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                }
                item { Text("All originals", style = MaterialTheme.typography.labelLarge) }
                if (all.isEmpty()) item { Text("The originals folder has no JPEG or MP4 files.") }
                items(all, key = { "a" + it.id }) { s -> TextButton(onClick = { onChoose(s) }) { Text(s.name) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun MatchConfidence.label() = when (this) {
    MatchConfidence.EXACT_NAME -> "Same name"
    MatchConfidence.SAME_BASE_NAME -> "Name match"
    MatchConfidence.SAME_CAPTURE_TIME -> "Same capture time, check"
    MatchConfidence.MANUAL -> "Paired by you"
}
