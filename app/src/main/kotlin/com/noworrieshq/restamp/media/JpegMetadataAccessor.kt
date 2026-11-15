package com.noworrieshq.restamp.media

import androidx.exifinterface.media.ExifInterface
import com.noworrieshq.restamp.core.MetadataAccessor
import com.noworrieshq.restamp.core.OverwriteGuard
import com.noworrieshq.restamp.core.WriteResult
import com.noworrieshq.restamp.core.io.IoInputStream
import com.noworrieshq.restamp.core.io.RandomAccessIo
import com.noworrieshq.restamp.core.io.copyTo
import com.noworrieshq.restamp.core.io.replaceWith
import com.noworrieshq.restamp.core.jpeg.ExifDateCodec
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * JPEG date taken and GPS through AndroidX ExifInterface.
 *
 * Writing edits a temporary copy and then streams it back, so the result is exact even when
 * the EXIF block changes size. Pixel motion photos stay intact, because their embedded video
 * is located relative to the end of the file.
 */
class JpegMetadataAccessor(
    private val tempDir: File,
    private val localZone: () -> ZoneId = { ZoneId.systemDefault() },
) : MetadataAccessor {

    override fun read(io: RandomAccessIo): MediaMetadata {
        val exif = IoInputStream(io).buffered().use { ExifInterface(it) }
        return readFrom(exif)
    }

    override fun write(
        io: RandomAccessIo,
        update: MetadataUpdate,
        confirmedOverwrites: Set<MetadataField>,
    ): WriteResult {
        val (effective, result) = OverwriteGuard.effectiveUpdate(read(io), update, confirmedOverwrites)
        if (effective.isEmpty) return result

        val temp = File.createTempFile("edit", ".jpg", tempDir)
        try {
            temp.outputStream().buffered().use { io.copyTo(it) }
            val exif = ExifInterface(temp)
            effective.dateTaken?.let { writeDate(exif, it) }
            effective.location?.let { writeLocation(exif, it) }
            exif.saveAttributes()
            temp.inputStream().buffered().use { io.replaceWith(it) }
        } finally {
            temp.delete()
        }
        return result
    }

    private fun readFrom(exif: ExifInterface): MediaMetadata {
        val date = ExifDateCodec.decode(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
        )
        val latLong = exif.latLong
        val location = latLong?.let {
            val alt = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
            GeoLocation(it[0], it[1], alt)
        }
        return MediaMetadata(date, location)
    }

    private fun writeDate(exif: ExifInterface, date: DateTaken) {
        // MP4 dates arrive in UTC; show them as local wall-clock time like the camera does.
        val local = if (date.utcOffset == ZoneOffset.UTC) {
            val instant = date.instant!!
            DateTaken.ofInstant(instant, localZone().rules.getOffset(instant))
        } else {
            date
        }
        val tags = ExifDateCodec.encode(local)
        for ((dateTag, offsetTag, subSecTag) in listOf(
            Triple(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            Triple(ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED),
        )) {
            exif.setAttribute(dateTag, tags.dateTime)
            exif.setAttribute(offsetTag, tags.offset)
            exif.setAttribute(subSecTag, tags.subSec)
        }
    }

    private fun writeLocation(exif: ExifInterface, location: GeoLocation) {
        exif.setLatLong(location.latitude, location.longitude)
        location.altitudeMeters?.let { exif.setAltitude(it) }
    }
}
