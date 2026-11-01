package com.eunho.tagohub.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun oneDegreeOfLatitudeIsAbout111Kilometers() {
        val meters = Geo.meters(35.0, 127.0, 36.0, 127.0)
        assertTrue(abs(meters - 111_195.0) < 200.0, "unexpected distance: $meters")
    }

    @Test
    fun bearingUsesNorthZeroAndEastNinety() {
        val north = Geo.bearing(0.0, 0.0, 1.0, 0.0)
        val east = Geo.bearing(0.0, 0.0, 0.0, 1.0)
        assertTrue(minOf(north, 360.0 - north) < 0.01, "north bearing: $north")
        assertTrue(abs(east - 90.0) < 0.01, "east bearing: $east")
    }

    @Test
    fun offsetMovesEastAtTheEquator() {
        val (lat, lng) = Geo.offset(0.0, 0.0, 90.0, 111_320.0)
        assertTrue(abs(lat) < 1e-6, "latitude: $lat")
        assertTrue(abs(lng - 1.0) < 1e-6, "longitude: $lng")
    }
}
