package com.noworrieshq.restamp.media

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

/**
 * Files laid out exactly like Pixel 9a photos (Nov 2026): Ultra HDR with a gain map found
 * through MPF and XMP, C2PA content credentials, extended XMP with the HDR+ makernote, and
 * for the motion photo an MP4 appended at the end. Made from real photos with
 * tools/make_lookalike_jpeg.py, so the picture, video and location are blank or made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PixelLookalikeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val accessor by lazy { JpegMetadataAccessor(tmp.newFolder("cache")) { ZoneId.of("Pacific/Efate") } }
    private val sydney = GeoLocation(-33.8568, 151.2153, 12.0)
    private val taken = DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:01Z"))

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/pixel/$name") ?: error("missing fixture $name")
        return File(tmp.root, name).apply { outputStream().use { stream.use { s -> s.copyTo(it) } } }
    }
    private fun read(f: File): MediaMetadata = FileChannelIo.open(f, false).use { accessor.read(it) }
    private fun write(f: File, u: MetadataUpdate, confirmed: Set<MetadataField> = emptySet()) =
        FileChannelIo.open(f, true).use { accessor.write(it, u, confirmed) }

    private fun xmp(bytes: ByteArray): String {
        val start = indexOf(bytes, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray())
        val len = ((bytes[start - 2].toInt() and 0xFF) shl 8) or (bytes[start - 1].toInt() and 0xFF)
        return String(bytes, start, len - 2, Charsets.ISO_8859_1)
    }
    private fun itemLengths(bytes: ByteArray) =
        Regex("Item:Length=\"(\\d+)\"").findAll(xmp(bytes)).map { it.groupValues[1].toInt() }.toList()

    private fun check(name: String, hasVideo: Boolean, wallClock: LocalDateTime) {
        val f = fixture(name)
        val original = f.readBytes()

        // Reads what the camera wrote: local time with its +11:00 offset, and the (made-up) place.
        val existing = read(f)
        assertEquals(wallClock, existing.dateTaken!!.localDateTime)
        assertEquals(ZoneOffset.ofHours(11), existing.dateTaken!!.utcOffset)
        assertTrue(existing.location!!.isSamePlaceAs(GeoLocation(-17.74, 168.315)))

        // It already has both, so writing needs confirmation and leaves the file alone.
        val e = runCatching { write(f, MetadataUpdate(taken, sydney)) }.exceptionOrNull()
        assertTrue("expected a confirmation request, got $e", e is OverwriteNotConfirmedException)
        assertArrayEquals(original, f.readBytes())

        write(f, MetadataUpdate(taken, sydney), MetadataField.entries.toSet())
        val m = read(f)
        assertEquals(taken.instant, m.dateTaken!!.instant)
        assertTrue(m.location!!.isSamePlaceAs(sydney))

        val after = f.readBytes()
        // Ultra HDR: the gain map is still where MPF says, unchanged.
        val gainMap = secondImage(original)
        assertNotNull(gainMap)
        assertArrayEquals("$name: gain map lost or moved", gainMap, secondImage(after))
        // Google's XMP directory (primary, gain map, video lengths) and content credentials kept.
        assertEquals("$name: XMP changed", xmp(original), xmp(after))
        assertTrue("$name: C2PA lost", indexOf(after, "c2pa.claim".toByteArray()) >= 0)
        assertTrue("$name: extended XMP lost", indexOf(after, "HdrPlusMakernote".toByteArray()) >= 0)
        // Everything after the main picture (gain map, then video) is byte for byte the same,
        // and still sits at the end where the XMP lengths say.
        val tail = itemLengths(original).sum()
        assertArrayEquals(
            "$name: gain map or video changed",
            original.copyOfRange(original.size - tail, original.size),
            after.copyOfRange(after.size - tail, after.size),
        )
        if (hasVideo) {
            val video = itemLengths(after).last()
            assertEquals("ftyp", String(after, after.size - video + 4, 4, Charsets.ISO_8859_1))
        }
    }

    @Test fun ultraHdrPhoto() =
        check("ultrahdr-lookalike.jpg", hasVideo = false, LocalDateTime.of(2026, 11, 16, 10, 49, 56, 269_000_000))

    @Test fun motionPhoto() =
        check("motion-ultrahdr-lookalike.MP.jpg", hasVideo = true, LocalDateTime.of(2026, 11, 16, 10, 50, 18, 704_000_000))
}
