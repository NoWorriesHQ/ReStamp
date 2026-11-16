package com.noworrieshq.restamp.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.noworrieshq.restamp.core.MetadataEngine
import com.noworrieshq.restamp.core.OverwriteGuard
import com.noworrieshq.restamp.core.WriteResult
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.undo.JournalingIo
import java.io.File
import java.io.FileNotFoundException

/** Opens picked files and runs the engine on them. */
class MediaFiles(private val context: Context) {
    val engine = MetadataEngine(jpeg = JpegMetadataAccessor(context.cacheDir))

    fun inspect(uri: Uri): MetadataEngine.Report = openForRead(uri).use { engine.inspect(it) }

    /** Every change made through [write], so it can be taken back. */
    val undoLog = UndoLog(File(context.filesDir, "undo"))

    /**
     * Writes tags and keeps an undo record. [batch] groups the files of one bulk copy.
     * Returns the result and the undo entry, which is null when nothing changed.
     */
    fun write(uri: Uri, name: String, update: MetadataUpdate, confirmed: Set<MetadataField>, batch: String? = null): Pair<WriteResult, UndoEntry?> {
        // Check against the unredacted view first: a file opened for writing may hide its GPS,
        // and that hidden GPS must still count as "already has a location".
        OverwriteGuard.effectiveUpdate(inspect(uri).metadata, update, confirmed)
        val undoFile = undoLog.newFile()
        val journal = File(context.cacheDir, undoFile.name + ".journal")
        try {
            val (result, changed) = JournalingIo(openForWrite(uri.toString()), journal).use { io ->
                val r = engine.write(io, update, confirmed)
                if (io.changed) io.finish(undoFile)
                r to io.changed
            }
            val entry = if (changed) undoLog.add(undoFile, uri.toString(), name, result.written, batch) else null
            return result to entry
        } catch (e: Exception) {
            undoFile.delete()
            throw e
        } finally {
            journal.delete()
        }
    }

    /** Takes back one change. Throws UndoConflictException if the file changed since. */
    fun undo(entry: UndoEntry) = undoLog.undo(entry, ::openForWrite)

    private fun openForWrite(uri: String): ParcelFdIo {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "rw")
            ?: throw FileNotFoundException("Can't open $uri for writing")
        return ParcelFdIo(pfd)
    }

    /**
     * Opens the unredacted file when possible. Android hides GPS unless the app holds
     * ACCESS_MEDIA_LOCATION and asks for the original through MediaStore.
     */
    private fun openForRead(uri: Uri): ParcelFdIo {
        val resolver = context.contentResolver
        val original = runCatching {
            val mediaUri = if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(context, uri)
            mediaUri?.let { MediaStore.setRequireOriginal(it) }
        }.getOrNull()
        val pfd = original?.let { runCatching { resolver.openFileDescriptor(it, "r") }.getOrNull() }
            ?: resolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Can't open $uri")
        return ParcelFdIo(pfd)
    }
}
