package com.eunho.tagohub

import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.widget.WidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTextTest {
    private fun stop(i: Int) = Favorite(Favorite.Kind.Stop, "S$i", "정류장$i", "$i", "25")
    private fun route(i: Int) = Favorite(Favorite.Kind.Route, "R$i", "노선$i", null, "25")
    private fun arr(no: String, sec: Int?, prev: Int? = null) = BusArrival("r$no", no, null, null, prev, sec)

    @Test
    fun pickStopsKeepsOnlyStopsInOrderUpToThree() {
        val favs = listOf(route(1), stop(1), stop(2), route(2), stop(3), stop(4))
        assertEquals(listOf("S1", "S2", "S3"), WidgetText.pickStops(favs).map { it.id })
        assertTrue(WidgetText.pickStops(listOf(route(1))).isEmpty())
        assertTrue(WidgetText.pickStops(emptyList()).isEmpty())
    }

    @Test
    fun arrivalLinesShowTheTwoSoonestBuses() {
        val text = WidgetText.arrivalLines(listOf(arr("300", 600, 5), arr("5", 80, 1), arr("12", 25), arr("99", null)))
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals("12번  곧 도착", lines[0])
        assertTrue(lines[1], lines[1].startsWith("5번  1분 20초") && "1정류장 전" in lines[1])
    }

    @Test
    fun arrivalLinesHandleEmptyAndUnknownTimes() {
        assertEquals("도착 정보 없음", WidgetText.arrivalLines(emptyList()))
        assertEquals("7번  정보없음", WidgetText.arrivalLines(listOf(arr("7", null))))
        assertEquals("불러오지 못했어요", WidgetText.failed())
    }
}
