package com.noworrieshq.restamp.media

import com.noworrieshq.restamp.core.io.FileChannelIo
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.undo.JournalingIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000_000L

    private fun change(log: UndoLog, f: File, batch: String?): UndoEntry {
        val undo = log.newFile()
        FileChannelIo.open(f, true).use { raw ->
            JournalingIo(raw, File(tmp.root, "j")).use { j -> j.write(0, "changed".toByteArray()); j.finish(undo) }
        }
        return log.add(undo, f.path, f.name, setOf(MetadataField.DATE_TAKEN), batch)
    }

    @Test fun recordsSurviveReloadAndUndoRestores() {
        val dir = tmp.newFolder("undo")
        val f = File(tmp.root, "a.jpg").apply { writeText("original content") }
        val entry = change(UndoLog(dir) { clock }, f, "b1")
        val reopened = UndoLog(dir) { clock }
        assertEquals(listOf(entry), reopened.entries())
        reopened.undo(reopened.entries().single()) { FileChannelIo.open(File(it), true) }
        assertArrayEquals("original content".toByteArray(), f.readBytes())
        assertEquals(emptyList<UndoEntry>(), reopened.entries())
    }

    @Test fun dropsRecordsOlderThan30Days() {
        val dir = tmp.newFolder("undo")
        val log = UndoLog(dir) { clock }
        val old = change(log, File(tmp.root, "old.jpg").apply { writeText("x".repeat(20)) }, null)
        clock += UndoLog.MAX_AGE_MS + 1
        change(log, File(tmp.root, "new.jpg").apply { writeText("y".repeat(20)) }, null)
        assertEquals(listOf("new.jpg"), log.entries().map { it.name })
        assertFalse(File(dir, old.id + ".undo").exists())
    }
}
