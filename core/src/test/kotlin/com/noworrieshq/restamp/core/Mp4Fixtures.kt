package com.noworrieshq.restamp.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Builds small but structurally real MP4 files for tests. */
object Mp4Fixtures {
    const val EPOCH_1904 = 2_082_844_800L

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { acc, b -> acc + b }
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
    }

    /** A full box with version 0 and 32-bit creation/modification times. */
    private fun timeBox(type: String, unixSeconds: Long, extra: Int): ByteArray {
        val secs = if (unixSeconds == 0L) 0L else unixSeconds + EPOCH_1904
        val b = ByteBuffer.allocate(4 + 8 + extra).putInt(0).putInt(secs.toInt()).putInt(secs.toInt())
        return box(type, b.array())
    }

    fun xyz(text: String): ByteArray {
        val t = text.toByteArray()
        return box("©xyz", ByteBuffer.allocate(4 + t.size).putShort(t.size.toShort()).putShort(0x15C7).put(t).array())
    }

    /** What Android's redaction leaves behind: a zero-typed, zero-filled box of the same size. */
    fun redacted(size: Int): ByteArray = ByteArray(size).also { ByteBuffer.wrap(it).putInt(size) }

    fun moov(unixSeconds: Long, udtaChildren: List<ByteArray>? = null): ByteArray {
        val mvhd = timeBox("mvhd", unixSeconds, 88)
        val tkhd = timeBox("tkhd", unixSeconds, 72)
        val mdhd = timeBox("mdhd", unixSeconds, 12)
        val trak = box("trak", tkhd, box("mdia", mdhd))
        val parts = mutableListOf(mvhd)
        udtaChildren?.let { parts += box("udta", *it.toTypedArray()) }
        parts += trak
        return box("moov", *parts.toTypedArray())
    }

    val ftyp = box("ftyp", "mp42".toByteArray(), ByteArray(4), "isommp42".toByteArray())

    /** Recognisable payload so tests can prove media bytes never move or change. */
    val mediaPayload = ByteArray(5000) { (it * 31 % 251).toByte() }
    val mdat = box("mdat", mediaPayload)

    fun write(dir: File, name: String, vararg boxes: ByteArray): File {
        val out = ByteArrayOutputStream()
        boxes.forEach { out.write(it) }
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }

    /** Offset of the mdat payload, found by scanning top-level boxes. */
    fun mdatPayload(file: File): Pair<Long, ByteArray> {
        val bytes = file.readBytes()
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val (size, header) = sizeAt(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "mdat") return (pos + header.toLong()) to bytes.copyOfRange(pos + header, (pos + size).toInt())
            pos += size.toInt()
        }
        error("no mdat")
    }

    fun topLevelTypes(file: File): List<String> {
        val bytes = file.readBytes()
        val types = mutableListOf<String>()
        var pos = 0
        while (pos + 8 <= bytes.size) {
            types += String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if ((ByteBuffer.wrap(bytes, pos, 4).int) == 0) break
            pos += sizeAt(bytes, pos).first.toInt()
        }
        return types
    }

    /** Box size and header length at [pos], handling 64-bit and open-ended sizes. */
    private fun sizeAt(bytes: ByteArray, pos: Int): Pair<Long, Int> {
        val size32 = ByteBuffer.wrap(bytes, pos, 4).int.toLong() and 0xFFFFFFFFL
        return when (size32) {
            0L -> (bytes.size - pos).toLong() to 8
            1L -> ByteBuffer.wrap(bytes, pos + 8, 8).long to 16
            else -> size32 to 8
        }
    }
}
