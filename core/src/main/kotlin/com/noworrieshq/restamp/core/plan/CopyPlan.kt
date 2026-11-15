package com.noworrieshq.restamp.core.plan

import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate

/** How one field compares between the original (source) and the edited copy (target). */
enum class FieldStatus {
    /** The original doesn't have it either, so there's nothing to copy. */
    MISSING_IN_SOURCE,
    /** The copy lacks it; safe to add. */
    WILL_ADD,
    /** Both already match. */
    ALREADY_SAME,
    /** The copy has a different value; copying needs the person's confirmation. */
    DIFFERENT_NEEDS_CONFIRMATION,
}

data class FieldComparison(
    val field: MetadataField,
    val status: FieldStatus,
    val source: MediaMetadata,
    val target: MediaMetadata,
)

/** What copying metadata from one file to another would do. Drives the comparison UI. */
data class CopyPlan(val fields: List<FieldComparison>) {

    val needsConfirmation: Set<MetadataField>
        get() = fields.filter { it.status == FieldStatus.DIFFERENT_NEEDS_CONFIRMATION }.map { it.field }.toSet()

    /** Fields that would change if the person confirms [confirmed] overwrites. */
    fun fieldsToWrite(confirmed: Set<MetadataField>): Set<MetadataField> =
        fields.filter {
            it.status == FieldStatus.WILL_ADD ||
                (it.status == FieldStatus.DIFFERENT_NEEDS_CONFIRMATION && it.field in confirmed)
        }.map { it.field }.toSet()

    /** The update to hand to a writer, along with the same [confirmed] set. */
    fun toUpdate(confirmed: Set<MetadataField>): MetadataUpdate {
        val chosen = fieldsToWrite(confirmed)
        val source = fields.firstOrNull()?.source ?: return MetadataUpdate()
        return MetadataUpdate(
            dateTaken = source.dateTaken.takeIf { MetadataField.DATE_TAKEN in chosen },
            location = source.location.takeIf { MetadataField.LOCATION in chosen },
        )
    }

    companion object {
        fun between(
            source: MediaMetadata,
            target: MediaMetadata,
            fields: Set<MetadataField> = MetadataField.entries.toSet(),
        ): CopyPlan = CopyPlan(fields.map { field ->
            val status = when (field) {
                MetadataField.DATE_TAKEN -> compare(source.dateTaken, target.dateTaken) { a, b -> a.isSameMomentAs(b) }
                MetadataField.LOCATION -> compare(source.location, target.location) { a, b -> a.isSamePlaceAs(b) }
            }
            FieldComparison(field, status, source, target)
        })

        private fun <T> compare(source: T?, target: T?, same: (T, T) -> Boolean): FieldStatus = when {
            source == null -> FieldStatus.MISSING_IN_SOURCE
            target == null -> FieldStatus.WILL_ADD
            same(source, target) -> FieldStatus.ALREADY_SAME
            else -> FieldStatus.DIFFERENT_NEEDS_CONFIRMATION
        }
    }
}
