package com.noworrieshq.restamp.media

import com.noworrieshq.restamp.core.MetadataException
import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs the JPEG writer over real photos from many phones (Pixel, Nexus, Samsung, iPhone, HTC,
 * OnePlus, Motorola) and Ultra HDR gain-map samples, when RESTAMP_JPEG_CORPUS lists folders of
 * them (CI fetches public collections). Each photo must read back the new tags and keep its
 * compressed image and everything after it (motion photo video, MPF/Ultra HDR second image,
 * Samsung trailers) byte for byte, or be cleanly refused and unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JpegCorpusTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files: List<File> = System.getenv("RESTAMP_JPEG_CORPUS").orEmpty().split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .flatMap { dir -> File(dir).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg") }.toList() }
        .sortedBy { it.name }

    @Test fun everyPhotoIsTaggedOrCleanlyRefused() {
        assumeTrue("Set RESTAMP_JPEG_CORPUS to run", files.isNotEmpty())
        val accessor = JpegMetadataAccessor(tmp.newFolder("cache")) { ZoneId.of("Pacific/Efate") }
        val place = GeoLocation(-17.7334, 168.3273, 15.0)
        val date = DateTaken.ofInstant(Instant.parse("2026-11-16T09:00:00Z"))
        val failures = mutableListOf<String>()
        for (source in files) {
            val f = source.copyTo(File(tmp.root, source.name), overwrite = true)
            val before = f.readBytes()
            val outcome = runCatching {
                val existing = FileChannelIo.open(f, false).use { accessor.read(it) }
                FileChannelIo.open(f, true).use { accessor.write(it, MetadataUpdate(date, place), MetadataField.entries.toSet()) }
                val after = FileChannelIo.open(f, false).use { accessor.read(it) }
                check(after.dateTaken?.instant == date.instant) { "date reads back as ${after.dateTaken}" }
                check(after.location?.isSamePlaceAs(place) == true) { "location reads back as ${after.location}" }
                val bytes = f.readBytes()
                check(imageAndTrailer(before).contentEquals(imageAndTrailer(bytes))) { "image data or trailing data changed" }
                "tagged (had date=${existing.dateTaken?.let { "${it.localDateTime}${it.utcOffset ?: ""}" }}, location=${existing.location}; ${before.size} -> ${bytes.size} bytes)"
            }
            val line = outcome.fold(
                { "OK       ${source.name}: $it" },
                { e ->
                    if (e is MetadataException && before.contentEquals(f.readBytes())) "REFUSED  ${source.name}: ${e.message}"
                    else "FAILED   ${source.name}: $e".also { failures += it }
                },
            )
            println(line)
        }
        assertTrue("Problems:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Everything from the main image's start-of-scan to the end of the file: the compressed
     * picture plus whatever phones append after it. Metadata segments before it may change.
     */
    private fun imageAndTrailer(bytes: ByteArray): ByteArray {
        var i = 2
        while (i + 4 <= bytes.size) {
            val marker = bytes[i + 1].toInt() and 0xFF
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) return bytes.copyOfRange(i, bytes.size)
            i += 2 + len
        }
        error("no start of scan")
    }
}
