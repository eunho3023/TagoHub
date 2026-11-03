package com.eunho.tagohub

import com.eunho.tagohub.data.AccountMerge
import com.eunho.tagohub.data.CloudSyncSpec
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.FavoriteCodec
import com.eunho.tagohub.data.HistoryLogic
import com.eunho.tagohub.data.RouteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountMergeTest {
    private val fav = CloudSyncSpec.field("bus", "favorites")
    private val overlays = CloudSyncSpec.field("bus", "overlays")
    private val home = CloudSyncSpec.field("bus", "saved_home")
    private val searches = CloudSyncSpec.field("search_history", "items")
    private val routes = CloudSyncSpec.field("route_history", "items")
    private val offGroups = CloudSyncSpec.field("drive_guidance", "off_groups")

    private fun stop(id: String, name: String) = Favorite(Favorite.Kind.Stop, id, name, null, "25")

    @Test
    fun favoritesFromBothAccountsWithoutDuplicates() {
        val mine = mapOf(fav to FavoriteCodec.encode(listOf(stop("A", "시청"), stop("B", "역"))))
        val other = mapOf(fav to FavoriteCodec.encode(listOf(stop("B", "역(다른 이름)"), stop("C", "터미널"))))
        val merged = FavoriteCodec.decode(AccountMerge.merge(mine, other)[fav] as String)
        assertEquals(listOf("A", "B", "C"), merged.map { it.id })
        assertEquals("역", merged[1].name)
    }

    @Test
    fun overlaysAndSearchesAreUnited() {
        val mine = mapOf(overlays to "traffic,cctv", searches to HistoryLogic.encodeSearches(listOf("강남역", "시청")))
        val other = mapOf(overlays to "cctv,traffic_lights", searches to HistoryLogic.encodeSearches(listOf("시청", "부산역")))
        val m = AccountMerge.merge(mine, other)
        assertEquals("traffic,cctv,traffic_lights", m[overlays])
        assertEquals(listOf("강남역", "시청", "부산역"), HistoryLogic.decodeSearches(m[searches] as String))
    }

    @Test
    fun routesKeepNewestAndGuidedMark() {
        val a = RouteRecord("집", "회사", 1.0, 2.0, atMs = 100)
        val b = RouteRecord("집", "회사", 1.0, 2.0, guided = true, atMs = 50)
        val c = RouteRecord("역", "공항", 3.0, 4.0, atMs = 200)
        val m = AccountMerge.merge(mapOf(routes to HistoryLogic.encodeRoutes(listOf(a))), mapOf(routes to HistoryLogic.encodeRoutes(listOf(c, b))))
        val out = HistoryLogic.decodeRoutes(m[routes] as String)
        assertEquals(listOf("공항", "회사"), out.map { it.toName })
        assertEquals(100L, out[1].atMs)
        assertEquals(true, out[1].guided)
    }

    @Test
    fun settingsPreferCurrentAccountThenFillFromOther() {
        val m = AccountMerge.merge(
            mapOf(home to "내 집", offGroups to listOf("Camera")),
            mapOf(home to "다른 집", offGroups to listOf("School"), CloudSyncSpec.field("ui", "theme_mode") to "dark", "updatedAt" to 5L),
        )
        assertEquals("내 집", m[home])
        assertEquals(listOf("Camera"), m[offGroups])
        assertEquals("dark", m[CloudSyncSpec.field("ui", "theme_mode")])
        assertFalse(m.containsKey("updatedAt"))
    }

    @Test
    fun blankCurrentValueTakesOther() {
        val m = AccountMerge.merge(mapOf(home to " ", offGroups to emptyList<String>()), mapOf(home to "다른 집", offGroups to listOf("School")))
        assertEquals("다른 집", m[home])
        assertEquals(listOf("School"), m[offGroups])
    }
}
