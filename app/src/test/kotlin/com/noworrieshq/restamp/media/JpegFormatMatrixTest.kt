package com.noworrieshq.restamp.media

import androidx.exifinterface.media.ExifInterface
import com.noworrieshq.restamp.core.OverwriteNotConfirmedException
import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/**
 * JPEG layouts beyond the plain test image: progressive, camera EXIF already present, XMP and
 * ICC profile segments, Pixel motion photos (video appended after the image), and
 * multi-picture files like Ultra HDR (a second JPEG found through the MPF index).
 * Each must get its tags without losing anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JpegFormatMatrixTest {
    @get:Rule val tmp = TemporaryFolder()

    private val accessor by lazy { JpegMetadataAccessor(tmp.newFolder("cache")) { ZoneId.of("Australia/Sydney") } }
    private val sydney = GeoLocation(-33.8568, 151.2153, 12.0)
    private val paris = GeoLocation(48.8584, 2.2945)
    private val taken = DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:01Z"))

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }
    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
    private fun read(f: File): MediaMetadata = FileChannelIo.open(f, false).use { accessor.read(it) }
    private fun write(f: File, u: MetadataUpdate, confirmed: Set<MetadataField> = emptySet()) =
        FileChannelIo.open(f, true).use { accessor.write(it, u, confirmed) }

    private fun assertTagged(f: File) {
        val m = read(f)
        assertEquals(taken.instant, m.dateTaken!!.instant)
        assertTrue(m.location!!.isSamePlaceAs(sydney))
    }

    /** Bytes from start-of-scan to the end of the first image: the compressed picture itself. */
    private fun scanData(bytes: ByteArray): ByteArray {
        var i = 2
        while (i + 4 <= bytes.size) {
            val marker = bytes[i + 1].toInt() and 0xFF
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                var j = i + 2 + len
                while (j + 1 < bytes.size && !(bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte())) j++
                return bytes.copyOfRange(i, j + 2)
            }
            i += 2 + len
        }
        error("no SOS")
    }

    @Test fun progressiveJpeg() {
        val f = file("progressive.jpg", decode(PROGRESSIVE))
        val scan = scanData(f.readBytes())
        assertNull(read(f).dateTaken)
        write(f, MetadataUpdate(taken, sydney))
        assertTagged(f)
        assertArrayEquals(scan, scanData(f.readBytes()))
    }

    @Test fun keepsCameraExifAndOnlyUsesDateTimeOriginal() {
        val f = file("camera.jpg", decode(CAMERA_EXIF_NO_GPS))
        // The file's modify date (DateTime) isn't date taken, so it reads as missing.
        assertNull(read(f).dateTaken)
        write(f, MetadataUpdate(taken, sydney))
        assertTagged(f)
        val exif = ExifInterface(f)
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Pixel 9a", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("2024:10:07 09:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME))
    }

    @Test fun keepsXmpAndColourProfile() {
        val original = decode(XMP_AND_ICC)
        val f = file("xmp.jpg", original)
        write(f, MetadataUpdate(taken, sydney))
        assertTagged(f)
        val after = f.readBytes()
        assertTrue("XMP lost", indexOf(after, "http://ns.adobe.com/xap/1.0/".toByteArray()) >= 0)
        assertTrue("ICC profile lost", indexOf(after, "ICC_PROFILE".toByteArray()) >= 0)
        assertArrayEquals(scanData(original), scanData(after))
    }

    @Test fun motionPhotoKeepsItsVideo() {
        // Pixel motion photos are a JPEG followed by an MP4; the XMP points at it from the end.
        val video = ByteArray(5000) { (it * 31).toByte() }.also { "ftypisom".toByteArray().copyInto(it, 4) }
        val f = file("PXL_motion.MP.jpg", decode(PROGRESSIVE) + video)
        write(f, MetadataUpdate(taken, sydney))
        assertTagged(f)
        val after = f.readBytes()
        assertArrayEquals("video after the image changed", video, after.copyOfRange(after.size - video.size, after.size))
    }

    @Test fun multiPictureFileKeepsSecondImageReachable() {
        // Ultra HDR and other MPF files find the second image (the gain map) by an offset that
        // is relative to the MPF header, so both must move together.
        val original = decode(MPO_TWO_IMAGES)
        val second = secondImage(original)
        assertNotNull("fixture has no MPF index", second)
        val f = file("ultrahdr.jpg", original)
        write(f, MetadataUpdate(taken, sydney))
        assertTagged(f)
        val after = secondImage(f.readBytes())
        assertNotNull("MPF index lost", after)
        assertArrayEquals("second image no longer where MPF says", second, after)
    }

    @Test fun existingDateAndLocationNeedConfirmation() {
        val f = file("tagged.jpg", decode(PROGRESSIVE))
        write(f, MetadataUpdate(taken, sydney))
        val before = f.readBytes()
        val later = DateTaken.ofInstant(Instant.parse("2026-11-16T00:00:00Z"))
        val e = runCatching { write(f, MetadataUpdate(later, paris)) }.exceptionOrNull()
        assertTrue(e is OverwriteNotConfirmedException)
        assertEquals(setOf(MetadataField.DATE_TAKEN, MetadataField.LOCATION), (e as OverwriteNotConfirmedException).fields)
        assertArrayEquals(before, f.readBytes())

        write(f, MetadataUpdate(later, paris), MetadataField.entries.toSet())
        val m = read(f)
        assertEquals(later.instant, m.dateTaken!!.instant)
        assertTrue(m.location!!.isSamePlaceAs(paris))
        // The old altitude must not survive next to the new place.
        assertNull(m.location!!.altitudeMeters)
    }

    private companion object {
        const val PROGRESSIVE = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wgARCAAQABADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAL/xAAVAQEBAAAAAAAAAAAAAAAAAAAEBv/aAAwDAQACEAMQAAABgCpP/8QAFxAAAwEAAAAAAAAAAAAAAAAAAAQFFP/aAAgBAQABBQKYhuKaGEmP4Sm/uP/EABsRAAAHAQAAAAAAAAAAAAAAAAAUJDFRYoGR/9oACAEDAQE/AVBimR1x/8QAFxEAAwEAAAAAAAAAAAAAAAAAABIiYf/aAAgBAgEBPwGV0//EABkQAAIDAQAAAAAAAAAAAAAAABESAAMjQf/aAAgBAQAGPwKzREHDK9Hc8Eszdx0SvNEPTP/EABkQAAEFAAAAAAAAAAAAAAAAABEAAVFh0f/aAAgBAQABPyHdyJtoWbkBbys3IGnlbuRFNC//2gAMAwEAAgADAAAAEP8A/8QAFhEBAQEAAAAAAAAAAAAAAAAA8AAx/9oACAEDAQE/EBAy/8QAGREAAQUAAAAAAAAAAAAAAAAAABExUZHw/9oACAECAQE/ENlmmP/EABkQAAEFAAAAAAAAAAAAAAAAAAARMWFx8P/aAAgBAQABPxDcf4Eu5uP8i1Y3H+Rasbj/AAJdz//Z"
        const val CAMERA_EXIF_NO_GPS = "/9j/4AAQSkZJRgABAQAAAQABAAD/4QBsRXhpZgAATU0AKgAAAAgABAEPAAIAAAAHAAAAPgEQAAIAAAAJAAAARgESAAMAAAABAAYAAAEyAAIAAAAUAAAAUAAAAABHb29nbGUAAFBpeGVsIDlhAAAyMDI0OjEwOjA3IDA5OjAwOjAwAP/bAEMABgQFBgUEBgYFBgcHBggKEAoKCQkKFA4PDBAXFBgYFxQWFhodJR8aGyMcFhYgLCAjJicpKikZHy0wLSgwJSgpKP/bAEMBBwcHCggKEwoKEygaFhooKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKP/AABEIABAAEAMBIgACEQEDEQH/xAAfAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgv/xAC1EAACAQMDAgQDBQUEBAAAAX0BAgMABBEFEiExQQYTUWEHInEUMoGRoQgjQrHBFVLR8CQzYnKCCQoWFxgZGiUmJygpKjQ1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4eLj5OXm5+jp6vHy8/T19vf4+fr/xAAfAQADAQEBAQEBAQEBAAAAAAAAAQIDBAUGBwgJCgv/xAC1EQACAQIEBAMEBwUEBAABAncAAQIDEQQFITEGEkFRB2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4+Tl5ufo6ery8/T19vf4+fr/2gAMAwEAAhEDEQA/AOs8DeEv+Ep+2/6b9l+zbP8Allv3bt3+0Mfd/Wjxz4S/4Rb7F/pv2r7Tv/5ZbNu3b/tHP3v0o8DeLf8AhFvtv+hfavtOz/lrs27d3+yc/e/Sjxz4t/4Sn7F/oX2X7Nv/AOWu/du2/wCyMfd/Wvj/APZfqv8A08+ff7tj4L/YvqX/AE++ff7tv6uf/9k="
        const val XMP_AND_ICC = "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"
        const val MPO_TWO_IMAGES = "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"
    }
}
