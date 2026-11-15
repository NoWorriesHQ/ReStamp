package com.noworrieshq.restamp.core.mp4

import com.noworrieshq.restamp.core.MalformedMediaException
import com.noworrieshq.restamp.core.io.RandomAccessIo

/** One ISO BMFF box. Offsets are absolute in the file, or relative to a buffer for in-memory boxes. */
data class Box(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    /** True when the header's size field was 0, meaning "until end of file". */
    val extendsToEof: Boolean = false,
) {
    val payloadOffset: Long get() = offset + headerSize
    val end: Long get() = offset + size
}

internal object BoxParser {
    /** Lists the boxes between [start] and [end] of a file. */
    fun topLevel(io: RandomAccessIo): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = 0L
        val end = io.size
        while (pos + 8 <= end) {
            val header = io.read(pos, minOf(16L, end - pos).toInt())
            val box = parseHeader(header, 0, pos, end) ?: break
            boxes += box
            if (box.extendsToEof) break
            pos = box.end
        }
        return boxes
    }

    /** Lists child boxes of an in-memory buffer between [start] and [end]. */
    fun children(buf: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val box = parseHeader(buf, pos, pos.toLong(), end.toLong()) ?: break
            if (box.end > end) throw MalformedMediaException("Box ${box.type} at $pos overruns its parent")
            boxes += box
            pos = box.end.toInt()
        }
        return boxes
    }

    /** Parses a header at [index] of [buf]; [absOffset] is where that header sits. */
    private fun parseHeader(buf: ByteArray, index: Int, absOffset: Long, containerEnd: Long): Box? {
        val size32 = u32(buf, index)
        val type = fourCc(buf, index + 4)
        return when (size32) {
            0L -> Box(type, absOffset, 8, containerEnd - absOffset, extendsToEof = true)
            1L -> {
                if (index + 16 > buf.size) throw MalformedMediaException("Truncated 64-bit box header")
                val size64 = u64(buf, index + 8)
                if (size64 < 16 || absOffset + size64 > containerEnd) {
                    throw MalformedMediaException("Bad size $size64 for box $type at $absOffset")
                }
                Box(type, absOffset, 16, size64)
            }
            in 2L..7L -> throw MalformedMediaException("Bad size $size32 for box $type at $absOffset")
            else -> {
                if (absOffset + size32 > containerEnd) {
                    throw MalformedMediaException("Box $type at $absOffset runs past the end of its container")
                }
                Box(type, absOffset, 8, size32)
            }
        }
    }

    fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)

    fun putU32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    fun putU64(b: ByteArray, i: Int, v: Long) {
        putU32(b, i, v ushr 32); putU32(b, i + 4, v and 0xFFFFFFFFL)
    }

    fun fourCc(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.ISO_8859_1)

    fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        putU32(out, 0, out.size.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    fun freeBox(size: Int): ByteArray {
        require(size >= 8)
        val out = ByteArray(size)
        putU32(out, 0, size.toLong())
        "free".toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        return out
    }
}
