package com.noworrieshq.restamp.media

/** Byte-level JPEG helpers shared by the JPEG tests. */

internal fun indexOf(hay: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..hay.size - needle.size) {
        for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** The second image an MPF (CIPA DC-007) index points to, or null when there's no index. */
internal fun secondImage(bytes: ByteArray): ByteArray? {
    val mpf = indexOf(bytes, byteArrayOf(0x4D, 0x50, 0x46, 0x00))
    if (mpf < 0) return null
    val base = mpf + 4
    val big = bytes[base] == 0x4D.toByte()
    fun u16(p: Int) = if (big) ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
        else ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
    fun u32(p: Int) = if (big) (u16(p).toLong() shl 16) or u16(p + 2).toLong() else (u16(p + 2).toLong() shl 16) or u16(p).toLong()
    val ifd = base + u32(base + 4).toInt()
    val count = u16(ifd)
    for (k in 0 until count) {
        val e = ifd + 2 + k * 12
        if (u16(e) == 0xB002) {
            val entries = base + u32(e + 8).toInt()
            val size = u32(entries + 16 + 4).toInt()
            val offset = u32(entries + 16 + 8).toInt()
            val start = base + offset
            if (start + size > bytes.size || bytes[start] != 0xFF.toByte() || bytes[start + 1] != 0xD8.toByte()) {
                return byteArrayOf()
            }
            return bytes.copyOfRange(start, start + size)
        }
    }
    return null
}
