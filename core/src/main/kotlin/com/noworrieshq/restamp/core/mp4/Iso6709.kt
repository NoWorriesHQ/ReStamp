package com.noworrieshq.restamp.core.mp4

import com.noworrieshq.restamp.core.model.GeoLocation
import java.util.Locale

/**
 * ISO 6709 point strings as used in MP4/QuickTime location tags, e.g. "+37.4219-122.0841/"
 * or "+37.4219-122.0841+012.500/".
 */
object Iso6709 {
    private val pattern = Regex("""^([+-][0-9]+(?:\.[0-9]+)?)([+-][0-9]+(?:\.[0-9]+)?)([+-][0-9]+(?:\.[0-9]+)?)?(?:CRS[^/]*)?/?$""")

    fun parse(text: String): GeoLocation? {
        val m = pattern.matchEntire(text.trim().trimEnd('\u0000')) ?: return null
        val lat = toDegrees(m.groupValues[1], degreeDigits = 2) ?: return null
        val lon = toDegrees(m.groupValues[2], degreeDigits = 3) ?: return null
        val alt = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoLocation(lat, lon, alt)
    }

    /** Same layout Android's MediaMuxer writes, so in-place updates usually keep the same size. */
    fun format(location: GeoLocation): String = buildString {
        append(String.format(Locale.US, "%+08.4f%+09.4f", location.latitude, location.longitude))
        location.altitudeMeters?.let { append(String.format(Locale.US, "%+.3f", it)) }
        append('/')
    }

    /** Handles ±DD.D, ±DDMM.M and ±DDMMSS.S (with 3 degree digits for longitude). */
    private fun toDegrees(value: String, degreeDigits: Int): Double? {
        val sign = if (value[0] == '-') -1.0 else 1.0
        val body = value.substring(1)
        val intPart = body.substringBefore('.')
        val frac = body.substring(intPart.length) // "" or ".xxx"
        return when (intPart.length) {
            degreeDigits, degreeDigits - 1 -> body.toDoubleOrNull()
            degreeDigits + 2 -> {
                val d = intPart.take(degreeDigits).toDouble()
                val min = (intPart.drop(degreeDigits) + frac).toDouble()
                d + min / 60.0
            }
            degreeDigits + 4 -> {
                val d = intPart.take(degreeDigits).toDouble()
                val min = intPart.substring(degreeDigits, degreeDigits + 2).toDouble()
                val sec = (intPart.drop(degreeDigits + 2) + frac).toDouble()
                d + min / 60.0 + sec / 3600.0
            }
            else -> null
        }?.let { it * sign }
    }
}
