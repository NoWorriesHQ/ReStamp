package com.noworrieshq.restamp.core.jpeg

import com.noworrieshq.restamp.core.model.DateTaken
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Converts between [DateTaken] and the EXIF text tags:
 * DateTimeOriginal ("yyyy:MM:dd HH:mm:ss"), OffsetTimeOriginal ("+11:00") and
 * SubSecTimeOriginal ("818").
 */
object ExifDateCodec {
    private val formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    data class ExifDateTags(val dateTime: String, val offset: String?, val subSec: String?)

    fun decode(dateTime: String?, offset: String?, subSec: String?): DateTaken? {
        val text = dateTime?.trim()?.trimEnd('\u0000')
        if (text.isNullOrEmpty() || text.startsWith("0000")) return null
        val local = try {
            LocalDateTime.parse(text, formatter)
        } catch (e: DateTimeParseException) {
            return null
        }
        val nanos = subSec?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.take(9)?.padEnd(9, '0')?.toInt() ?: 0
        val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try { ZoneOffset.of(it) } catch (e: Exception) { null }
        }
        return DateTaken(local.withNano(nanos), zone)
    }

    fun encode(date: DateTaken): ExifDateTags {
        val millis = date.localDateTime.nano / 1_000_000
        return ExifDateTags(
            dateTime = date.localDateTime.format(formatter),
            offset = date.utcOffset?.let { formatOffset(it) },
            subSec = if (date.localDateTime.nano == 0) null else "%03d".format(millis),
        )
    }

    /** EXIF wants "+HH:MM", and ZoneOffset.UTC.id is "Z". */
    private fun formatOffset(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) '-' else '+'
        val abs = kotlin.math.abs(total)
        return "%c%02d:%02d".format(sign, abs / 3600, (abs % 3600) / 60)
    }
}
