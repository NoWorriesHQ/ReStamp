package com.noworrieshq.restamp.core.model

import com.noworrieshq.restamp.core.io.RandomAccessIo

enum class MediaType {
    JPEG, MP4, UNSUPPORTED;

    companion object {
        /** Detects the container from its first bytes, so a wrong file extension doesn't matter. */
        fun sniff(io: RandomAccessIo): MediaType {
            if (io.size < 12) return UNSUPPORTED
            val head = io.read(0, 12)
            if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return JPEG
            if (String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") return MP4
            return UNSUPPORTED
        }
    }
}
