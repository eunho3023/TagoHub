package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.RouteCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteCompareTest {
    private fun route(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    @Test
    fun theSameRouteHasNoDivergence() {
        val r = route()
        assertEquals(0.0, RouteCompare.divergence(r, r.points), 1e-9)
        assertEquals(0.0, RouteCompare.divergence(r, emptyList()), 1e-9)
    }

    @Test
    fun aParallelRouteFarAwayDiverges() {
        val r = route()
        // 같은 모양을 동쪽으로 2km 옮김 → 다른 길
        val shifted = r.points.map { p -> val (la, ln) = Geo.offset(p.lat, p.lng, 90.0, 2000.0); LatLng(la, ln) }
        assertEquals(1.0, RouteCompare.divergence(r, shifted), 0.01)
        // 같은 길에서 20m 옆 (차선 차이)은 다른 길이 아님
        val near = r.points.map { p -> val (la, ln) = Geo.offset(p.lat, p.lng, 90.0, 20.0); LatLng(la, ln) }
        assertEquals(0.0, RouteCompare.divergence(r, near), 0.01)
    }

    @Test
    fun aDetourOnlyForAThirdOfTheRouteIsStillDifferent() {
        val r = route()
        val third = r.points.size / 3
        val detour = r.points.mapIndexed { i, p ->
            if (i in third until 2 * third) { val (la, ln) = Geo.offset(p.lat, p.lng, 90.0, 1500.0); LatLng(la, ln) } else p
        }
        val d = RouteCompare.divergence(r, detour)
        assertTrue("divergence $d", d in 0.2..0.5)
    }

    @Test
    fun suggestOnlyWhenDifferentAndFasterEnough() {
        // 남은 1시간(3600초): 5분(300)과 8%(288) 중 큰 300초 이상 줄어야 함
        assertEquals(600, RouteCompare.savedSeconds(3600, 3000, 0.5))
        assertEquals(300, RouteCompare.savedSeconds(3600, 3300, 0.5))
        assertNull(RouteCompare.savedSeconds(3600, 3400, 0.5))   // 200초만 줄어 → 알리지 않음
        assertNull(RouteCompare.savedSeconds(3600, 3000, 0.05))  // 같은 길이면 알리지 않음
        assertNull(RouteCompare.savedSeconds(3600, 4000, 0.9))   // 더 느리면 알리지 않음
        // 남은 3시간(10800초): 8% = 864초 이상
        assertNull(RouteCompare.savedSeconds(10_800, 10_000, 0.5)) // 800초
        assertEquals(900, RouteCompare.savedSeconds(10_800, 9_900, 0.5))
    }
}
