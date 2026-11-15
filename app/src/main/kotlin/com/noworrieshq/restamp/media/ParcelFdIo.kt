package com.noworrieshq.restamp.media

import android.os.ParcelFileDescriptor
import android.system.Os
import com.noworrieshq.restamp.core.io.RandomAccessIo
import java.io.IOException

/** [RandomAccessIo] over a file descriptor from ContentResolver, using positional reads/writes. */
class ParcelFdIo(private val pfd: ParcelFileDescriptor) : RandomAccessIo {
    private val fd get() = pfd.fileDescriptor

    override val size: Long get() = Os.fstat(fd).st_size

    override fun read(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = Os.pread(fd, out, done, length - done, position + done)
            if (n <= 0) throw IOException("Unexpected end of file at ${position + done}")
            done += n
        }
        return out
    }

    override fun write(position: Long, data: ByteArray) {
        var done = 0
        while (done < data.size) {
            done += Os.pwrite(fd, data, done, data.size - done, position + done)
        }
    }

    override fun truncate(newSize: Long) = Os.ftruncate(fd, newSize)

    override fun sync() = Os.fsync(fd)

    override fun close() = pfd.close()
}
