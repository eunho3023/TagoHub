package com.eunho.tagohub

import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.FavoriteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {
    private val stop = Favorite.of(BusStop("35010", "JUB305001234", "전북대학교|구정문", "52123", 35.8467, 127.1294))
    private val route = Favorite.of("GGB200000085", "98", "일반형시내버스", "31", "이목동차고지.이목동입구", "한화꿈에그린")

    @Test
    fun roundTrip_withSpecialChars() {
        val odd = stop.copy(name = "a\\b|c\nd")
        val back = FavoriteCodec.decode(FavoriteCodec.encode(listOf(odd, route)))
        assertEquals(listOf(odd, route), back)
    }

    @Test
    fun reopen_stopAndRoute() {
        val s = stop.toStop()
        assertEquals("JUB305001234", s.nodeId)
        assertEquals("52123", s.nodeNo)
        assertEquals(35.8467, s.lat!!, 1e-9)
        val r = route.toRoute()
        assertEquals("98", r.routeNo)
        assertEquals("이목동차고지.이목동입구", r.startName)
        assertEquals("한화꿈에그린", r.endName)
        assertEquals("31", r.cityCode)
    }

    @Test
    fun toggle_addFirst_removeSecond() {
        val one = FavoriteCodec.toggle(emptyList(), stop)
        val two = FavoriteCodec.toggle(one, route)
        assertEquals(listOf(route, stop), two)           // 새로 넣은 게 앞
        assertEquals(listOf(route), FavoriteCodec.toggle(two, stop))
        assertTrue(FavoriteCodec.decode("깨진 줄\n\nStop|x").isEmpty()) // 깨진 줄은 건너뜀
    }
}
