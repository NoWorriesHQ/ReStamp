package com.noworrieshq.restamp.core.undo

import com.noworrieshq.restamp.core.io.RandomAccessIo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Wraps a file while the engine changes it and saves every original byte just before it is
 * overwritten or cut off. [finish] turns that into a compact undo file, and [Undo.apply] puts
 * the file back exactly as it was.
 *
 * MP4 changes touch a few small boxes, so only those bytes are kept. A JPEG is rewritten whole,
 * but the picture and anything after it only shift, so only the changed header is kept.
 */
class JournalingIo(private val inner: RandomAccessIo, private val scratch: File) : RandomAccessIo {
    val originalSize: Long = inner.size
    private val records = RandomAccessFile(scratch, "rw").apply { setLength(0) }
    private val saved = RangeSet()
    private val touched = RangeSet()

    override val size: Long get() = inner.size
    override fun read(position: Long, length: Int): ByteArray = inner.read(position, length)

    override fun write(position: Long, data: ByteArray) {
        save(position, position + data.size)
        inner.write(position, data)
        touched.add(position, position + data.size)
    }

    override fun truncate(newSize: Long) {
        if (newSize < inner.size) save(newSize, inner.size)
        inner.truncate(newSize)
        touched.add(newSize, newSize)
    }

    override fun sync() {
        records.fd.sync()
        inner.sync()
    }

    override fun close() {
        records.close()
        inner.close()
    }

    /** True once anything in the file was changed. */
    val changed: Boolean get() = !touched.isEmpty() || inner.size != originalSize

    private fun save(from: Long, to: Long) {
        val end = minOf(to, originalSize)
        if (from >= end) return
        for ((a, b) in saved.gaps(from, end)) {
            var pos = a
            while (pos < b) {
                val n = minOf(CHUNK.toLong(), b - pos).toInt()
                val bytes = inner.read(pos, n)
                records.seek(records.length())
                records.writeLong(pos)
                records.writeInt(n)
                records.write(bytes)
                pos += n
            }
            saved.add(a, b)
        }
    }

    /** Writes the undo data to [out]. Call after the engine is done and before [close]. */
    fun finish(out: File) {
        val newSize = inner.size
        val patches = readRecords()
        val sparseCost = patches.sumOf { it.second.size.toLong() }
        val window = if (saved.covers(0, originalSize)) window(patches, newSize) else null
        DataOutputStream(out.outputStream().buffered()).use { o ->
            o.write(MAGIC)
            o.writeLong(originalSize)
            o.writeLong(newSize)
            if (window != null && window.second.size < sparseCost) {
                val (prefix, middle, suffix) = Triple(window.first, window.second, window.third)
                o.writeByte(WINDOW)
                o.write(hashWhole(newSize))
                o.writeLong(prefix)
                o.writeLong(suffix)
                o.writeInt(middle.size)
                o.write(middle)
            } else {
                o.writeByte(SPARSE)
                o.write(hashTouched(patches, newSize))
                o.writeInt(patches.size)
                for ((pos, bytes) in patches) {
                    o.writeLong(pos)
                    o.writeInt(bytes.size)
                    o.write(bytes)
                }
            }
        }
    }

    private fun readRecords(): List<Pair<Long, ByteArray>> {
        val list = mutableListOf<Pair<Long, ByteArray>>()
        records.seek(0)
        while (records.filePointer < records.length()) {
            val pos = records.readLong()
            val bytes = ByteArray(records.readInt())
            records.readFully(bytes)
            list += pos to bytes
        }
        return list
    }

    /**
     * When the whole original was saved (the file was rewritten), finds how much of its start
     * and end are unchanged and keeps only the original bytes in between.
     */
    private fun window(patches: List<Pair<Long, ByteArray>>, newSize: Long): Triple<Long, ByteArray, Long>? {
        if (originalSize > MAX_WINDOW_ORIGINAL) return null
        val old = ByteArray(originalSize.toInt())
        for ((pos, bytes) in patches) bytes.copyInto(old, pos.toInt())
        val limit = minOf(originalSize, newSize)
        var prefix = 0L
        while (prefix < limit) {
            val n = minOf(CHUNK.toLong(), limit - prefix).toInt()
            val now = inner.read(prefix, n)
            var i = 0
            while (i < n && now[i] == old[(prefix + i).toInt()]) i++
            prefix += i
            if (i < n) break
        }
        var suffix = 0L
        val maxSuffix = limit - prefix
        while (suffix < maxSuffix) {
            val n = minOf(CHUNK.toLong(), maxSuffix - suffix).toInt()
            val now = inner.read(newSize - suffix - n, n)
            var i = 0
            while (i < n && now[n - 1 - i] == old[(originalSize - suffix - 1 - i).toInt()]) i++
            suffix += i
            if (i < n) break
        }
        return Triple(prefix, old.copyOfRange(prefix.toInt(), (originalSize - suffix).toInt()), suffix)
    }

    private fun hashWhole(newSize: Long): ByteArray = Undo.hashRange(inner, 0, newSize, MessageDigest.getInstance("SHA-256")).digest()

    private fun hashTouched(patches: List<Pair<Long, ByteArray>>, newSize: Long): ByteArray =
        Undo.sparseHash(inner, patches.map { it.first to it.second.size }, originalSize, newSize)

    companion object {
        internal val MAGIC = "RSUNDO1".toByteArray()
        internal const val SPARSE = 0
        internal const val WINDOW = 1
        internal const val CHUNK = 1 shl 16
        /** Rewritten files bigger than this keep their full original instead (JPEGs are far smaller). */
        private const val MAX_WINDOW_ORIGINAL = 256L shl 20
    }
}

/** Thrown when a file was changed again after ReStamp's edit, so undoing would lose that change. */
class UndoConflictException(message: String) : Exception(message)

object Undo {
    /**
     * Puts the file back as it was before the change recorded in [undoFile]. Refuses, without
     * touching the file, if it no longer matches what ReStamp left.
     */
    fun apply(io: RandomAccessIo, undoFile: File) {
        DataInputStream(undoFile.inputStream().buffered()).use { input ->
            val magic = ByteArray(JournalingIo.MAGIC.size).also { input.readFully(it) }
            require(magic.contentEquals(JournalingIo.MAGIC)) { "Not an undo file" }
            val originalSize = input.readLong()
            val newSize = input.readLong()
            val mode = input.readByte().toInt()
            val hash = ByteArray(32).also { input.readFully(it) }
            if (io.size != newSize) throw UndoConflictException("The file was changed since ReStamp edited it.")
            when (mode) {
                JournalingIo.SPARSE -> {
                    val patches = List(input.readInt()) {
                        val pos = input.readLong()
                        pos to ByteArray(input.readInt()).also { input.readFully(it) }
                    }
                    val now = sparseHash(io, patches.map { it.first to it.second.size }, originalSize, newSize)
                    if (!now.contentEquals(hash)) throw UndoConflictException("The file was changed since ReStamp edited it.")
                    for ((pos, bytes) in patches) io.write(pos, bytes)
                    io.truncate(originalSize)
                }
                JournalingIo.WINDOW -> {
                    val prefix = input.readLong()
                    val suffix = input.readLong()
                    val middle = ByteArray(input.readInt()).also { input.readFully(it) }
                    val now = hashRange(io, 0, newSize, MessageDigest.getInstance("SHA-256")).digest()
                    if (!now.contentEquals(hash)) throw UndoConflictException("The file was changed since ReStamp edited it.")
                    move(io, from = newSize - suffix, to = prefix + middle.size, length = suffix)
                    io.write(prefix, middle)
                    io.truncate(originalSize)
                }
                else -> error("Unknown undo mode $mode")
            }
            io.sync()
        }
    }

    /** Copies [length] bytes inside the file, safely when the two ranges overlap. */
    private fun move(io: RandomAccessIo, from: Long, to: Long, length: Long) {
        if (from == to || length == 0L) return
        val chunk = JournalingIo.CHUNK.toLong()
        if (to > from) {
            var done = 0L
            while (done < length) {
                val n = minOf(chunk, length - done)
                val start = length - done - n
                io.write(to + start, io.read(from + start, n.toInt()))
                done += n
            }
        } else {
            var done = 0L
            while (done < length) {
                val n = minOf(chunk, length - done)
                io.write(to + done, io.read(from + done, n.toInt()))
                done += n
            }
        }
    }

    internal fun hashRange(io: RandomAccessIo, from: Long, to: Long, md: MessageDigest): MessageDigest {
        var pos = from
        while (pos < to) {
            val n = minOf(JournalingIo.CHUNK.toLong(), to - pos).toInt()
            md.update(io.read(pos, n))
            pos += n
        }
        return md
    }

    /** Hash of the bytes ReStamp wrote: the saved ranges as they are now, plus anything added at the end. */
    internal fun sparseHash(io: RandomAccessIo, ranges: List<Pair<Long, Int>>, originalSize: Long, newSize: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(newSize.toString().toByteArray())
        for ((pos, len) in ranges) hashRange(io, pos, minOf(pos + len, newSize), md)
        if (newSize > originalSize) hashRange(io, originalSize, newSize, md)
        return md.digest()
    }
}

/** Sorted, merged set of half-open byte ranges. */
internal class RangeSet {
    private val ranges = mutableListOf<LongArray>()

    fun isEmpty() = ranges.isEmpty()

    fun add(from: Long, to: Long) {
        if (to < from) return
        var a = from
        var b = to
        val it = ranges.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (r[1] >= a && r[0] <= b) { a = minOf(a, r[0]); b = maxOf(b, r[1]); it.remove() }
        }
        ranges += longArrayOf(a, b)
        ranges.sortBy { it[0] }
    }

    /** Parts of [from, to) not yet in the set. */
    fun gaps(from: Long, to: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var pos = from
        for (r in ranges) {
            if (r[1] <= pos) continue
            if (r[0] >= to) break
            if (r[0] > pos) out += pos to r[0]
            pos = maxOf(pos, r[1])
            if (pos >= to) break
        }
        if (pos < to) out += pos to to
        return out
    }

    fun covers(from: Long, to: Long) = gaps(from, to).isEmpty()
}
