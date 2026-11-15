package com.noworrieshq.restamp.core

import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.core.mp4.Iso6709
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso6709Test {
    @Test fun parsesDecimalDegrees() {
        val l = Iso6709.parse("+37.4219-122.0841/")!!
        assertEquals(37.4219, l.latitude, 1e-9)
        assertEquals(-122.0841, l.longitude, 1e-9)
        assertNull(l.altitudeMeters)
    }

    @Test fun parsesAltitudeAndCrs() {
        val l = Iso6709.parse("+27.5916+086.5640+8850CRSWGS_84/")!!
        assertEquals(8850.0, l.altitudeMeters!!, 1e-9)
    }

    @Test fun parsesDegreesMinutesSeconds() {
        val l = Iso6709.parse("+402646.302-0795855.903/")!!
        assertEquals(40.446195, l.latitude, 1e-5)
        assertEquals(-79.982195, l.longitude, 1e-5)
    }

    @Test fun formatsLikeMediaMuxer() {
        assertEquals("+37.4219-122.0841/", Iso6709.format(GeoLocation(37.42190, -122.08410)))
        assertEquals("-33.8568+151.2153/", Iso6709.format(GeoLocation(-33.8568, 151.2153)))
        assertEquals("+01.5000+003.2500+12.500/", Iso6709.format(GeoLocation(1.5, 3.25, 12.5)))
    }

    @Test fun rejectsGarbage() {
        assertNull(Iso6709.parse(""))
        assertNull(Iso6709.parse("hello"))
        assertNull(Iso6709.parse("+99.0000+000.0000/"))
    }
}
