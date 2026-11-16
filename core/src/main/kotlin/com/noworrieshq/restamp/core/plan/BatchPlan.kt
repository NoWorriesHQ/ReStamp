package com.noworrieshq.restamp.core.plan

import com.noworrieshq.restamp.core.OverwriteGuard
import com.noworrieshq.restamp.core.OverwriteNotConfirmedException
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate

/** One write to run: [update] goes into the file [id], with [confirmed] overwrites allowed. */
data class CopyJob(val id: String, val update: MetadataUpdate, val confirmed: Set<MetadataField>)

/**
 * Copying tags for many pairs at once. Missing tags are always added; tags the edited copy
 * already has with a different value are only replaced for the fields the person confirms.
 */
class BatchPlan(val items: Map<String, CopyPlan>) {

    /** Pairs where at least one tag can be added without overwriting anything. */
    fun canAdd(field: MetadataField): Int = items.values.count { p -> p.statusOf(field) == FieldStatus.WILL_ADD }

    /** Pairs where the edited copy has a different value for [field] than the original. */
    fun differing(field: MetadataField): Int =
        items.values.count { p -> p.statusOf(field) == FieldStatus.DIFFERENT_NEEDS_CONFIRMATION }

    val needsConfirmation: Set<MetadataField>
        get() = MetadataField.entries.filter { differing(it) > 0 }.toSet()

    /** Pairs that are already complete, or whose original has nothing to give. */
    val nothingToDo: Int
        get() = items.values.count { it.fieldsToWrite(MetadataField.entries.toSet()).isEmpty() }

    /**
     * The writes to run when copying [fields] and allowing overwrites of [overwrite].
     * Pairs left with nothing to write are skipped.
     */
    fun jobs(
        fields: Set<MetadataField> = MetadataField.entries.toSet(),
        overwrite: Set<MetadataField> = emptySet(),
    ): List<CopyJob> = items.mapNotNull { (id, plan) ->
        val confirmed = overwrite intersect fields intersect plan.needsConfirmation
        val chosen = plan.fieldsToWrite(confirmed) intersect fields
        val full = plan.toUpdate(confirmed)
        val update = MetadataUpdate(
            dateTaken = full.dateTaken.takeIf { MetadataField.DATE_TAKEN in chosen },
            location = full.location.takeIf { MetadataField.LOCATION in chosen },
        )
        if (update.isEmpty) null else CopyJob(id, update, confirmed)
    }

    private fun CopyPlan.statusOf(field: MetadataField) = fields.firstOrNull { it.field == field }?.status
}

/** Fields of [update] that would replace a different value already in [current]. Never throws. */
fun overwritesIn(current: MediaMetadata, update: MetadataUpdate): Set<MetadataField> = try {
    OverwriteGuard.effectiveUpdate(current, update, emptySet())
    emptySet()
} catch (e: OverwriteNotConfirmedException) {
    e.fields
}
