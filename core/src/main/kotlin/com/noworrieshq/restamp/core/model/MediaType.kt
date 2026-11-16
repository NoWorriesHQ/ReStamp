package com.noworrieshq.restamp.core.model

import com.noworrieshq.restamp.core.io.RandomAccessIo

enum class MediaType {
    JPEG, MP4, UNSUPPORTED;

    companion object {
        /** Boxes an ftyp-less QuickTime movie can start with. */
        val QUICKTIME_FIRST_BOXES = setOf("moov", "mdat", "wide", "free", "skip", "pnot")

        /** Detects the container from its first bytes, so a wrong file extension doesn't matter. */
        fun sniff(io: RandomAccessIo): MediaType {
            if (io.size < 12) return UNSUPPORTED
            val head = io.read(0, 12)
            if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return JPEG
            val firstBox = String(head, 4, 4, Charsets.ISO_8859_1)
            if (firstBox == "ftyp") return MP4
            // Older QuickTime files (and some Live Photo videos) have no ftyp and start straight
            // with one of these.
            if (firstBox in QUICKTIME_FIRST_BOXES) return MP4
            return UNSUPPORTED
        }
    }
}
