package com.noworrieshq.restamp.core.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** The two metadata fields this app manages. */
enum class MetadataField { DATE_TAKEN, LOCATION }

/**
 * When the photo or video was captured.
 *
 * JPEG stores a local wall-clock time plus an optional UTC offset; MP4 stores a UTC instant.
 * Keeping both pieces lets either format round-trip without inventing a time zone.
 */
data class DateTaken(
    val localDateTime: LocalDateTime,
    val utcOffset: ZoneOffset?,
) {
    /** The absolute instant, or null when the offset is unknown. */
    val instant: Instant? get() = utcOffset?.let { localDateTime.toInstant(it) }

    /** Resolves to an instant, using [assumedZone] only when the offset is unknown. */
    fun toInstant(assumedZone: ZoneId): Instant =
        instant ?: localDateTime.atZone(assumedZone).toInstant()

    /** True when both describe the same moment, to within one second (MP4's resolution). */
    fun isSameMomentAs(other: DateTaken): Boolean {
        val a = instant
        val b = other.instant
        val seconds = if (a != null && b != null) {
            ChronoUnit.SECONDS.between(a, b)
        } else {
            ChronoUnit.SECONDS.between(localDateTime, other.localDateTime)
        }
        return abs(seconds) <= 1
    }

    companion object {
        fun ofInstant(instant: Instant, offset: ZoneOffset = ZoneOffset.UTC) =
            DateTaken(LocalDateTime.ofInstant(instant, offset), offset)
    }
}

/** A WGS84 position. */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }
    }

    /**
     * True when the two points are within about 11 m (MP4's ISO 6709 tags keep 4 decimals),
     * ignoring altitude.
     */
    fun isSamePlaceAs(other: GeoLocation): Boolean =
        abs(latitude - other.latitude) <= LOCATION_TOLERANCE_DEGREES &&
            abs(longitude - other.longitude) <= LOCATION_TOLERANCE_DEGREES

    companion object {
        const val LOCATION_TOLERANCE_DEGREES = 0.00011
    }
}

/** What a file currently carries. */
data class MediaMetadata(
    val dateTaken: DateTaken?,
    val location: GeoLocation?,
    /**
     * True when the file has a location tag whose bytes were blanked out. Android does this
     * when a file is read by an app without the ACCESS_MEDIA_LOCATION permission, and the
     * blanked copy can end up saved or shared.
     */
    val locationRedacted: Boolean = false,
) {
    fun has(field: MetadataField): Boolean = when (field) {
        MetadataField.DATE_TAKEN -> dateTaken != null
        MetadataField.LOCATION -> location != null
    }

    companion object {
        val EMPTY = MediaMetadata(null, null)
    }
}

/** Values to write. A null field is left untouched. */
data class MetadataUpdate(
    val dateTaken: DateTaken? = null,
    val location: GeoLocation? = null,
) {
    val fields: Set<MetadataField>
        get() = buildSet {
            if (dateTaken != null) add(MetadataField.DATE_TAKEN)
            if (location != null) add(MetadataField.LOCATION)
        }

    val isEmpty: Boolean get() = dateTaken == null && location == null
}
