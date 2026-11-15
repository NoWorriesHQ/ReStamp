package com.noworrieshq.restamp.core.jpeg

import com.noworrieshq.restamp.core.io.RandomAccessIo

/** Light JPEG segment scan for things ExifInterface doesn't report. */
object JpegInspector {
    /**
     * True when the file carries a C2PA "Content Credentials" manifest (JUMBF in APP11).
     * Pixel signs its originals this way; editing tags in a signed file invalidates that
     * signature, so the UI can warn first.
     */
    fun hasContentCredentials(io: RandomAccessIo): Boolean {
        var pos = 2L
        while (pos + 4 <= io.size) {
            val h = io.read(pos, 4)
            if (h[0] != 0xFF.toByte()) return false
            val marker = h[1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return false // start of scan / end of image
            val len = ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (len < 2) return false
            if (marker == 0xEB && len >= 4) {
                val body = io.read(pos + 4, minOf(len - 2, 64))
                if (String(body, Charsets.ISO_8859_1).contains("c2pa")) return true
            }
            pos += 2 + len
        }
        return false
    }
}
