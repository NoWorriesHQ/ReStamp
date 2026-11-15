package com.noworrieshq.restamp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceSearchTest {
    @Test fun parsesNominatimResults() {
        val json = """
            [
              {"place_id": 1, "lat": "-33.8698439", "lon": "151.2082848", "display_name": "Sydney, New South Wales, Australia"},
              {"place_id": 2, "lat": "not a number", "lon": "1", "display_name": "Broken"},
              {"place_id": 3, "lat": "51.5074456", "lon": "-0.1277653"}
            ]
        """.trimIndent()
        val places = PlaceSearch.parse(json)
        assertEquals(2, places.size)
        assertEquals("Sydney, New South Wales, Australia", places[0].name)
        assertEquals(-33.8698439, places[0].latitude, 1e-9)
        assertEquals(151.2082848, places[0].longitude, 1e-9)
        assertTrue(places[1].name.startsWith("51.5074"))
    }

    @Test fun emptyAnswer() {
        assertTrue(PlaceSearch.parse("[]").isEmpty())
    }
}
