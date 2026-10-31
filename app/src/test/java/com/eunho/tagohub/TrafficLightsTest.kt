package com.eunho.tagohub

import com.eunho.tagohub.data.TrafficLights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLightsTest {
    private val text = "35.0000,127.0000,0\n35.0010,127.0010,1\nbad line\n35.0020,127.0020,0\n36.0000,128.0000,1\n"

    @Test
    fun parsesLinesSortedByLatitudeAndSkipsBadOnes() {
        val list = TrafficLights.parse(text)
        assertEquals(4, list.size)
        assertEquals(listOf(35.0, 35.001, 35.002, 36.0), list.map { it.lat })
        assertTrue(list[1].timer)
        assertTrue(!list[0].timer)
    }

    @Test
    fun boxReturnsOnlyPointsInside() {
        val all = TrafficLights.parse(text)
        val got = TrafficLights.inBox(all, 34.9995, 126.9995, 35.0015, 127.0015)
        assertEquals(listOf(35.0, 35.001), got.map { it.lat })
        assertTrue(TrafficLights.inBox(all, 40.0, 130.0, 41.0, 131.0).isEmpty())
        assertTrue(TrafficLights.inBox(emptyList(), 0.0, 0.0, 1.0, 1.0).isEmpty())
    }

    @Test
    fun tooManyAreCutToTheOnesNearestTheCenter() {
        val all = (0 until 1000).map { "35.%04d,127.0000,0".format(it) }.joinToString("\n").let(TrafficLights::parse)
        val got = TrafficLights.inBox(all, 35.0, 126.9, 35.1, 127.1, max = 10)
        assertEquals(10, got.size)
        assertTrue(got.all { it.lat in 35.04..35.06 })    // 가운데(35.05) 근처
    }
}
