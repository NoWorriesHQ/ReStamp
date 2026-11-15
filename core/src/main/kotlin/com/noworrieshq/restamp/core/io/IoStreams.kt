package com.noworrieshq.restamp.core.io

import java.io.InputStream
import java.io.OutputStream

/** Streams a [RandomAccessIo] from the start without loading it all into memory. */
class IoInputStream(private val io: RandomAccessIo) : InputStream() {
    private var position = 0L

    override fun read(): Int {
        if (position >= io.size) return -1
        return io.read(position++, 1)[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val remaining = io.size - position
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        io.read(position, n).copyInto(b, off)
        position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = minOf(n, io.size - position).coerceAtLeast(0)
        position += skipped
        return skipped
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), io.size - position).toInt()
}

/** Copies all bytes of [io] to [out]. */
fun RandomAccessIo.copyTo(out: OutputStream, bufferSize: Int = 1 shl 20) {
    var pos = 0L
    val total = size
    while (pos < total) {
        val n = minOf(bufferSize.toLong(), total - pos).toInt()
        out.write(read(pos, n))
        pos += n
    }
}

/** Replaces the whole content of [io] with [input], then trims any leftover tail. */
fun RandomAccessIo.replaceWith(input: InputStream, bufferSize: Int = 1 shl 20) {
    val buffer = ByteArray(bufferSize)
    var pos = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        write(pos, if (n == buffer.size) buffer else buffer.copyOf(n))
        pos += n
    }
    truncate(pos)
    sync()
}
