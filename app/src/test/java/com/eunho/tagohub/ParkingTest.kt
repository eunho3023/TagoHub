package com.eunho.tagohub

import com.eunho.tagohub.data.Parking
import org.junit.Assert.assertEquals
import org.junit.Test

class ParkingTest {
    /** 서버 parking/<칸>.json → 지도 점 (웹 site/js/parking.js 와 같은 칸·글자) */
    @Test
    fun tileParseAndFeature() {
        assertEquals("187_634", Parking.tile(37.5336, 126.9727))
        assertEquals(listOf("186_634", "186_635", "187_634", "187_635"), Parking.tilesIn(37.39, 126.99, 37.41, 127.01))
        val lots = Parking.parseTile("187_634", """[["삼각지임시",37.533578,126.972694,169,"공영 노외","5분 250원","24시간","02-749-5314"],["x"]]""")
        assertEquals(1, lots.size)
        val f = Parking.feature(lots[0])
        assertEquals("parking:187_634:0", f.id)
        assertEquals("삼각지임시", f.title)
        assertEquals("공영 노외 · 169면 · 5분 250원 · 24시간 · 02-749-5314", f.details)
        assertEquals(emptyList<Parking.Lot>(), Parking.parseTile("1_1", null))
    }
}
