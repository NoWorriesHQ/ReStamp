package com.noworrieshq.restamp.core.match

import java.time.LocalDateTime

/** Broad kind of a file, from its extension. Only files of the same kind are paired automatically. */
enum class MediaKind {
    IMAGE, VIDEO, OTHER;

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "jpe", "heic", "heif", "png", "webp", "dng", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")

        fun ofExtension(extension: String): MediaKind = when (extension.lowercase()) {
            in IMAGE_EXTENSIONS -> IMAGE
            in VIDEO_EXTENSIONS -> VIDEO
            else -> OTHER
        }
    }
}

/**
 * A capture time written into a file name by the camera.
 *
 * Pixel's `PXL_` names use UTC; older `IMG_`/`VID_` style names use the phone's local time,
 * so the two are only compared with names of the same style.
 */
data class NameTime(val dateTime: LocalDateTime, val isUtc: Boolean)

/**
 * A file name broken down for matching an edited copy back to its original.
 *
 * [keys] is the name stripped one edit suffix at a time, longest first, for example
 * `pxl_20241005_143012345-edited (1)` → `pxl_20241005_143012345-edited` → `pxl_20241005_143012345`.
 */
class FileName private constructor(
    val fileName: String,
    val extension: String,
    val kind: MediaKind,
    /** Lower-cased name without extension, before anything is stripped. */
    val stem: String,
    val keys: List<String>,
    /** Camera-style prefix and timestamp, such as `pxl_20241005_143012345`, when present. */
    val cameraKey: String?,
    val time: NameTime?,
    /** True when something follows the camera timestamp that isn't one of Pixel's own tags. */
    private val hasTextAfterCameraKey: Boolean,
    /** Strip steps that were a clear edit or copy marker, as opposed to a bare trailing number. */
    private val markedEdits: Int,
) {
    /** The name with only Pixel's own tags (such as `.MP`) removed. */
    val baseName: String get() = keys.first()

    /** The name with every recognised edit suffix removed. */
    val coreName: String get() = keys.last()

    /** Roughly how many times the file looks to have been edited or copied. 0 looks like a camera original. */
    val editLevel: Int get() = maxOf(markedEdits, if (hasTextAfterCameraKey) 1 else 0)

    override fun toString() = fileName

    companion object {
        /**
         * Pixel adds dot tags to the camera name, e.g. `PXL_…​.MP.jpg` (motion photo),
         * `PXL_…​.NIGHT.jpg`, `PXL_…​.RAW-01.MP.COVER.jpg`. They describe the shot, not an edit.
         */
        private val PIXEL_TAGS = Regex(
            """\.(mp|night|portrait|ts|original|cover|raw|long_exposure|motion|vr|photosphere|panorama|pano|lens|top_shot|ls)(-\d+)?(?=\.|$|[\s_~(-])""",
        )

        private val CAMERA_NAME = Regex("""^(pxl|img|vid|mvimg|pano|burst)?[_-]?(\d{8})[_-](\d{6})(\d{1,3})?""")

        /** Suffixes editors and file managers add. Each one is stripped as a separate step. */
        private val EDIT_SUFFIXES = listOf(
            Regex("""\s*\(\d+\)$"""), // "name (1)", file managers and downloads
            Regex("""~\d+$"""), // "name~2", Google Photos and Android duplicates
            Regex(
                """[\s._-]+(edited|edit|edits|bearbeitet|modifie|modifié|editado|copy|copie|kopie|export|exported|retouched|snapseed|lightroom|lr|ps|crop|cropped|filtered|collage|final)([\s_-]*\d+)?$""",
            ),
            Regex("""[\s_-]+\d{1,3}$"""), // "name-01", "name_1", "name 2"
        )
        private val COPY_OF_PREFIX = Regex("""^(copy of|kopie von|copie de)\s+""")

        fun parse(fileName: String): FileName {
            val dot = fileName.lastIndexOf('.')
            val rawExtension = if (dot > 0) fileName.substring(dot + 1) else ""
            val hasMediaExtension = rawExtension.isNotEmpty() && MediaKind.ofExtension(rawExtension) != MediaKind.OTHER
            val extension = if (hasMediaExtension) rawExtension.lowercase() else ""
            val stem = (if (hasMediaExtension) fileName.substring(0, dot) else fileName).trim().lowercase()

            val untagged = PIXEL_TAGS.replace(stem, "").trim()

            val keys = mutableListOf(untagged.ifEmpty { stem })
            var markedEdits = 0
            while (true) {
                val current = keys.last()
                val (next, marked) = stripOne(current) ?: break
                if (next.isEmpty() || next == current) break
                keys += next
                if (marked) markedEdits++
            }

            val camera = CAMERA_NAME.find(untagged)
            val time = camera?.let { parseTime(it) }
            val cameraKey = if (camera != null && time != null) {
                val (prefix, date, hms, millis) = camera.destructured
                listOf(prefix.ifEmpty { "cam" }, date, hms + millis).joinToString("_")
            } else {
                null
            }
            val afterCamera = if (camera != null && time != null) untagged.substring(camera.range.last + 1).trim() else ""

            return FileName(
                fileName = fileName,
                extension = extension,
                kind = MediaKind.ofExtension(extension),
                stem = stem,
                keys = keys,
                cameraKey = cameraKey,
                time = time,
                hasTextAfterCameraKey = afterCamera.isNotEmpty(),
                markedEdits = markedEdits,
            )
        }

        /** Removes one edit marker. The flag is false for a bare trailing number, which originals have too. */
        private fun stripOne(name: String): Pair<String, Boolean>? {
            COPY_OF_PREFIX.find(name)?.let { return name.substring(it.range.last + 1).trim() to true }
            for ((i, suffix) in EDIT_SUFFIXES.withIndex()) {
                val m = suffix.find(name) ?: continue
                return name.substring(0, m.range.first).trimEnd(' ', '_', '-', '.') to (i != EDIT_SUFFIXES.lastIndex)
            }
            return null
        }

        private fun parseTime(m: kotlin.text.MatchResult): NameTime? {
            val (prefix, date, hms, millis) = m.destructured
            return runCatching {
                val dt = LocalDateTime.of(
                    date.substring(0, 4).toInt(), date.substring(4, 6).toInt(), date.substring(6, 8).toInt(),
                    hms.substring(0, 2).toInt(), hms.substring(2, 4).toInt(), hms.substring(4, 6).toInt(),
                    if (millis.isEmpty()) 0 else millis.padEnd(3, '0').toInt() * 1_000_000,
                )
                if (dt.year < 1990 || dt.year > 2100) null else NameTime(dt, isUtc = prefix == "pxl")
            }.getOrNull()
        }

        /** Treats jpg/jpeg/jpe as the same, and so on, for tie-breaking. */
        internal fun extensionGroup(extension: String): String = when (extension) {
            "jpg", "jpeg", "jpe" -> "jpeg"
            "heic", "heif" -> "heif"
            "mp4", "m4v" -> "mp4"
            else -> extension
        }
    }
}
