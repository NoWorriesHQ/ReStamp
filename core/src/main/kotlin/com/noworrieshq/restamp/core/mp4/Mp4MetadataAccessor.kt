package com.noworrieshq.restamp.core.mp4

import com.noworrieshq.restamp.core.MalformedMediaException
import com.noworrieshq.restamp.core.MetadataAccessor
import com.noworrieshq.restamp.core.OverwriteGuard
import com.noworrieshq.restamp.core.UnsupportedMediaException
import com.noworrieshq.restamp.core.WriteResult
import com.noworrieshq.restamp.core.io.RandomAccessIo
import com.noworrieshq.restamp.core.model.DateTaken
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.model.MediaMetadata
import com.noworrieshq.restamp.core.model.MediaType
import com.noworrieshq.restamp.core.model.MetadataField
import com.noworrieshq.restamp.core.model.MetadataUpdate
import com.noworrieshq.restamp.core.mp4.BoxParser.u16
import com.noworrieshq.restamp.core.mp4.BoxParser.u32
import com.noworrieshq.restamp.core.mp4.BoxParser.u64
import java.time.Instant
import java.time.ZoneId

/**
 * Date taken and GPS for MP4 / QuickTime files.
 *
 * - Date taken is the creation time in `mvhd` (and, when writing, every `tkhd` and `mdhd`),
 *   stored as seconds since 1904-01-01 UTC.
 * - Location is the `©xyz` ISO 6709 string in `moov/udta`, which is what Pixel phones and
 *   Android's MediaMuxer write and what Google Photos reads. The QuickTime
 *   `com.apple.quicktime.location.ISO6709` key is read as a fallback.
 *
 * Writes never move media data. Small edits happen in place. When `moov` has to grow, it is
 * rewritten into adjacent `free` space when there is room, or else appended at the end of the
 * file with the old copy turned into `free`, so sample offsets stay valid.
 */
class Mp4MetadataAccessor(
    private val maxMoovBytes: Int = 64 * 1024 * 1024,
    /** Used only when a date has no UTC offset (e.g. from a JPEG without OffsetTime tags). */
    private val assumedZone: () -> ZoneId = { ZoneId.systemDefault() },
) : MetadataAccessor {

    override fun read(io: RandomAccessIo): MediaMetadata = parse(io).metadata

    override fun write(
        io: RandomAccessIo,
        update: MetadataUpdate,
        confirmedOverwrites: Set<MetadataField>,
    ): WriteResult {
        val file = parse(io)
        val (effective, result) = OverwriteGuard.effectiveUpdate(file.metadata, update, confirmedOverwrites)
        if (effective.isEmpty) return result

        val moov = file.moovBytes.copyOf()
        effective.dateTaken?.let { setCreationTime(moov, file.timeBoxes, it) }

        val location = effective.location
        if (location == null) {
            io.write(file.moov.offset, moov)
        } else {
            val newXyz = xyzBox(location)
            val slot = file.xyzSlot
            if (slot != null && slot.size.toInt() == newXyz.size && !file.hasOtherLocationTags) {
                newXyz.copyInto(moov, slot.offset.toInt())
                io.write(file.moov.offset, moov)
            } else {
                writeGrownMoov(io, file, rebuildWithLocation(moov, file, newXyz, location))
            }
        }
        io.sync()
        return result
    }

    // ---- Parsing ----------------------------------------------------------------------

    private class ParsedFile(
        val topLevel: List<Box>,
        val moov: Box,
        val moovBytes: ByteArray,
        /** Boxes inside [moovBytes] whose creation/modification times hold the date. */
        val timeBoxes: List<Box>,
        val udta: Box?,
        /** The existing `©xyz` box, or a blanked placeholder where one used to be. */
        val xyzSlot: Box?,
        /**
         * Location tags other than `udta/©xyz`: 3GPP `loci`, iTunes-style `udta/meta/ilst/©xyz`, or
         * Apple's `meta/keys` ISO6709. Other apps may prefer them, so a write updates or removes
         * them too, which needs the full rebuild.
         */
        val hasOtherLocationTags: Boolean,
        val metadata: MediaMetadata,
    )

    private fun parse(io: RandomAccessIo): ParsedFile {
        val top = BoxParser.topLevel(io)
        val ftyp = top.firstOrNull()
        if (ftyp == null || (ftyp.type != "ftyp" && ftyp.type !in MediaType.QUICKTIME_FIRST_BOXES)) {
            throw UnsupportedMediaException("Not an MP4 or QuickTime file")
        }
        val moov = top.firstOrNull { it.type == "moov" } ?: throw MalformedMediaException("No moov box; file may be incomplete")
        if (moov.size > maxMoovBytes) throw UnsupportedMediaException("moov box is too large (${moov.size} bytes)")
        val buf = io.read(moov.offset, moov.size.toInt())
        val inner = BoxParser.children(buf, moov.headerSize, buf.size)

        val mvhd = inner.firstOrNull { it.type == "mvhd" } ?: throw MalformedMediaException("No mvhd box")
        val timeBoxes = mutableListOf(mvhd)
        for (trak in inner.filter { it.type == "trak" }) {
            val trakKids = kids(buf, trak)
            trakKids.firstOrNull { it.type == "tkhd" }?.let { timeBoxes += it }
            trakKids.firstOrNull { it.type == "mdia" }?.let { mdia ->
                kids(buf, mdia).firstOrNull { it.type == "mdhd" }?.let { timeBoxes += it }
            }
        }

        val created = readCreationTime(buf, mvhd)
        val dateTaken = created?.let { DateTaken.ofInstant(it) }

        val udta = inner.firstOrNull { it.type == "udta" }
        var xyzSlot: Box? = null
        var location: GeoLocation? = null
        var redacted = false
        var lociLocation: GeoLocation? = null
        var hasLoci = false
        var ilstLocation: GeoLocation? = null
        var hasOtherTags = false
        var udtaKeysLocation: GeoLocation? = null
        // Normally one udta, but every one is checked so no old location is missed.
        for (anyUdta in inner.filter { it.type == "udta" }) {
            for (child in kids(buf, anyUdta)) {
                if (child.type == LOCI) {
                    hasLoci = true
                    if (lociLocation == null) lociLocation = parseLoci(buf, child)
                    continue
                }
                if (child.type == "meta") {
                    val item = ilstItems(buf, child).firstOrNull { it.type == XYZ }
                    if (item != null) {
                        hasOtherTags = true
                        if (ilstLocation == null) ilstLocation = dataText(buf, item)?.let(Iso6709::parse)
                    }
                    // ffmpeg puts Apple-style keys here rather than in moov/meta.
                    if (udtaKeysLocation == null) udtaKeysLocation = readKeysLocation(buf, child)
                    if (hasKeysLocation(buf, child)) hasOtherTags = true
                    continue
                }
                if (child.type == XYZ && xyzSlot?.type != XYZ && anyUdta == udta) {
                    xyzSlot = child
                    location = parseXyz(buf, child)
                    if (location == null && isAllZero(buf, child.payloadOffset.toInt(), child.end.toInt())) redacted = true
                    continue
                }
                if (xyzSlot == null && anyUdta == udta && isRedactedPlaceholder(buf, child)) {
                    xyzSlot = child
                    redacted = true
                }
            }
        }
        if (location == null) location = lociLocation
        if (location == null) location = ilstLocation
        val moovMeta = inner.firstOrNull { it.type == "meta" }
        val keysLocation = moovMeta?.let { readKeysLocation(buf, it) } ?: udtaKeysLocation
        if (location == null) location = keysLocation
        if (moovMeta != null && hasKeysLocation(buf, moovMeta)) hasOtherTags = true
        if (location != null) redacted = false

        val others = hasLoci || hasOtherTags || keysLocation != null || inner.count { it.type == "udta" } > 1
        return ParsedFile(top, moov, buf, timeBoxes, udta, xyzSlot, others, MediaMetadata(dateTaken, location, redacted))
    }

    private fun kids(buf: ByteArray, parent: Box): List<Box> =
        BoxParser.children(buf, parent.payloadOffset.toInt(), parent.end.toInt())

    private fun readCreationTime(buf: ByteArray, box: Box): Instant? {
        val p = box.payloadOffset.toInt()
        val seconds = if (buf[p].toInt() == 1) u64(buf, p + 4) else u32(buf, p + 4)
        if (seconds == 0L) return null
        return Instant.ofEpochSecond(seconds - EPOCH_1904_OFFSET)
    }

    /**
     * 3GPP TS 26.244 location: version/flags, language, name (null-terminated), role, then
     * longitude, latitude and altitude as signed 16.16 fixed point. Longitude comes first.
     */
    private fun parseLoci(buf: ByteArray, box: Box): GeoLocation? {
        var p = box.payloadOffset.toInt() + 4 + 2
        val end = box.end.toInt()
        if (p + 2 <= end && (buf[p].toInt() and 0xFF) == 0xFE && (buf[p + 1].toInt() and 0xFF) == 0xFF) {
            // UTF-16 name with BOM, ended by a 16-bit zero.
            p += 2
            while (p + 1 < end && (buf[p].toInt() != 0 || buf[p + 1].toInt() != 0)) p += 2
            p += 2
        } else {
            while (p < end && buf[p].toInt() != 0) p++
            p += 1
        }
        p += 1 // role
        if (p + 12 > end) return null
        val lon = u32(buf, p).toInt() / 65536.0
        val lat = u32(buf, p + 4).toInt() / 65536.0
        val alt = u32(buf, p + 8).toInt() / 65536.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoLocation(lat, lon, alt.takeIf { it != 0.0 })
    }

    /** QuickTime user-data text: 16-bit length, 16-bit language, then the string. */
    private fun parseXyz(buf: ByteArray, box: Box): GeoLocation? {
        val p = box.payloadOffset.toInt()
        if (box.end - p < 4) return null
        val len = u16(buf, p)
        val textEnd = minOf(p + 4 + len, box.end.toInt())
        return Iso6709.parse(String(buf, p + 4, textEnd - p - 4, Charsets.UTF_8))
    }

    /**
     * Android's location redaction zeroes the whole `©xyz` box, type included, but keeps its
     * size. A zero-typed, all-zero box inside `udta` is treated as that placeholder.
     */
    private fun isRedactedPlaceholder(buf: ByteArray, box: Box): Boolean =
        box.headerSize == 8 && box.type == "\u0000\u0000\u0000\u0000" &&
            isAllZero(buf, box.payloadOffset.toInt(), box.end.toInt())

    private fun isAllZero(buf: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (buf[i] != 0.toByte()) return false
        return true
    }

    /** Reads `com.apple.quicktime.location.ISO6709` from a QuickTime `meta`/`keys`/`ilst`. */
    private fun readKeysLocation(buf: ByteArray, meta: Box): GeoLocation? {
        val p = meta.payloadOffset.toInt()
        // QuickTime meta has no version/flags; ISO meta has 4 bytes of them first.
        val start = if (meta.end - p >= 8 && BoxParser.fourCc(buf, p + 4) == "hdlr") p else p + 4
        val children = BoxParser.children(buf, start, meta.end.toInt())
        val keys = children.firstOrNull { it.type == "keys" } ?: return null
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return null

        var k = keys.payloadOffset.toInt() + 4
        val count = u32(buf, k).toInt(); k += 4
        var locationIndex = -1
        for (i in 1..count) {
            if (k + 8 > keys.end) break
            val size = u32(buf, k).toInt()
            if (size < 8) break
            val name = String(buf, k + 8, size - 8, Charsets.UTF_8)
            if (name == APPLE_LOCATION_KEY) locationIndex = i
            k += size
        }
        if (locationIndex < 0) return null

        for (item in kids(buf, ilst)) {
            if (u32(buf, item.offset.toInt() + 4).toInt() != locationIndex) continue
            val data = kids(buf, item).firstOrNull { it.type == "data" } ?: return null
            val valueStart = data.payloadOffset.toInt() + 8
            return Iso6709.parse(String(buf, valueStart, data.end.toInt() - valueStart, Charsets.UTF_8))
        }
        return null
    }

    private fun hasKeysLocation(buf: ByteArray, meta: Box): Boolean {
        val keys = BoxParser.children(buf, metaChildrenStart(buf, meta), meta.end.toInt()).firstOrNull { it.type == "keys" }
            ?: return false
        return APPLE_LOCATION_KEY in keyNames(buf, keys)
    }

    /** Where a `meta` box's children start: QuickTime meta has no version/flags, ISO meta does. */
    private fun metaChildrenStart(buf: ByteArray, meta: Box): Int {
        val p = meta.payloadOffset.toInt()
        return if (meta.end - p >= 8 && BoxParser.fourCc(buf, p + 4) == "hdlr") p else p + 4
    }

    private fun ilstItems(buf: ByteArray, meta: Box): List<Box> {
        val ilst = BoxParser.children(buf, metaChildrenStart(buf, meta), meta.end.toInt()).firstOrNull { it.type == "ilst" }
            ?: return emptyList()
        return kids(buf, ilst)
    }

    /** The text in an ilst item's `data` box (after its 4-byte type and 4-byte locale). */
    private fun dataText(buf: ByteArray, item: Box): String? {
        val data = kids(buf, item).firstOrNull { it.type == "data" } ?: return null
        val start = data.payloadOffset.toInt() + 8
        if (start > data.end) return null
        return String(buf, start, data.end.toInt() - start, Charsets.UTF_8)
    }

    // ---- Writing ----------------------------------------------------------------------

    private fun setCreationTime(buf: ByteArray, boxes: List<Box>, date: DateTaken) {
        val instant = date.toInstant(assumedZone())
        val seconds = instant.epochSecond + EPOCH_1904_OFFSET
        require(seconds > 0) { "Dates before 1904 can't be stored in MP4" }
        for (box in boxes) {
            val p = box.payloadOffset.toInt()
            if (buf[p].toInt() == 1) {
                BoxParser.putU64(buf, p + 4, seconds)
                BoxParser.putU64(buf, p + 12, seconds)
            } else {
                if (seconds > 0xFFFFFFFFL) throw UnsupportedMediaException("Date is too late for this file's 32-bit time fields")
                BoxParser.putU32(buf, p + 4, seconds)
                BoxParser.putU32(buf, p + 8, seconds)
            }
        }
    }

    private fun xyzBox(location: GeoLocation): ByteArray {
        val text = Iso6709.format(location).toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4 + text.size)
        payload[0] = (text.size ushr 8).toByte(); payload[1] = text.size.toByte()
        payload[2] = (LANGUAGE_UND ushr 8).toByte(); payload[3] = LANGUAGE_UND.toByte()
        text.copyInto(payload, 4)
        return BoxParser.box(XYZ, payload)
    }

    /**
     * Returns a new `moov` whose `udta` starts with [newXyz]. Any other location tags are brought
     * in line: an old `©xyz` or placeholder, a 3GPP `loci` and an iTunes-style `udta/meta/ilst/©xyz`
     * are dropped, and Apple's `meta/keys` ISO6709 value is replaced with the new location.
     */
    private fun rebuildWithLocation(moov: ByteArray, file: ParsedFile, newXyz: ByteArray, location: GeoLocation): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(moov, 0, file.moov.headerSize)
        var wroteUdta = false
        val fixLocation = { item: Box, key: String? ->
            when {
                item.type == XYZ -> ByteArray(0)
                key == APPLE_LOCATION_KEY -> replaceItemText(moov, item, Iso6709.format(location))
                else -> null
            }
        }
        for (child in BoxParser.children(moov, file.moov.headerSize, moov.size)) {
            when (child.type) {
                "udta" -> { out.write(rebuildUdta(moov, child, if (wroteUdta) null else newXyz, fixLocation)); wroteUdta = true }
                "meta" -> out.write(rewriteMeta(moov, child, fixLocation))
                else -> out.write(moov, child.offset.toInt(), child.size.toInt())
            }
        }
        if (!wroteUdta) out.write(BoxParser.box("udta", newXyz))
        val result = out.toByteArray()
        if (file.moov.headerSize == 16) BoxParser.putU64(result, 8, result.size.toLong())
        else BoxParser.putU32(result, 0, result.size.toLong())
        return result
    }

    private fun rebuildUdta(buf: ByteArray, udta: Box, newXyz: ByteArray?, fixLocation: (Box, String?) -> ByteArray?): ByteArray {
        val payload = java.io.ByteArrayOutputStream()
        newXyz?.let(payload::write)
        for (child in kids(buf, udta)) {
            when {
                // Old copies of the location would contradict the new one.
                child.type == XYZ || child.type == LOCI || isRedactedPlaceholder(buf, child) -> Unit
                child.type == "meta" -> payload.write(rewriteMeta(buf, child, fixLocation))
                else -> payload.write(buf, child.offset.toInt(), child.size.toInt())
            }
        }
        return BoxParser.box("udta", payload.toByteArray())
    }

    /**
     * Copies a `meta` box, letting [replace] swap any `ilst` item: it gets the item and its
     * `keys` name (if any) and returns the replacement bytes, empty to drop it, or null to keep it.
     */
    private fun rewriteMeta(buf: ByteArray, meta: Box, replace: (item: Box, key: String?) -> ByteArray?): ByteArray {
        val start = metaChildrenStart(buf, meta)
        val children = BoxParser.children(buf, start, meta.end.toInt())
        val keyNames = children.firstOrNull { it.type == "keys" }?.let { keyNames(buf, it) }.orEmpty()
        val payload = java.io.ByteArrayOutputStream()
        payload.write(buf, meta.payloadOffset.toInt(), start - meta.payloadOffset.toInt())
        for (child in children) {
            if (child.type != "ilst") {
                payload.write(buf, child.offset.toInt(), child.size.toInt())
                continue
            }
            val items = java.io.ByteArrayOutputStream()
            for (item in kids(buf, child)) {
                val key = keyNames.getOrNull(u32(buf, item.offset.toInt() + 4).toInt() - 1)
                val replacement = replace(item, key)
                if (replacement == null) items.write(buf, item.offset.toInt(), item.size.toInt()) else items.write(replacement)
            }
            payload.write(BoxParser.box("ilst", items.toByteArray()))
        }
        return BoxParser.box("meta", payload.toByteArray())
    }

    private fun keyNames(buf: ByteArray, keys: Box): List<String> {
        val names = mutableListOf<String>()
        var k = keys.payloadOffset.toInt() + 4
        val count = u32(buf, k).toInt(); k += 4
        repeat(count) {
            if (k + 8 > keys.end) return names
            val size = u32(buf, k).toInt()
            if (size < 8 || k + size > keys.end) return names
            names += String(buf, k + 8, size - 8, Charsets.UTF_8)
            k += size
        }
        return names
    }

    /** An ilst item with its `data` value replaced by [text], keeping the data type and locale. */
    private fun replaceItemText(buf: ByteArray, item: Box, text: String): ByteArray {
        val data = kids(buf, item).firstOrNull { it.type == "data" }
            ?: return buf.copyOfRange(item.offset.toInt(), item.end.toInt())
        val p = data.payloadOffset.toInt()
        val newData = BoxParser.box("data", buf.copyOfRange(p, p + 8) + text.toByteArray(Charsets.UTF_8))
        val payload = java.io.ByteArrayOutputStream()
        for (child in kids(buf, item)) {
            if (child === data) payload.write(newData) else payload.write(buf, child.offset.toInt(), child.size.toInt())
        }
        return BoxParser.box(BoxParser.fourCc(buf, item.offset.toInt() + 4), payload.toByteArray())
    }

    private fun writeGrownMoov(io: RandomAccessIo, file: ParsedFile, newMoov: ByteArray) {
        val moov = file.moov
        val index = file.topLevel.indexOf(moov)
        val next = file.topLevel.getOrNull(index + 1)
        val followingFree = next?.takeIf { (it.type == "free" || it.type == "skip") && !it.extendsToEof }
        val available = moov.size + (followingFree?.size ?: 0L)
        val remainder = available - newMoov.size

        // 1. Fits in the old moov plus any free box right after it.
        if (remainder == 0L || remainder >= 8) {
            io.write(moov.offset, newMoov)
            if (remainder > 0) io.write(moov.offset + newMoov.size, BoxParser.freeBox(remainder.toInt()))
            return
        }
        // 2. moov is the last box: rewrite it and fix the file length.
        if (next == null) {
            io.write(moov.offset, newMoov)
            io.truncate(moov.offset + newMoov.size)
            return
        }
        // 3. Append a new moov at the end and retire the old one as free space.
        if (file.topLevel.any { it.type == "moof" }) {
            throw UnsupportedMediaException("Fragmented MP4 needs a full rewrite to add a location; not supported yet")
        }
        val last = file.topLevel.last()
        if (last.extendsToEof) {
            if (last.size > 0xFFFFFFFFL) throw UnsupportedMediaException("Can't make room after an open-ended box over 4 GB")
            val sizeField = ByteArray(4).also { BoxParser.putU32(it, 0, last.size) }
            io.write(last.offset, sizeField)
        }
        val end = io.size
        io.write(end, newMoov)
        io.sync()
        // Only after the new copy is safely on disk does the old one stop being "moov".
        io.write(moov.offset + 4, "free".toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        /** Seconds between 1904-01-01 and 1970-01-01. */
        const val EPOCH_1904_OFFSET = 2_082_844_800L
        const val XYZ = "©xyz"
        const val LOCI = "loci"
        const val APPLE_LOCATION_KEY = "com.apple.quicktime.location.ISO6709"
        /** Packed ISO 639-2 code "und", as Android writes it. */
        private const val LANGUAGE_UND = 0x15C7
    }
}
