package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.Mp4Fixtures.ftyp
import com.noworrieshq.restamp.core.Mp4Fixtures.mdat
import com.noworrieshq.restamp.core.Mp4Fixtures.mediaPayload
import com.noworrieshq.restamp.core.Mp4Fixtures.moov
import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class Mp4MetadataAccessorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val accessor = Mp4MetadataAccessor()
    private val captured = Instant.parse("2026-11-15T01:50:04Z")
    private val edited = Instant.parse("2026-11-15T01:50:40Z")
    private val sydney = GeoLocation(-33.8568, 151.2153)

    private fun read(f: File): MediaMetadata = FileChannelIo.open(f, false).use { accessor.read(it) }

    private fun write(f: File, update: MetadataUpdate, confirm: Set<MetadataField> = emptySet()) =
        FileChannelIo.open(f, true).use { accessor.write(it, update, confirm) }

    private fun assertMediaUntouched(before: Pair<Long, ByteArray>, after: File) {
        val (offset, payload) = Mp4Fixtures.mdatPayload(after)
        assertEquals("mdat must not move", before.first, offset)
        assertArrayEquals(payload, before.second)
    }

    @Test fun readsCreationTimeAndLocation() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, moov(captured.epochSecond, listOf(Mp4Fixtures.xyz("-33.8568+151.2153/"))), mdat)
        val m = read(f)
        assertEquals(captured, m.dateTaken!!.instant)
        assertEquals(-33.8568, m.location!!.latitude, 1e-9)
        assertEquals(151.2153, m.location!!.longitude, 1e-9)
        assertFalse(m.locationRedacted)
    }

    @Test fun zeroCreationTimeReadsAsMissing() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(0))
        assertNull(read(f).dateTaken)
    }

    @Test fun detectsAndroidRedactedLocation() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(captured.epochSecond, listOf(Mp4Fixtures.redacted(30))))
        val m = read(f)
        assertNull(m.location)
        assertTrue(m.locationRedacted)
    }

    @Test fun fillsRedactedSlotInPlace() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, moov(captured.epochSecond, listOf(Mp4Fixtures.redacted(30))), mdat)
        val size = f.length()
        val media = Mp4Fixtures.mdatPayload(f)
        val result = write(f, MetadataUpdate(location = sydney))
        assertEquals(setOf(MetadataField.LOCATION), result.written)
        assertEquals(size, f.length())
        assertMediaUntouched(media, f)
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
        assertFalse(read(f).locationRedacted)
    }

    @Test fun addsLocationWhenMoovIsLast() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(edited.epochSecond))
        val media = Mp4Fixtures.mdatPayload(f)
        write(f, MetadataUpdate(location = sydney))
        assertMediaUntouched(media, f)
        assertEquals(listOf("ftyp", "mdat", "moov"), Mp4Fixtures.topLevelTypes(f))
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
        assertEquals(edited, read(f).dateTaken!!.instant) // untouched
    }

    @Test fun addsLocationIntoFollowingFreeSpace() {
        // Layout of the Google Photos edited clip: ftyp, moov, free(253), mdat.
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, moov(edited.epochSecond), Mp4Fixtures.box("free", ByteArray(245)), mdat)
        val size = f.length()
        val media = Mp4Fixtures.mdatPayload(f)
        write(f, MetadataUpdate(location = sydney))
        assertEquals(size, f.length())
        assertMediaUntouched(media, f)
        assertEquals(listOf("ftyp", "moov", "free", "mdat"), Mp4Fixtures.topLevelTypes(f))
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
    }

    @Test fun relocatesMoovWhenThereIsNoRoom() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, moov(edited.epochSecond), mdat)
        val media = Mp4Fixtures.mdatPayload(f)
        write(f, MetadataUpdate(location = sydney))
        assertMediaUntouched(media, f)
        assertEquals(listOf("ftyp", "free", "mdat", "moov"), Mp4Fixtures.topLevelTypes(f))
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
    }

    @Test fun relocationFixesOpenEndedMdat() {
        val openMdat = Mp4Fixtures.box("mdat", mediaPayload).also { it.fill(0, 0, 4) }
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, moov(edited.epochSecond), openMdat)
        write(f, MetadataUpdate(location = sydney))
        assertEquals(listOf("ftyp", "free", "mdat", "moov"), Mp4Fixtures.topLevelTypes(f))
        assertArrayEquals(mediaPayload, Mp4Fixtures.mdatPayload(f).second)
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
    }

    @Test fun refusesToOverwriteDateWithoutConfirmation() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(edited.epochSecond))
        val before = f.readBytes()
        val e = assertThrows(OverwriteNotConfirmedException::class.java) {
            write(f, MetadataUpdate(dateTaken = DateTaken.ofInstant(captured), location = sydney))
        }
        assertEquals(setOf(MetadataField.DATE_TAKEN), e.fields)
        assertArrayEquals("nothing may be written, not even the safe field", before, f.readBytes())
    }

    @Test fun overwritesDateWhenConfirmed() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(edited.epochSecond))
        val result = write(f, MetadataUpdate(dateTaken = DateTaken.ofInstant(captured)), setOf(MetadataField.DATE_TAKEN))
        assertEquals(setOf(MetadataField.DATE_TAKEN), result.written)
        assertEquals(captured, read(f).dateTaken!!.instant)
    }

    @Test fun refusesToOverwriteDifferentLocation() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(edited.epochSecond, listOf(Mp4Fixtures.xyz("+51.5007-000.1246/"))))
        assertThrows(OverwriteNotConfirmedException::class.java) { write(f, MetadataUpdate(location = sydney)) }
        write(f, MetadataUpdate(location = sydney), setOf(MetadataField.LOCATION))
        assertTrue(read(f).location!!.isSamePlaceAs(sydney))
        // The old tag is replaced, not duplicated.
        assertEquals(1, Regex("©xyz").findAll(String(f.readBytes(), Charsets.ISO_8859_1)).count())
    }

    @Test fun matchingValuesAreLeftAlone() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(captured.epochSecond, listOf(Mp4Fixtures.xyz("-33.8568+151.2153/"))))
        val before = f.readBytes()
        val result = write(f, MetadataUpdate(DateTaken.ofInstant(captured), sydney))
        assertTrue(result.written.isEmpty())
        assertEquals(setOf(MetadataField.DATE_TAKEN, MetadataField.LOCATION), result.alreadyMatching)
        assertArrayEquals(before, f.readBytes())
    }

    @Test fun writesDateIntoEveryTimeBox() {
        val f = Mp4Fixtures.write(tmp.root, "a.mp4", ftyp, mdat, moov(0))
        write(f, MetadataUpdate(dateTaken = DateTaken.ofInstant(captured)))
        val expected = captured.epochSecond + Mp4Fixtures.EPOCH_1904
        val bytes = f.readBytes()
        val s = String(bytes, Charsets.ISO_8859_1)
        for (type in listOf("mvhd", "tkhd", "mdhd")) {
            val p = s.indexOf(type) + 4 + 4
            val v = java.nio.ByteBuffer.wrap(bytes, p, 4).int.toLong() and 0xFFFFFFFFL
            assertEquals(type, expected, v)
        }
    }

    @Test fun rejectsNonMp4() {
        val f = File(tmp.root, "x.mp4").apply { writeBytes(ByteArray(100)) }
        assertThrows(UnsupportedMediaException::class.java) { read(f) }
    }
}
