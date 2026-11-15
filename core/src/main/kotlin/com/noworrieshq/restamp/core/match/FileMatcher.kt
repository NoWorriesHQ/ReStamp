package com.noworrieshq.restamp.core.match

import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A file in one of the picked folders. [id] is whatever identifies it to the app (a content URI). */
data class MediaFile(val id: String, val name: String) {
    val parsed: FileName by lazy { FileName.parse(name) }
}

/** Why two files were paired, strongest first. */
enum class MatchConfidence {
    /** Same file name, ignoring case and jpg/jpeg style differences. */
    EXACT_NAME,
    /** Same name once edit suffixes such as `-edited`, `~2` or ` (1)` are removed. */
    SAME_BASE_NAME,
    /** Different names that carry the same camera capture time. Worth a second look. */
    SAME_CAPTURE_TIME,
    /** Paired by the person. */
    MANUAL,
}

data class FilePair(val source: MediaFile, val target: MediaFile, val confidence: MatchConfidence)

/** An edited copy that fits more than one original equally well. The person picks one. */
data class AmbiguousMatch(val target: MediaFile, val candidates: List<MediaFile>)

data class MatchResult(
    val pairs: List<FilePair>,
    val ambiguous: List<AmbiguousMatch>,
    /** Edited copies with no original found. */
    val unmatchedTargets: List<MediaFile>,
    /** Originals no edited copy was paired with. Usually fine: not every photo gets edited. */
    val unmatchedSources: List<MediaFile>,
) {
    fun pairFor(targetId: String): FilePair? = pairs.firstOrNull { it.target.id == targetId }
}

/**
 * The person's own pairing decisions, which always win over automatic matching.
 * Keyed by file id so they survive a folder rescan.
 */
data class ManualChoices(
    /** Edited copy id → original id. */
    val paired: Map<String, String> = emptyMap(),
    /** Edited copies the person said have no original, even if one was found. */
    val unpaired: Set<String> = emptySet(),
) {
    fun pair(targetId: String, sourceId: String) = ManualChoices(paired + (targetId to sourceId), unpaired - targetId)
    fun unpair(targetId: String) = ManualChoices(paired - targetId, unpaired + targetId)
    /** Goes back to automatic matching for this file. */
    fun reset(targetId: String) = ManualChoices(paired - targetId, unpaired - targetId)

    val isEmpty: Boolean get() = paired.isEmpty() && unpaired.isEmpty()
}

/**
 * Pairs edited copies (targets) with the originals (sources) they came from, by file name.
 *
 * Each edited copy gets at most one original; one original can feed several edited copies.
 * Both lists may come from the same folder (Pixel saves edits next to the originals); then a
 * file is only paired with one that looks less edited than itself, and never with itself.
 */
object FileMatcher {

    fun match(sources: List<MediaFile>, targets: List<MediaFile>, manual: ManualChoices = ManualChoices()): MatchResult {
        val sourceIds = sources.mapTo(HashSet()) { it.id }
        val sameFolder = targets.any { it.id in sourceIds }
        val sourcesById = sources.associateBy { it.id }
        val index = SourceIndex(sources)

        val pairs = mutableListOf<FilePair>()
        val ambiguous = mutableListOf<AmbiguousMatch>()
        val unmatched = mutableListOf<MediaFile>()

        for (target in targets) {
            val manualSource = manual.paired[target.id]?.let { sourcesById[it] }
            when {
                manualSource != null -> pairs += FilePair(manualSource, target, MatchConfidence.MANUAL)
                target.id in manual.unpaired -> unmatched += target
                else -> {
                    val best = bestCandidates(target, index, sameFolder)
                    when {
                        best == null -> unmatched += target
                        best.second.size == 1 -> pairs += FilePair(best.second.single(), target, best.first)
                        else -> ambiguous += AmbiguousMatch(target, best.second.sortedBy { it.name.lowercase() })
                    }
                }
            }
        }

        val usedSourceIds = pairs.mapTo(HashSet()) { it.source.id }
        val targetsInUse = (pairs.map { it.target.id } + ambiguous.map { it.target.id }).toSet()
        val manuallyUnpaired = manual.unpaired

        // In one shared folder every file is in both lists. Keep each one on the side it belongs to.
        val unmatchedTargets = unmatched.filterNot { t ->
            t.id in usedSourceIds ||
                (sameFolder && t.id in sourceIds && t.parsed.editLevel == 0 && t.id !in manuallyUnpaired)
        }
        val shownAsTargets = unmatchedTargets.mapTo(HashSet()) { it.id }
        val unmatchedSources = sources.filterNot { s ->
            s.id in usedSourceIds || s.id in targetsInUse || s.id in shownAsTargets
        }

        val byName = compareBy<MediaFile> { it.name.lowercase() }
        return MatchResult(
            pairs = pairs.sortedWith(compareBy(byName) { it.target }),
            ambiguous = ambiguous.sortedWith(compareBy(byName) { it.target }),
            unmatchedTargets = unmatchedTargets.sortedWith(byName),
            unmatchedSources = unmatchedSources.sortedWith(byName),
        )
    }

    /**
     * Originals ranked by how likely they are to be the source of [target], best first, for
     * pairing by hand. Same kind of file first, then closest name, then closest capture time.
     */
    fun suggestions(target: MediaFile, sources: List<MediaFile>, limit: Int = 5): List<MediaFile> {
        val t = target.parsed
        return sources.asSequence()
            .filter { it.id != target.id }
            .map { s -> s to suggestionScore(t, s.parsed) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    // ---- automatic matching ----

    private class Candidate(val source: MediaFile, val rank: Int, val confidence: MatchConfidence)

    /** Looks sources up by each kind of key instead of comparing every pair of files. */
    private class SourceIndex(sources: List<MediaFile>) {
        val byStem = sources.groupBy { it.parsed.stem }
        val byBase = sources.groupBy { it.parsed.baseName }
        val byCamera = sources.filter { it.parsed.cameraKey != null }.groupBy { it.parsed.cameraKey!! }
        val bySecond = sources.filter { it.parsed.time != null }.groupBy { secondKey(it.parsed.time!!) }
    }

    private fun bestCandidates(target: MediaFile, index: SourceIndex, sameFolder: Boolean): Pair<MatchConfidence, List<MediaFile>>? {
        val t = target.parsed
        if (t.kind == MediaKind.OTHER) return null
        val found = mutableListOf<Candidate>()

        fun consider(sources: List<MediaFile>?, rank: Int, confidence: MatchConfidence) {
            sources?.forEach { s ->
                val p = s.parsed
                if (s.id == target.id || p.kind != t.kind) return@forEach
                // An original shouldn't look more edited than its edited copy.
                if (if (sameFolder) p.editLevel >= t.editLevel else p.editLevel > t.editLevel) return@forEach
                val extensionPenalty = if (FileName.extensionGroup(p.extension) == FileName.extensionGroup(t.extension)) 0 else 1
                found += Candidate(s, rank * 2 + extensionPenalty, confidence)
            }
        }

        consider(index.byStem[t.stem], 0, MatchConfidence.EXACT_NAME)
        t.keys.forEachIndexed { i, key -> consider(index.byBase[key], 10 + i, MatchConfidence.SAME_BASE_NAME) }
        t.cameraKey?.let { consider(index.byCamera[it], 50, MatchConfidence.SAME_BASE_NAME) }
        t.time?.let { consider(index.bySecond[secondKey(it)], 80, MatchConfidence.SAME_CAPTURE_TIME) }

        if (found.isEmpty()) return null
        val bestRank = found.minOf { it.rank }
        val best = found.filter { it.rank == bestRank }
        val sources = best.map { it.source }.distinctBy { it.id }
        return best.first().confidence to sources
    }

    private fun secondKey(time: NameTime) = "${time.isUtc}:${time.dateTime.truncatedTo(ChronoUnit.SECONDS)}"

    // ---- suggestions for manual pairing ----

    private fun suggestionScore(t: FileName, s: FileName): Double {
        var score = 0.0
        if (s.kind == t.kind) score += 10.0
        score += 5.0 * similarity(t.coreName, s.coreName)
        val a = t.time
        val b = s.time
        if (a != null && b != null && a.isUtc == b.isUtc) {
            val minutes = abs(Duration.between(a.dateTime, b.dateTime).toMinutes()).toDouble()
            score += 3.0 / (1.0 + minutes)
        }
        return score
    }

    /** 1.0 for identical strings, falling towards 0 as more characters differ. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
