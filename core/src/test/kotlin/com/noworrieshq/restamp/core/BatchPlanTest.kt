package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.plan.BatchPlan
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.core.plan.overwritesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BatchPlanTest {
    private val taken = DateTaken.ofInstant(Instant.parse("2024-10-05T14:30:12Z"))
    private val editedAt = DateTaken.ofInstant(Instant.parse("2024-10-07T09:00:00Z"))
    private val here = GeoLocation(-33.8568, 151.2153)
    private val elsewhere = GeoLocation(51.5, -0.12)
    private val original = MediaMetadata(taken, here)

    private val plan = BatchPlan(
        mapOf(
            // Typical edit: edit time as the date, no GPS.
            "typical" to CopyPlan.between(original, MediaMetadata(editedAt, null)),
            // Bare: nothing at all.
            "bare" to CopyPlan.between(original, MediaMetadata.EMPTY),
            // Already fixed.
            "done" to CopyPlan.between(original, original),
            // Someone set a different place on purpose.
            "moved" to CopyPlan.between(original, MediaMetadata(taken, elsewhere)),
        ),
    )

    @Test fun countsWhatEachFieldNeeds() {
        assertEquals(1, plan.canAdd(MetadataField.DATE_TAKEN))
        assertEquals(2, plan.canAdd(MetadataField.LOCATION))
        assertEquals(1, plan.differing(MetadataField.DATE_TAKEN))
        assertEquals(1, plan.differing(MetadataField.LOCATION))
        assertEquals(setOf(MetadataField.DATE_TAKEN, MetadataField.LOCATION), plan.needsConfirmation)
        assertEquals(1, plan.nothingToDo)
    }

    @Test fun withoutConfirmationOnlyMissingTagsAreAdded() {
        val jobs = plan.jobs().associateBy { it.id }
        assertEquals(setOf("typical", "bare"), jobs.keys)
        assertNull(jobs.getValue("typical").update.dateTaken)
        assertEquals(here, jobs.getValue("typical").update.location)
        assertEquals(MetadataUpdate(taken, here), jobs.getValue("bare").update)
        assertTrue(jobs.values.all { it.confirmed.isEmpty() })
    }

    @Test fun confirmingDateOverwritesOnlyDates() {
        val jobs = plan.jobs(overwrite = setOf(MetadataField.DATE_TAKEN)).associateBy { it.id }
        assertEquals(setOf("typical", "bare"), jobs.keys)
        assertEquals(MetadataUpdate(taken, here), jobs.getValue("typical").update)
        assertEquals(setOf(MetadataField.DATE_TAKEN), jobs.getValue("typical").confirmed)
        assertEquals(emptySet<MetadataField>(), jobs.getValue("bare").confirmed)
    }

    @Test fun canCopyJustOneField() {
        val jobs = plan.jobs(fields = setOf(MetadataField.LOCATION), overwrite = MetadataField.entries.toSet())
        assertEquals(setOf("typical", "bare", "moved"), jobs.map { it.id }.toSet())
        assertTrue(jobs.all { it.update.dateTaken == null })
        assertEquals(setOf(MetadataField.LOCATION), jobs.first { it.id == "moved" }.confirmed)
    }

    @Test fun manualValuesReportOverwrites() {
        assertEquals(setOf(MetadataField.DATE_TAKEN), overwritesIn(MediaMetadata(editedAt, null), MetadataUpdate(taken, here)))
        assertEquals(emptySet<MetadataField>(), overwritesIn(MediaMetadata(taken, null), MetadataUpdate(taken, here)))
        assertEquals(setOf(MetadataField.LOCATION), overwritesIn(original, MetadataUpdate(location = elsewhere)))
    }
}
