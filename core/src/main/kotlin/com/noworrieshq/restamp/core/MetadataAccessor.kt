package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.io.RandomAccessIo
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate

/** Reads and writes date taken and GPS for one container format. */
interface MetadataAccessor {
    fun read(io: RandomAccessIo): MediaMetadata

    /**
     * Writes [update] into the file.
     *
     * Tags the file does not have yet are added. A tag that already exists with a different
     * value is replaced only when its field is in [confirmedOverwrites]; otherwise nothing at
     * all is written and [OverwriteNotConfirmedException] is thrown.
     */
    fun write(
        io: RandomAccessIo,
        update: MetadataUpdate,
        confirmedOverwrites: Set<MetadataField> = emptySet(),
    ): WriteResult
}

data class WriteResult(
    /** Fields whose value changed in the file. */
    val written: Set<MetadataField>,
    /** Fields that already held the requested value, so they were left alone. */
    val alreadyMatching: Set<MetadataField>,
)

/** Shared "never overwrite without confirmation" rule, used by every accessor. */
object OverwriteGuard {
    /**
     * Returns the part of [update] that actually needs writing, or throws when an existing,
     * different tag would be replaced without confirmation.
     */
    fun effectiveUpdate(
        current: MediaMetadata,
        update: MetadataUpdate,
        confirmedOverwrites: Set<MetadataField>,
    ): Pair<MetadataUpdate, WriteResult> {
        val unconfirmed = mutableSetOf<MetadataField>()
        val matching = mutableSetOf<MetadataField>()

        val date = update.dateTaken?.let { wanted ->
            val existing = current.dateTaken
            when {
                existing == null -> wanted
                existing.isSameMomentAs(wanted) -> { matching += MetadataField.DATE_TAKEN; null }
                MetadataField.DATE_TAKEN in confirmedOverwrites -> wanted
                else -> { unconfirmed += MetadataField.DATE_TAKEN; null }
            }
        }
        val location = update.location?.let { wanted ->
            // A redacted (blanked) location reads as null, so it counts as missing.
            val existing = current.location
            when {
                existing == null -> wanted
                existing.isSamePlaceAs(wanted) &&
                    (wanted.altitudeMeters == null || existing.altitudeMeters != null) -> {
                    matching += MetadataField.LOCATION; null
                }
                MetadataField.LOCATION in confirmedOverwrites -> wanted
                else -> { unconfirmed += MetadataField.LOCATION; null }
            }
        }
        if (unconfirmed.isNotEmpty()) throw OverwriteNotConfirmedException(unconfirmed)
        val effective = MetadataUpdate(date, location)
        return effective to WriteResult(effective.fields, matching)
    }
}
