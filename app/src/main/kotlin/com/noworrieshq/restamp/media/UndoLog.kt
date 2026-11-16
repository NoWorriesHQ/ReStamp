package com.noworrieshq.restamp.media

import com.noworrieshq.restamp.core.io.RandomAccessIo
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.undo.Undo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One file change ReStamp can take back. [batch] groups the files of one bulk copy. */
data class UndoEntry(
    val id: String,
    val uri: String,
    val name: String,
    val time: Long,
    val fields: Set<MetadataField>,
    val batch: String?,
)

/**
 * Keeps an undo record for every change ReStamp makes, in the app's private storage.
 * Records older than [MAX_AGE_MS] or beyond [MAX_ENTRIES] / [MAX_BYTES] are dropped, oldest first.
 */
class UndoLog(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val index = File(dir, "index.json")

    init { dir.mkdirs() }

    @Synchronized fun entries(): List<UndoEntry> = load().sortedByDescending { it.time }

    /** A fresh file for the next undo record. Pass it to [add] once the change succeeded. */
    fun newFile(): File = File(dir, UUID.randomUUID().toString() + ".undo")

    @Synchronized fun add(file: File, uri: String, name: String, fields: Set<MetadataField>, batch: String?): UndoEntry {
        val entry = UndoEntry(file.nameWithoutExtension, uri, name, now(), fields, batch)
        save(prune(load() + entry))
        return entry
    }

    /** Restores the file. Throws [com.noworrieshq.restamp.core.undo.UndoConflictException] when it changed since. */
    fun undo(entry: UndoEntry, open: (String) -> RandomAccessIo) {
        open(entry.uri).use { Undo.apply(it, fileOf(entry)) }
        forget(entry)
    }

    @Synchronized fun forget(entry: UndoEntry) {
        fileOf(entry).delete()
        save(load().filter { it.id != entry.id })
    }

    private fun fileOf(e: UndoEntry) = File(dir, e.id + ".undo")

    private fun prune(list: List<UndoEntry>): List<UndoEntry> {
        val keep = mutableListOf<UndoEntry>()
        var bytes = 0L
        for (e in list.sortedByDescending { it.time }) {
            val size = fileOf(e).length()
            if (keep.size < MAX_ENTRIES && now() - e.time < MAX_AGE_MS && bytes + size <= MAX_BYTES) {
                keep += e; bytes += size
            } else {
                fileOf(e).delete()
            }
        }
        return keep
    }

    private fun load(): List<UndoEntry> = runCatching {
        val arr = JSONArray(index.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            UndoEntry(
                id = o.getString("id"),
                uri = o.getString("uri"),
                name = o.getString("name"),
                time = o.getLong("time"),
                fields = o.getString("fields").split(',').filter { it.isNotEmpty() }.map { MetadataField.valueOf(it) }.toSet(),
                batch = o.optString("batch").ifEmpty { null },
            )
        }.filter { fileOf(it).exists() }
    }.getOrDefault(emptyList())

    private fun save(list: List<UndoEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("uri", e.uri).put("name", e.name).put("time", e.time)
                .put("fields", e.fields.joinToString(",") { it.name }).put("batch", e.batch ?: ""))
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 5000
        const val MAX_BYTES = 1L shl 30
    }
}
