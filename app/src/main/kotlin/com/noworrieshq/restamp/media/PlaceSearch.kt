package com.noworrieshq.restamp.media

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * City and place search with OpenStreetMap's free Nominatim service.
 *
 * Its usage policy asks for an identifying User-Agent, at most one request a second and no
 * search-as-you-type, so the app only searches when the person submits a query.
 * https://operations.osmfoundation.org/policies/nominatim/
 */
class PlaceSearch(private val userAgent: String) {

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    private var lastRequestAt = 0L

    /** Blocking; call off the main thread. */
    fun search(query: String, limit: Int = 8): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        waitForRateLimit()
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$limit&q=" +
                URLEncoder.encode(q, "UTF-8"),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            if (conn.responseCode != 200) throw IOException("Search failed (HTTP ${conn.responseCode})")
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    private fun waitForRateLimit() {
        val wait = lastRequestAt + 1_100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    companion object {
        /** Parses Nominatim's jsonv2 answer: an array of objects with lat, lon and display_name. */
        fun parse(json: String): List<Place> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                Place(o.optString("display_name", "%.4f, %.4f".format(lat, lon)), lat, lon)
            }
        }
    }
}
