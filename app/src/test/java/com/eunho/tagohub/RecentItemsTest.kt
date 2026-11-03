package com.eunho.tagohub

import com.eunho.tagohub.data.AccountMerge
import com.eunho.tagohub.data.CloudSyncSpec
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.Place2
import com.eunho.tagohub.data.RecentItem
import com.eunho.tagohub.data.RecentLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentItemsTest {
    private val stop = Favorite(Favorite.Kind.Stop, "JUB312101702", "우석대학교", "31701", "35010", 35.91512, 127.067102, "31701")
    private val route = Favorite(Favorite.Kind.Route, "JUB305001145", "309", "우석대학교 ↔ 평화동종점", "35010", extra = "일반")

    @Test
    fun sameThingMovesToFrontOnce() {
        var l = RecentLogic.add(emptyList(), RecentItem.of(stop, 1))
        l = RecentLogic.add(l, RecentItem.query("화장실", 2))
        l = RecentLogic.add(l, RecentItem.of(stop, 3))
        assertEquals(listOf(RecentItem.Kind.Stop, RecentItem.Kind.Query), l.map { it.kind })
        assertEquals(3L, l.first().atMs)
        // 검색어는 대소문자·공백 차이를 같은 것으로
        l = RecentLogic.add(l, RecentItem.query("  화장실 ", 4))
        assertEquals(2, l.size)
    }

    @Test
    fun shortQueriesAndLimit() {
        assertTrue(RecentLogic.add(emptyList(), RecentItem.query("a", 1)).isEmpty())
        val many = (1..80).fold(emptyList<RecentItem>()) { acc, i -> RecentLogic.add(acc, RecentItem.query("검색$i", i.toLong())) }
        assertEquals(RecentLogic.MAX, many.size)
        assertEquals("검색80", many.first().title)
    }

    @Test
    fun encodeRoundTripKeepsEverything() {
        val list = listOf(
            RecentItem.of(stop, 10), RecentItem.of(route, 20),
            RecentItem.place(Place2("우석대학교\t인문관", "전북 완주군\n삼례읍", 35.9, 127.06, category = "대학교"), 30),
            RecentItem.query("삼례\\터미널", 0),
        )
        val back = RecentLogic.decode(RecentLogic.encode(list))
        assertEquals(list, back)
        assertEquals(stop.copy(), back[0].toFavorite())
        assertEquals("우석대학교\t인문관", back[2].toPlace()!!.name)
        assertNull(back[3].toFavorite())
        assertTrue(RecentLogic.decode("깨진 줄\n").isEmpty())
    }

    @Test
    fun accountMergeKeepsBothNewestFirst() {
        val f = CloudSyncSpec.field("recent_items", "items")
        val mine = RecentLogic.encode(listOf(RecentItem.query("시청", 5), RecentItem.of(stop, 1)))
        val other = RecentLogic.encode(listOf(RecentItem.of(stop, 9), RecentItem.of(route, 3)))
        val merged = RecentLogic.decode(AccountMerge.merge(mapOf(f to mine), mapOf(f to other))[f] as String)
        assertEquals(listOf(RecentItem.Kind.Stop, RecentItem.Kind.Query, RecentItem.Kind.Route), merged.map { it.kind })
        assertEquals(9L, merged.first().atMs)
    }
}
