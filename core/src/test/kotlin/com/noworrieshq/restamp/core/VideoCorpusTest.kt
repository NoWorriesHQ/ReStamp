package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.Mp4MetadataAccessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Runs the MP4 engine over a folder of videos from other phones and cameras, when
 * RESTAMP_VIDEO_CORPUS points at one (CI fetches a public collection). Every file must end up
 * either correctly tagged (checked by ffprobe too) or cleanly refused and unchanged.
 */
class VideoCorpusTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files: List<File> = System.getenv("RESTAMP_VIDEO_CORPUS")?.let { dir ->
        File(dir).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("mp4", "mov", "m4v", "3gp") }.sortedBy { it.name }.toList()
    }.orEmpty()

    @Test fun everyVideoIsTaggedOrCleanlyRefused() {
        assumeTrue("Set RESTAMP_VIDEO_CORPUS to run", files.isNotEmpty())
        val accessor = Mp4MetadataAccessor()
        val place = GeoLocation(-17.7334, 168.3273)
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
                Ffmpeg.check(f, date, place, original = source)
                "tagged (had date=${existing.dateTaken?.let { "${it.localDateTime}${it.utcOffset ?: ""}" }}, location=${existing.location})"
            }
            val line = outcome.fold(
                { "OK       ${source.name}: $it" },
                { e ->
                    if (e is MetadataException) {
                        if (before.contentEquals(f.readBytes())) "REFUSED  ${source.name}: ${e.message}"
                        else "FAILED   ${source.name}: refused but the file changed".also { failures += it }
                    } else {
                        "FAILED   ${source.name}: $e".also { failures += it }
                    }
                },
            )
            println(line)
        }
        assertTrue("Problems:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
