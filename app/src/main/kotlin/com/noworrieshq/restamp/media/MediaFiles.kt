package com.noworrieshq.restamp.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.noworrieshq.restamp.core.MetadataEngine
import com.noworrieshq.restamp.core.OverwriteGuard
import com.noworrieshq.restamp.core.WriteResult
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import java.io.FileNotFoundException

/** Opens picked files and runs the engine on them. */
class MediaFiles(private val context: Context) {
    val engine = MetadataEngine(jpeg = JpegMetadataAccessor(context.cacheDir))

    fun inspect(uri: Uri): MetadataEngine.Report = openForRead(uri).use { engine.inspect(it) }

    fun write(uri: Uri, update: MetadataUpdate, confirmed: Set<MetadataField>): WriteResult {
        // Check against the unredacted view first: a file opened for writing may hide its GPS,
        // and that hidden GPS must still count as "already has a location".
        OverwriteGuard.effectiveUpdate(inspect(uri).metadata, update, confirmed)
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw FileNotFoundException("Can't open $uri for writing")
        return ParcelFdIo(pfd).use { engine.write(it, update, confirmed) }
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
