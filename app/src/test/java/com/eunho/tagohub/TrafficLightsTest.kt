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
    fun lightsNearALiveSignalIntersectionAreLeftOut() {
        val all = TrafficLights.parse("35.00000,127.00000,0\n35.00040,127.00000,1\n35.00200,127.00000,0\n")   // 0m · 약 44m · 약 222m
        val skip = TrafficLights.nearAny(listOf(35.0 to 127.0), 70.0)
        val got = TrafficLights.inBox(all, 34.9, 126.9, 35.1, 127.1, skip = skip)
        assertEquals(listOf(35.002), got.map { it.lat })                  // 교차로 가운데 70m 안은 빠지고 222m 떨어진 것만 남음
        assertEquals(3, TrafficLights.inBox(all, 34.9, 126.9, 35.1, 127.1).size)   // skip 이 없으면 그대로
    }

    @Test
    fun nothingIsLeftOutWhenThereIsNoLiveSignal() {
        val all = TrafficLights.parse("35.0,127.0,0\n")
        assertEquals(1, TrafficLights.inBox(all, 34.9, 126.9, 35.1, 127.1, skip = TrafficLights.nearAny(emptyList(), 70.0)).size)
    }

    @Test
    fun skippedLightsDoNotUseUpTheCap() {
        // 같은 교차로 근처 점이 많아도 빠진 만큼 다른 점이 채워짐 (개수를 자르기 전에 뺌)
        val near = (0 until 20).joinToString("\n") { "35.0000,127.%05d,0".format(it) }
        val far = (0 until 5).joinToString("\n") { "35.0100,127.%05d,0".format(it * 100) }
        val all = TrafficLights.parse("$near\n$far")
        val got = TrafficLights.inBox(all, 34.9, 126.9, 35.1, 127.1, max = 5, skip = TrafficLights.nearAny(listOf(35.0 to 127.0), 70.0))
        assertEquals(5, got.size)
        assertTrue(got.all { it.lat > 35.005 })
    }

    @Test
    fun tooManyAreCutToTheOnesNearestTheCenter() {
        val all = (0 until 1000).map { "35.%04d,127.0000,0".format(it) }.joinToString("\n").let(TrafficLights::parse)
        val got = TrafficLights.inBox(all, 35.0, 126.9, 35.1, 127.1, max = 10)
        assertEquals(10, got.size)
        assertTrue(got.all { it.lat in 35.04..35.06 })    // 가운데(35.05) 근처
    }
}
