package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.io.RandomAccessIo
import com.noworrieshq.restamp.core.jpeg.JpegInspector
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MediaType
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4Inspector
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor

/** Picks the right reader/writer from the file's own bytes. */
class MetadataEngine(
    private val jpeg: MetadataAccessor,
    private val mp4: MetadataAccessor = Mp4MetadataAccessor(),
) {
    data class Report(
        val type: MediaType,
        val metadata: MediaMetadata,
        /** Signed by the camera (C2PA). Writing to it would break that signature. */
        val hasContentCredentials: Boolean,
    )

    fun inspect(io: RandomAccessIo): Report = when (val type = MediaType.sniff(io)) {
        MediaType.JPEG -> Report(type, jpeg.read(io), JpegInspector.hasContentCredentials(io))
        MediaType.MP4 -> Report(type, mp4.read(io), Mp4Inspector.hasContentCredentials(io))
        MediaType.UNSUPPORTED -> throw UnsupportedMediaException("Only JPEG and MP4 files are supported")
    }

    fun write(io: RandomAccessIo, update: MetadataUpdate, confirmedOverwrites: Set<MetadataField>): WriteResult =
        when (MediaType.sniff(io)) {
            MediaType.JPEG -> jpeg.write(io, update, confirmedOverwrites)
            MediaType.MP4 -> mp4.write(io, update, confirmedOverwrites)
            MediaType.UNSUPPORTED -> throw UnsupportedMediaException("Only JPEG and MP4 files are supported")
        }
}
