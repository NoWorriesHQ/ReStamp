package com.noworrieshq.restamp.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Size
import java.time.Instant

/**
 * Display-only details about a file that aren't tags: the phone's own "modified" date and a
 * thumbnail. Nothing here is ever written.
 */
class FileExtras(private val context: Context) {

    data class Info(val modified: Instant?, val sizeBytes: Long?)

    fun info(uri: Uri): Info = runCatching {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Info(
                modified = if (c.isNull(0)) null else c.getLong(0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                sizeBytes = if (c.isNull(1)) null else c.getLong(1),
            )
        }
    }.getOrNull() ?: Info(null, null)

    /** A small preview of a photo or a frame of a video, or null when none can be made. */
    fun thumbnail(uri: Uri, isVideo: Boolean, sizePx: Int): Bitmap? {
        runCatching { return context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }
        return runCatching {
            if (isVideo) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
                }
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val scale = maxOf(1, maxOf(info.size.width, info.size.height) / sizePx)
                    decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                }
            }
        }.getOrNull()
    }
}
