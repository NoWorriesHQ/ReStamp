package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.match.FileMatcher
import com.noworrieshq.restamp.core.match.FileName
import com.noworrieshq.restamp.core.match.ManualChoices
import com.noworrieshq.restamp.core.match.MatchConfidence
import com.noworrieshq.restamp.core.match.MediaFile
import com.noworrieshq.restamp.core.match.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FileNameTest {
    @Test fun pixelNameCarriesUtcCaptureTime() {
        val n = FileName.parse("PXL_20241005_143012345.jpg")
        assertEquals(MediaKind.IMAGE, n.kind)
        assertEquals("pxl_20241005_143012345", n.cameraKey)
        assertEquals(LocalDateTime.of(2024, 10, 5, 14, 30, 12, 345_000_000), n.time!!.dateTime)
        assertTrue(n.time!!.isUtc)
        assertEquals(0, n.editLevel)
    }

    @Test fun pixelShotTagsAreNotEdits() {
        for (name in listOf("PXL_20241005_143012345.MP.jpg", "PXL_20241005_143012345.NIGHT.jpg", "PXL_20241005_143012345.RAW-01.MP.COVER.jpg", "PXL_20241005_143012345.TS.mp4")) {
            val n = FileName.parse(name)
            assertEquals(name, "pxl_20241005_143012345", n.baseName)
            assertEquals(name, 0, n.editLevel)
        }
    }

    @Test fun editSuffixesAreStrippedOneAtATime() {
        val n = FileName.parse("PXL_20241005_143012345-edited (1).JPEG")
        assertEquals("jpeg", n.extension)
        assertEquals(listOf("pxl_20241005_143012345-edited (1)", "pxl_20241005_143012345-edited", "pxl_20241005_143012345"), n.keys)
        assertEquals(2, n.editLevel)

        assertEquals("pxl_20241005_143012345", FileName.parse("PXL_20241005_143012345~2.jpg").coreName)
        assertEquals("pxl_20241005_143012345", FileName.parse("PXL_20241005_143012345_exported_1728138612345.jpg").coreName)
        assertEquals("pxl_20241005_143012345", FileName.parse("PXL_20241005_143012345-01.jpeg").coreName)
        assertEquals("beach", FileName.parse("Copy of beach_edited.jpg").coreName)
    }

    @Test fun timestampDigitsAreNeverMistakenForACounter() {
        assertEquals("img_20241005_143012", FileName.parse("IMG_20241005_143012.jpg").coreName)
        assertEquals("pxl_20241005_143012345", FileName.parse("PXL_20241005_143012345.mp4").coreName)
    }

    @Test fun unknownTextAfterTheTimestampCountsAsAnEdit() {
        val n = FileName.parse("PXL_20241005_143012345_Snapseed2.jpg")
        assertEquals("pxl_20241005_143012345", n.cameraKey)
        assertEquals(1, n.editLevel)
    }

    @Test fun oldStyleNamesUseLocalTime() {
        val n = FileName.parse("VID_20200101_235959.mp4")
        assertEquals(MediaKind.VIDEO, n.kind)
        assertEquals(false, n.time!!.isUtc)
    }

    @Test fun nonMediaFiles() {
        assertEquals(MediaKind.OTHER, FileName.parse("notes.txt").kind)
        assertNull(FileName.parse("holiday.jpg").cameraKey)
    }
}

class FileMatcherTest {
    private var nextId = 0
    private fun src(name: String) = MediaFile("orig/${nextId++}/$name", name)
    private fun tgt(name: String) = MediaFile("edit/${nextId++}/$name", name)

    @Test fun pairsPixelEditsAcrossFolders() {
        val photo = src("PXL_20241005_143012345.MP.jpg")
        val video = src("PXL_20241005_150000111.mp4")
        val other = src("PXL_20241006_090000000.jpg")
        val editedPhoto = tgt("PXL_20241005_143012345-edited.jpg")
        val editedVideo = tgt("PXL_20241005_150000111~2.mp4")
        val stranger = tgt("Screenshot_20241001-101010.png")

        val r = FileMatcher.match(listOf(photo, video, other), listOf(editedPhoto, editedVideo, stranger))

        assertEquals(photo, r.pairFor(editedPhoto.id)!!.source)
        assertEquals(MatchConfidence.SAME_BASE_NAME, r.pairFor(editedPhoto.id)!!.confidence)
        assertEquals(video, r.pairFor(editedVideo.id)!!.source)
        assertEquals(listOf(stranger), r.unmatchedTargets)
        assertEquals(listOf(other), r.unmatchedSources)
        assertTrue(r.ambiguous.isEmpty())
    }

    @Test fun identicalNamesAreExactMatches() {
        val a = src("PXL_20241005_143012345.jpg")
        val b = tgt("pxl_20241005_143012345.jpeg")
        val pair = FileMatcher.match(listOf(a), listOf(b)).pairFor(b.id)!!
        assertEquals(a, pair.source)
        assertEquals(MatchConfidence.EXACT_NAME, pair.confidence)
    }

    @Test fun photoNeverPairsWithVideo() {
        val video = src("PXL_20241005_143012345.mp4")
        val photo = tgt("PXL_20241005_143012345.jpg")
        val r = FileMatcher.match(listOf(video), listOf(photo))
        assertEquals(listOf(photo), r.unmatchedTargets)
    }

    @Test fun unknownEditorSuffixFallsBackToCameraTimestamp() {
        val a = src("PXL_20241005_143012345.jpg")
        val b = tgt("PXL_20241005_143012345_Snapseed2.jpg")
        assertEquals(a, FileMatcher.match(listOf(a), listOf(b)).pairFor(b.id)!!.source)
    }

    @Test fun sameSecondWithDifferentMillisIsALikelyMatch() {
        val a = src("PXL_20241005_143012345.jpg")
        val b = tgt("PXL_20241005_143012.jpg")
        val pair = FileMatcher.match(listOf(a), listOf(b)).pairFor(b.id)!!
        assertEquals(a, pair.source)
        assertEquals(MatchConfidence.SAME_CAPTURE_TIME, pair.confidence)
    }

    @Test fun longestSharedNameWins() {
        val two = src("Holiday 2.jpg")
        val three = src("Holiday 3.jpg")
        val edited = tgt("Holiday 2-edited.jpg")
        assertEquals(two, FileMatcher.match(listOf(two, three), listOf(edited)).pairFor(edited.id)!!.source)
    }

    @Test fun numberedOriginalsAreNotConfused() {
        val one = src("IMG_001.jpg")
        val two = tgt("IMG_002.jpg")
        val r = FileMatcher.match(listOf(one), listOf(two))
        assertTrue(r.pairs.isEmpty())
    }

    @Test fun sameExtensionBreaksTies() {
        val raw = src("PXL_20241005_143012345.RAW-01.ORIGINAL.dng")
        val jpg = src("PXL_20241005_143012345.RAW-01.MP.COVER.jpg")
        val edited = tgt("PXL_20241005_143012345-edited.jpg")
        assertEquals(jpg, FileMatcher.match(listOf(raw, jpg), listOf(edited)).pairFor(edited.id)!!.source)
    }

    @Test fun realTiesAreLeftForThePerson() {
        val a = MediaFile("orig/a/PXL_20241005_143012345.jpg", "PXL_20241005_143012345.jpg")
        val b = MediaFile("orig/sub/PXL_20241005_143012345.jpg", "PXL_20241005_143012345.jpg")
        val edited = tgt("PXL_20241005_143012345-edited.jpg")
        val r = FileMatcher.match(listOf(a, b), listOf(edited))
        assertTrue(r.pairs.isEmpty())
        assertEquals(setOf(a, b), r.ambiguous.single().candidates.toSet())
        // Choosing one resolves it.
        val chosen = FileMatcher.match(listOf(a, b), listOf(edited), ManualChoices().pair(edited.id, b.id))
        assertEquals(b, chosen.pairFor(edited.id)!!.source)
        assertEquals(MatchConfidence.MANUAL, chosen.pairFor(edited.id)!!.confidence)
        assertEquals(listOf(a), chosen.unmatchedSources)
    }

    @Test fun oneOriginalCanFeedSeveralEdits() {
        val a = src("PXL_20241005_143012345.jpg")
        val e1 = tgt("PXL_20241005_143012345-edited.jpg")
        val e2 = tgt("PXL_20241005_143012345-edited (1).jpg")
        val r = FileMatcher.match(listOf(a), listOf(e1, e2))
        assertEquals(2, r.pairs.size)
        assertTrue(r.pairs.all { it.source == a })
        assertTrue(r.unmatchedSources.isEmpty())
    }

    @Test fun manualPairAndUnpairOverrideAutomaticMatching() {
        val a = src("PXL_20241005_143012345.jpg")
        val b = src("beach.jpg")
        val edited = tgt("PXL_20241005_143012345-edited.jpg")
        val stray = tgt("final.jpg")

        var manual = ManualChoices().unpair(edited.id).pair(stray.id, b.id)
        var r = FileMatcher.match(listOf(a, b), listOf(edited, stray), manual)
        assertNull(r.pairFor(edited.id))
        assertEquals(listOf(edited), r.unmatchedTargets)
        assertEquals(b, r.pairFor(stray.id)!!.source)
        assertEquals(listOf(a), r.unmatchedSources)

        manual = manual.reset(edited.id)
        r = FileMatcher.match(listOf(a, b), listOf(edited, stray), manual)
        assertEquals(a, r.pairFor(edited.id)!!.source)
    }

    @Test fun oneSharedFolderLikePixelCameraRoll() {
        // Google Photos saves edits next to the originals in DCIM/Camera.
        val files = listOf(
            "PXL_20241005_143012345.MP.jpg",
            "PXL_20241005_143012345.MP~2.jpg",
            "PXL_20241005_150000111.mp4",
            "PXL_20241005_150000111_exported_1728140000000.mp4",
            "PXL_20241006_090000000.jpg",
            "trimmed-edited.mp4",
        ).map { MediaFile("dcim/$it", it) }
        val byName = files.associateBy { it.name }

        val r = FileMatcher.match(files, files)

        assertEquals(byName["PXL_20241005_143012345.MP.jpg"], r.pairFor("dcim/PXL_20241005_143012345.MP~2.jpg")!!.source)
        assertEquals(byName["PXL_20241005_150000111.mp4"], r.pairFor("dcim/PXL_20241005_150000111_exported_1728140000000.mp4")!!.source)
        assertEquals(2, r.pairs.size)
        assertTrue(r.pairs.none { it.source.id == it.target.id })
        // Originals stay on the originals side; only real edits are listed as missing an original.
        assertEquals(listOf(byName["trimmed-edited.mp4"]), r.unmatchedTargets)
        assertEquals(listOf(byName["PXL_20241006_090000000.jpg"]), r.unmatchedSources)
    }

    @Test fun suggestionsPutTheLikeliestOriginalFirst() {
        val near = src("PXL_20241005_143015000.jpg")
        val far = src("PXL_20230101_000000000.jpg")
        val video = src("PXL_20241005_143012000.mp4")
        val edited = tgt("PXL_20241005_143012345_v2.jpg")
        val s = FileMatcher.suggestions(edited, listOf(far, video, near))
        assertEquals(near, s.first())
        assertEquals(video, s.last())
    }

    @Test fun scalesToABigCameraRoll() {
        val originals = (0 until 5000).map { src("PXL_20240101_%06d%03d.jpg".format(it, it % 1000)) }
        val edits = originals.take(2000).map { tgt(it.name.replace(".jpg", "-edited.jpg")) }
        val start = System.nanoTime()
        val r = FileMatcher.match(originals, edits)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(2000, r.pairs.size)
        assertTrue("took $ms ms", ms < 3000)
    }
}
