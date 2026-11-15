package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.jpeg.ExifDateCodec
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.plan.CopyPlan
import com.noworrieshq.restamp.core.plan.FieldStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CopyPlanAndDateTest {
    private val original = MediaMetadata(DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:04Z")), GeoLocation(-33.8568, 151.2153))

    @Test fun planForTypicalEditedCopy() {
        // Edited copies keep a creation time (the edit time) but lose GPS.
        val edited = MediaMetadata(DateTaken.ofInstant(Instant.parse("2026-11-15T01:50:40Z")), null)
        val plan = CopyPlan.between(original, edited)
        val byField = plan.fields.associate { it.field to it.status }
        assertEquals(FieldStatus.DIFFERENT_NEEDS_CONFIRMATION, byField[MetadataField.DATE_TAKEN])
        assertEquals(FieldStatus.WILL_ADD, byField[MetadataField.LOCATION])
        assertEquals(setOf(MetadataField.DATE_TAKEN), plan.needsConfirmation)

        val unconfirmed = plan.toUpdate(emptySet())
        assertNull(unconfirmed.dateTaken)
        assertEquals(original.location, unconfirmed.location)
        assertEquals(setOf(MetadataField.DATE_TAKEN, MetadataField.LOCATION), plan.toUpdate(plan.needsConfirmation).fields)
    }

    @Test fun nothingToCopyFromBareSource() {
        val plan = CopyPlan.between(MediaMetadata.EMPTY, original)
        assertTrue(plan.fields.all { it.status == FieldStatus.MISSING_IN_SOURCE })
        assertTrue(plan.toUpdate(MetadataField.entries.toSet()).isEmpty)
    }

    @Test fun exifDatesRoundTrip() {
        val d = ExifDateCodec.decode("2026:11:15 12:50:04", "+11:00", "818")!!
        assertEquals(LocalDateTime.of(2026, 11, 15, 12, 50, 4, 818_000_000), d.localDateTime)
        assertEquals(Instant.parse("2026-11-15T01:50:04.818Z"), d.instant)
        val tags = ExifDateCodec.encode(d)
        assertEquals("2026:11:15 12:50:04", tags.dateTime)
        assertEquals("+11:00", tags.offset)
        assertEquals("818", tags.subSec)
        assertEquals("+00:00", ExifDateCodec.encode(DateTaken.ofInstant(Instant.EPOCH)).offset)
    }

    @Test fun exifDateWithoutOffsetComparesByWallClock() {
        val a = ExifDateCodec.decode("2026:11:15 12:50:04", null, null)!!
        val b = DateTaken(LocalDateTime.of(2026, 11, 15, 12, 50, 4), ZoneOffset.ofHours(11))
        assertNull(a.instant)
        assertTrue(a.isSameMomentAs(b))
        assertNull(ExifDateCodec.decode("0000:00:00 00:00:00", null, null))
        assertNull(ExifDateCodec.decode("garbage", null, null))
    }
}
