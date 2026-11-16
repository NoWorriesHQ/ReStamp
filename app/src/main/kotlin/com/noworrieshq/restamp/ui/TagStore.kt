package com.noworrieshq.restamp.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noworrieshq.restamp.core.MetadataEngine
import com.noworrieshq.restamp.core.match.FilePair
import com.noworrieshq.restamp.core.match.MediaFile
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.plan.BatchPlan
import com.noworrieshq.restamp.core.plan.CopyJob
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.media.MediaFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app knows about one file's tags. */
sealed interface FileTags {
    data object Loading : FileTags
    data class Loaded(val report: MetadataEngine.Report) : FileTags
    data class Failed(val message: String) : FileTags
}

/** Reads every listed file's tags in the background and writes tags into edited copies. */
class TagStore(private val files: MediaFiles, private val scope: CoroutineScope) {
    private val tags = mutableStateMapOf<String, FileTags>()
    private val queue = ArrayDeque<String>()
    private var loader: Job? = null

    /** Batch copy progress as (done, total), or null when no batch is running. */
    var batchProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var lastMessage by mutableStateOf<String?>(null); private set

    operator fun get(file: MediaFile): FileTags = tags[file.id] ?: FileTags.Loading

    fun report(file: MediaFile?): MetadataEngine.Report? = (file?.let { tags[it.id] } as? FileTags.Loaded)?.report

    fun planFor(pair: FilePair): CopyPlan? {
        val s = report(pair.source) ?: return null
        val t = report(pair.target) ?: return null
        return CopyPlan.between(s.metadata, t.metadata)
    }

    fun batch(pairs: List<FilePair>): BatchPlan =
        BatchPlan(pairs.mapNotNull { p -> planFor(p)?.let { p.target.id to it } }.toMap())

    /** Starts reading any of [list] not read yet. Cheap to call after every change. */
    fun ensureLoaded(list: Collection<MediaFile>) {
        list.forEach { f -> if (f.id !in tags) { tags[f.id] = FileTags.Loading; queue.addLast(f.id) } }
        if (loader?.isActive == true || queue.isEmpty()) return
        loader = scope.launch {
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                tags[id] = read(id)
            }
        }
    }

    fun clear() {
        queue.clear()
        loader?.cancel()
        tags.clear()
    }

    fun dismissMessage() { lastMessage = null }

    /** Writes into one edited copy. [confirmed] lists the overwrites the person agreed to. */
    fun write(target: MediaFile, update: MetadataUpdate, confirmed: Set<MetadataField>) {
        scope.launch {
            lastMessage = runCatching { withContext(Dispatchers.IO) { files.write(Uri.parse(target.id), update, confirmed) } }
                .fold(
                    { r -> if (r.written.isEmpty()) "Nothing changed: ${target.name} already had those tags." else "Saved ${r.written.fieldNames()} to ${target.name}." },
                    { e -> "Couldn't save ${target.name}: ${e.message}" },
                )
            tags[target.id] = read(target.id)
        }
    }

    /** Runs a batch copy one file at a time, rereading each file afterwards. */
    fun run(jobs: List<CopyJob>, names: Map<String, String>) {
        if (jobs.isEmpty() || batchProgress != null) return
        scope.launch {
            var done = 0
            var failed = 0
            var firstError: String? = null
            batchProgress = 0 to jobs.size
            for (job in jobs) {
                runCatching { withContext(Dispatchers.IO) { files.write(Uri.parse(job.id), job.update, job.confirmed) } }
                    .onFailure { e -> failed++; if (firstError == null) firstError = "${names[job.id] ?: "a file"}: ${e.message}" }
                tags[job.id] = read(job.id)
                done++
                batchProgress = done to jobs.size
            }
            batchProgress = null
            lastMessage = buildString {
                append("Updated ${jobs.size - failed} of ${jobs.size} files.")
                if (failed > 0) append(" $failed failed, first was $firstError")
            }
        }
    }

    private suspend fun read(id: String): FileTags = withContext(Dispatchers.IO) {
        runCatching { files.inspect(Uri.parse(id)) }
            .fold({ FileTags.Loaded(it) }, { FileTags.Failed(it.message ?: it.javaClass.simpleName) })
    }
}

fun MetadataField.fieldName() = when (this) {
    MetadataField.DATE_TAKEN -> "date taken"
    MetadataField.LOCATION -> "location"
}

fun Set<MetadataField>.fieldNames() = if (isEmpty()) "nothing" else joinToString(" and ") { it.fieldName() }
