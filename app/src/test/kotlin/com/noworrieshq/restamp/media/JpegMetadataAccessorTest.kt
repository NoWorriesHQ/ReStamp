package com.noworrieshq.restamp.media

import androidx.exifinterface.media.ExifInterface
import com.noworrieshq.restamp.core.OverwriteNotConfirmedException
import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JpegMetadataAccessorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sydneyZone = ZoneId.of("Australia/Sydney")
    private val accessor by lazy { JpegMetadataAccessor(tmp.newFolder("cache")) { sydneyZone } }
    private val sydney = GeoLocation(-33.8568, 151.2153, 12.0)

    private fun plainJpeg(): File =
        File(tmp.root, "photo.jpg").apply { writeBytes(javaClass.getResourceAsStream("/plain.jpg")!!.readBytes()) }

    @Test fun plainJpegHasNoMetadata() {
        val m = FileChannelIo.open(plainJpeg(), false).use { accessor.read(it) }
        assertNull(m.dateTaken)
        assertNull(m.location)
    }

    @Test fun addsDateAndGps() {
        val f = plainJpeg()
        val date = DateTaken(LocalDateTime.of(2026, 11, 15, 12, 50, 1, 818_000_000), ZoneOffset.ofHours(11))
        FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(date, sydney)) }

        val exif = ExifInterface(f)
        assertEquals("2026:11:15 12:50:01", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+11:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("818", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))

        val m = FileChannelIo.open(f, false).use { accessor.read(it) }
        assertEquals(date.instant, m.dateTaken!!.instant)
        assertTrue(m.location!!.isSamePlaceAs(sydney))
        assertEquals(12.0, m.location!!.altitudeMeters!!, 0.01)
    }

    @Test fun utcDateFromVideoIsStoredAsLocalTime() {
        val f = plainJpeg()
        val fromMp4 = DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:01Z"))
        FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(dateTaken = fromMp4)) }
        val exif = ExifInterface(f)
        assertEquals("2026:11:15 12:50:01", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+11:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
    }

    @Test fun neverOverwritesWithoutConfirmation() {
        val f = plainJpeg()
        FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(location = sydney)) }
        val before = f.readBytes()
        val london = GeoLocation(51.5007, -0.1246)
        assertThrows(OverwriteNotConfirmedException::class.java) {
            FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(location = london)) }
        }
        assertArrayEquals(before, f.readBytes())

        FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(location = london), setOf(MetadataField.LOCATION)) }
        val m = FileChannelIo.open(f, false).use { accessor.read(it) }
        assertTrue(m.location!!.isSamePlaceAs(london))
    }

    @Test fun imageDataSurvives() {
        val f = plainJpeg()
        FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(location = sydney)) }
        val bytes = f.readBytes()
        assertEquals(0xFF.toByte(), bytes[0]); assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xD9.toByte(), bytes.last()) // ends cleanly, no leftover tail
    }
}
