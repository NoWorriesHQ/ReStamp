package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MediaType
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Real files from ffmpeg in the layouts phones and editors produce (see resources/formats).
 * Each one gets date and location written twice, and must keep every media byte, read back
 * correctly, and, when ffmpeg is installed, still decode and show the new tags to ffprobe,
 * which is an independent reader.
 */
class Mp4FormatMatrixTest {
    @get:Rule val tmp = TemporaryFolder()

    private val accessor = Mp4MetadataAccessor()
    private val sydney = GeoLocation(-33.8568, 151.2153)
    private val paris = GeoLocation(48.8584, 2.2945)
    private val taken = DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:01Z"))
    private val later = DateTaken.ofInstant(Instant.parse("2026-11-16T09:00:00Z"))

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/formats/$name") ?: error("missing fixture $name")
        return File(tmp.root, name).apply { outputStream().use { stream.use { s -> s.copyTo(it) } } }
    }

    private fun read(f: File): MediaMetadata = FileChannelIo.open(f, false).use { accessor.read(it) }

    private fun write(f: File, update: MetadataUpdate) =
        FileChannelIo.open(f, true).use { accessor.write(it, update, MetadataField.entries.toSet()) }

    /** Writes, checks, writes again with other values (now an overwrite), checks again. */
    private fun roundTrip(name: String) {
        val f = fixture(name)
        FileChannelIo.open(f, false).use { assertEquals(name, MediaType.MP4, MediaType.sniff(it)) }
        val media = mediaDigest(f)

        write(f, MetadataUpdate(taken, sydney))
        check(name, f, taken, sydney)
        assertArrayEquals("$name: media changed", media, mediaDigest(f))

        write(f, MetadataUpdate(later, paris))
        check(name, f, later, paris)
        assertArrayEquals("$name: media changed on second write", media, mediaDigest(f))
    }

    private fun check(name: String, f: File, date: DateTaken, place: GeoLocation) {
        val m = read(f)
        assertEquals("$name date", date.instant, m.dateTaken!!.instant)
        assertTrue("$name location ${m.location}", m.location!!.isSamePlaceAs(place))
        Ffmpeg.check(f, date, place)
    }

    @Test fun moovAfterMediaData() = roundTrip("moov-at-end.mp4")
    @Test fun moovBeforeMediaData() = roundTrip("moov-first.mp4")
    @Test fun quickTimeMov() = roundTrip("quicktime.mov")
    @Test fun videoWithoutAudio() = roundTrip("video-only.mp4")
    @Test fun hevcVideo() = roundTrip("hevc.mp4")
    @Test fun sixtyFourBitTimes() = roundTrip("version1-times.mp4")
    /**
     * Fragmented files need moov before the fragments, so there's nowhere to grow it. The date
     * still writes in place; adding a location is refused cleanly and the file is untouched.
     */
    @Test fun fragmentedMp4() {
        val f = fixture("fragmented.mp4")
        val media = mediaDigest(f)
        write(f, MetadataUpdate(dateTaken = taken))
        assertEquals(taken.instant, read(f).dateTaken!!.instant)
        val before = f.readBytes()
        val e = runCatching { write(f, MetadataUpdate(location = sydney)) }.exceptionOrNull()
        assertTrue("expected a clean refusal, got $e", e is UnsupportedMediaException)
        assertArrayEquals("refused write changed the file", before, f.readBytes())
        assertArrayEquals(media, mediaDigest(f))
    }

    /** ffmpeg writes the 3GPP `loci` box rather than `©xyz`. It must count as an existing location. */
    @Test fun readsTagsOtherToolsWrote() {
        val f = fixture("tagged.mp4")
        val m = read(f)
        assertEquals(Instant.parse("2024-10-05T14:30:12Z"), m.dateTaken!!.instant)
        assertTrue(m.location!!.isSamePlaceAs(paris))
        roundTrip("tagged.mp4")
    }

    /** Location inside udta/meta/ilst, as Adobe tools and exiftool write it. */
    @Test fun iTunesStyleLocation() {
        val f = fixture("itunes-ilst-location.mp4")
        assertTrue(read(f).location!!.isSamePlaceAs(GeoLocation(51.5007, -0.1246)))
        roundTrip("itunes-ilst-location.mp4")
    }

    /** iPhone videos keep location in moov/meta keys (com.apple.quicktime.location.ISO6709). */
    @Test fun appleKeysLocation() {
        val f = fixture("apple-keys.mov")
        assertTrue(read(f).location!!.isSamePlaceAs(paris))
        roundTrip("apple-keys.mov")
        // The other Apple tags are kept.
        assertTrue(String(File(tmp.root, "apple-keys.mov").readBytes(), Charsets.ISO_8859_1).contains("com.apple.quicktime.make"))
    }

    @Test fun quickTimeWithoutFtyp() = roundTrip("no-ftyp.mov")

    @Test fun untaggedFilesReadAsMissing() {
        for (name in listOf("moov-at-end.mp4", "moov-first.mp4", "quicktime.mov", "fragmented.mp4")) {
            val m = read(fixture(name))
            assertNull(name, m.dateTaken)
            assertNull(name, m.location)
        }
    }

    @Test fun existingTagsNeedConfirmation() {
        val f = fixture("tagged.mp4")
        val before = f.readBytes()
        val e = runCatching {
            FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(taken, sydney)) }
        }.exceptionOrNull()
        assertTrue(e is OverwriteNotConfirmedException)
        assertEquals(setOf(MetadataField.DATE_TAKEN, MetadataField.LOCATION), (e as OverwriteNotConfirmedException).fields)
        assertArrayEquals("file changed without confirmation", before, f.readBytes())
    }

    /** SHA-256 over every mdat payload and where it sits, so moved or changed media is caught. */
    private fun mediaDigest(file: File): ByteArray {
        val bytes = file.readBytes()
        val md = MessageDigest.getInstance("SHA-256")
        var pos = 0L
        while (pos + 8 <= bytes.size) {
            val buf = ByteBuffer.wrap(bytes, pos.toInt(), 16.coerceAtMost(bytes.size - pos.toInt()))
            var size = buf.int.toLong() and 0xFFFFFFFFL
            val type = String(bytes, pos.toInt() + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = buf.long; header = 16 }
            if (size == 0L) size = bytes.size - pos
            if (type == "mdat") {
                md.update(pos.toString().toByteArray())
                md.update(bytes, (pos + header).toInt(), (size - header).toInt())
            }
            pos += size
        }
        return md.digest()
    }
}

/** Independent checks with ffprobe/ffmpeg, skipped when they aren't installed. */
object Ffmpeg {
    private val available: Boolean by lazy { runCatching { run("ffprobe", "-version").first == 0 }.getOrDefault(false) }

    /**
     * [original], when given, is the untouched file: decode warnings it already had (some
     * cameras write odd timestamps) are allowed, as long as tagging added no new ones.
     */
    fun check(f: File, date: DateTaken, place: GeoLocation, original: File? = null) {
        if (!available) return
        val (code, out) = run("ffprobe", "-v", "error", "-show_entries", "format_tags=creation_time,location", "-of", "default=nw=1", f.path)
        assertEquals("ffprobe failed on ${f.name}: $out", 0, code)
        val tags = out.lines().filter { '=' in it }.associate { it.substringAfter("TAG:").substringBefore('=') to it.substringAfter('=') }
        assertEquals("${f.name}: ffprobe creation_time", date.instant, Instant.parse(tags["creation_time"]))
        val loc = Regex("""([+-]\d+\.\d+)([+-]\d+\.\d+)""").find(tags["location"].orEmpty())
            ?: error("${f.name}: ffprobe sees no location in $tags")
        assertEquals(place.latitude, loc.groupValues[1].toDouble(), 0.0001)
        assertEquals(place.longitude, loc.groupValues[2].toDouble(), 0.0001)
        val (decode, errors) = decodeErrors(f)
        assertEquals("${f.name} no longer decodes: $errors", 0, decode)
        val allowed = original?.let { decodeErrors(it).second }.orEmpty()
        assertTrue("${f.name} decode errors: $errors", errors.isBlank() || errors == allowed)
    }

    /** Exit code and ffmpeg's decode errors, with memory addresses removed so runs compare. */
    private fun decodeErrors(f: File): Pair<Int, String> {
        val (code, out) = run("ffmpeg", "-v", "error", "-i", f.path, "-f", "null", "-")
        return code to out.replace(Regex("0x[0-9a-f]+"), "")
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }
}
