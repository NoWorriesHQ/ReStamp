package com.noworrieshq.restamp.core.undo

import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.io.replaceWith
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.random.Random

class JournalingIoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val place = GeoLocation(-17.7334, 168.3273)
    private val date = DateTaken.ofInstant(Instant.parse("2026-11-16T09:00:00Z"))

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/formats/$name") ?: error("missing fixture $name")
        return File(tmp.root, name).apply { outputStream().use { stream.use { s -> s.copyTo(it) } } }
    }

    /** Runs [change] through a journal, checks the undo file is small, undoes, and compares. */
    private fun roundTrip(f: File, maxUndoBytes: Long, change: (JournalingIo) -> Unit) {
        val before = f.readBytes()
        val undo = File(tmp.root, f.name + ".undo")
        FileChannelIo.open(f, true).use { raw ->
            val j = JournalingIo(raw, File(tmp.root, f.name + ".journal"))
            change(j)
            assertTrue("nothing changed", j.changed)
            j.finish(undo)
        }
        assertFalse("file did not change", before.contentEquals(f.readBytes()))
        assertTrue("undo file is ${undo.length()} bytes", undo.length() <= maxUndoBytes)
        FileChannelIo.open(f, true).use { Undo.apply(it, undo) }
        assertArrayEquals("${f.name} not restored", before, f.readBytes())
    }

    private fun mp4Write(j: JournalingIo, update: MetadataUpdate) =
        Mp4MetadataAccessor().write(j, update, MetadataField.entries.toSet())

    @Test fun undoesMp4EditsInPlaceAndWhenMoovMoves() {
        for (name in listOf("moov-at-end.mp4", "moov-first.mp4", "quicktime.mov", "tagged.mp4", "apple-keys.mov", "itunes-ilst-location.mp4", "no-ftyp.mov")) {
            roundTrip(fixture(name), 64 * 1024) { mp4Write(it, MetadataUpdate(date, place)) }
        }
    }

    @Test fun undoesDateOnlyChangeOnFragmentedFile() {
        roundTrip(fixture("fragmented.mp4"), 16 * 1024) { mp4Write(it, MetadataUpdate(dateTaken = date)) }
    }

    /** Like a JPEG rewrite: new header of another size, then the same picture and trailer. */
    @Test fun wholeRewriteKeepsOnlyTheChangedHeader() {
        val header = Random(1).nextBytes(20_000)
        val body = Random(2).nextBytes(3_000_000)
        for (newHeaderSize in listOf(19_000, 20_000, 23_456)) {
            val f = File(tmp.root, "photo$newHeaderSize.jpg").apply { writeBytes(header + body) }
            val newHeader = header.copyOf(newHeaderSize).also { for (i in 100 until minOf(400, it.size)) it[i] = 7 }
            roundTrip(f, 25_000) { it.replaceWith((newHeader + body).inputStream()) }
        }
    }

    @Test fun refusesWhenFileChangedAfterwards() {
        val f = fixture("moov-at-end.mp4")
        val undo = File(tmp.root, "u")
        FileChannelIo.open(f, true).use { raw ->
            val j = JournalingIo(raw, File(tmp.root, "j"))
            mp4Write(j, MetadataUpdate(date, place))
            j.finish(undo)
        }
        // Something else writes a new date afterwards.
        FileChannelIo.open(f, true).use { Mp4MetadataAccessor().write(it, MetadataUpdate(dateTaken = DateTaken.ofInstant(Instant.parse("2020-01-01T00:00:00Z"))), MetadataField.entries.toSet()) }
        val after = f.readBytes()
        val e = runCatching { FileChannelIo.open(f, true).use { Undo.apply(it, undo) } }.exceptionOrNull()
        assertTrue("expected a conflict, got $e", e is UndoConflictException)
        assertArrayEquals(after, f.readBytes())
    }

    @Test fun truncateAndRegrowIsRestored() {
        val f = File(tmp.root, "t.bin").apply { writeBytes(Random(3).nextBytes(10_000)) }
        roundTrip(f, 20_000) { j ->
            j.truncate(4_000)
            j.write(4_000, ByteArray(500) { 1 })
            j.write(10, byteArrayOf(9, 9, 9))
        }
    }
}
