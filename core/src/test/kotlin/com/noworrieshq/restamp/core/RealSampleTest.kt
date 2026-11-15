package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaType
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Runs against real phone files when RESTAMP_SAMPLES points at a folder of them.
 * Samples stay out of the repo because they're personal.
 */
class RealSampleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val samples: List<File> =
        System.getProperty("restamp.samples")?.let { File(it).listFiles()?.toList() }.orEmpty()
            .filter { it.name.endsWith(".mp4", ignoreCase = true) }

    @Test fun writesDateAndLocationIntoEveryRealMp4() {
        assumeTrue("Set RESTAMP_SAMPLES to run", samples.isNotEmpty())
        val accessor = Mp4MetadataAccessor()
        val location = GeoLocation(-33.8568, 151.2153)
        val date = DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:01Z"))
        for (sample in samples) {
            val copy = sample.copyTo(File(tmp.root, sample.name), overwrite = true)
            val mediaBefore = mdatDigest(copy)
            FileChannelIo.open(copy, true).use { io ->
                assertEquals(MediaType.MP4, MediaType.sniff(io))
                val before = accessor.read(io)
                println("${sample.name}: before=$before")
                accessor.write(io, MetadataUpdate(date, location), MetadataField.entries.toSet())
            }
            val after = FileChannelIo.open(copy, false).use { accessor.read(it) }
            println("${sample.name}: after=$after (${sample.length()} -> ${copy.length()} bytes)")
            assertTrue(after.location!!.isSamePlaceAs(location))
            assertEquals(date.instant, after.dateTaken!!.instant)
            assertArrayEquals("${sample.name}: media data changed", mediaBefore, mdatDigest(copy))
            // Keep the results for checking with other tools (ffprobe, Google Photos).
            System.getProperty("restamp.out")?.let { copy.copyTo(File(it, sample.name), overwrite = true) }
        }
    }

    private fun mdatDigest(file: File): ByteArray {
        val (offset, payload) = Mp4Fixtures.mdatPayload(file)
        return MessageDigest.getInstance("SHA-256").apply { update(offset.toString().toByteArray()); update(payload) }.digest()
    }
}
