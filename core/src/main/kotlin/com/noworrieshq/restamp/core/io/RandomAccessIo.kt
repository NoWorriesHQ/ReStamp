package com.noworrieshq.restamp.core.io

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Minimal random-access file API. The Android app backs it with a ParcelFileDescriptor,
 * and tests back it with a local file.
 */
interface RandomAccessIo : Closeable {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
    fun write(position: Long, data: ByteArray)
    fun truncate(newSize: Long)
    /** Flushes written bytes to storage. */
    fun sync()
}

class FileChannelIo(private val channel: FileChannel) : RandomAccessIo {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var pos = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n < 0) throw IOException("Unexpected end of file at $pos")
            pos += n
        }
        return buffer.array()
    }

    override fun write(position: Long, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        var pos = position
        while (buffer.hasRemaining()) pos += channel.write(buffer, pos)
    }

    override fun truncate(newSize: Long) {
        channel.truncate(newSize)
    }

    override fun sync() = channel.force(true)

    override fun close() = channel.close()

    companion object {
        fun open(file: File, writable: Boolean): FileChannelIo =
            FileChannelIo(RandomAccessFile(file, if (writable) "rw" else "r").channel)
    }
}
