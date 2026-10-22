package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.ItsRoadFeature
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.RestAhead
import com.eunho.tagohub.data.RestTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestAheadTest {
    private fun route(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    /** 경로 [along] 지점에서 진행 방향 오른쪽(sideM > 0) 또는 왼쪽(< 0)으로 |sideM| 미터 떨어진 휴게소 */
    private fun rest(r: NavRoute, title: String, along: Double, sideM: Double = 0.0): ItsRoadFeature {
        val p = r.pointAt(along)
        val q = r.pointAt(along + 20.0)
        val heading = Geo.bearing(p.lat, p.lng, q.lat, q.lng)
        val (la, ln) = Geo.offset(p.lat, p.lng, if (sideM >= 0) heading + 90.0 else heading - 90.0, kotlin.math.abs(sideM))
        return ItsRoadFeature("ex_rest:$title", "ex_rest", title, "", la, ln)
    }

    @Test
    fun rootDropsDirectionAndSuffix() {
        assertEquals("서울만남", RestAhead.root("서울만남(부산)휴게소"))
        assertEquals("안성", RestAhead.root("안성(서울)"))
        assertEquals("천안삼거리", RestAhead.root("천안삼거리휴게소"))
    }

    @Test
    fun onlyRestAreasNearTheRouteAreKeptInOrder() {
        val r = route()
        val stops = RestAhead.match(
            r,
            listOf(
                rest(r, "B휴게소", 80_000.0, sideM = 120.0),
                rest(r, "A휴게소", 30_000.0, sideM = 60.0),
                rest(r, "먼휴게소", 50_000.0, sideM = 900.0), // 길에서 멀어 다른 길의 휴게소
            ),
        )
        assertEquals(listOf("A휴게소", "B휴게소"), stops.map { it.name })
        assertEquals(30_000.0, stops[0].along, 150.0)
    }

    @Test
    fun bothDirectionsOfTheSameRestAreaCollapseToTheCloserOne() {
        val r = route()
        val stops = RestAhead.match(
            r,
            listOf(
                rest(r, "만남(부산)휴게소", 40_000.0, sideM = 200.0), // 우리 쪽이지만 조금 먼 출입로
                rest(r, "만남(서울)휴게소", 40_100.0, sideM = 70.0),  // 같은 휴게소의 더 가까운 점
            ),
        )
        assertEquals(1, stops.size)
        assertEquals("만남(서울)휴게소", stops[0].name)
    }

    @Test
    fun restAreasOnTheOppositeCarriagewayAreIgnored() {
        val r = route()
        val stops = RestAhead.match(
            r,
            listOf(
                rest(r, "천안삼거리(서울)휴게소", 60_000.0, sideM = -70.0), // 반대편 차로 쪽(왼쪽): 서울 방향
                rest(r, "천안삼거리(부산)휴게소", 60_300.0, sideM = 90.0),  // 우리 쪽(오른쪽)
            ),
        )
        assertEquals(listOf("천안삼거리(부산)휴게소"), stops.map { it.name })
    }

    @Test
    fun trackerShowsTheNextOneAndAnnouncesOnceWithin5km() {
        val r = route()
        val stops = RestAhead.match(r, listOf(rest(r, "만남(서울)휴게소", 40_000.0, 30.0), rest(r, "다음(서울)휴게소", 90_000.0, 30.0)))
        val t = RestTracker(stops)
        val at = stops[0].along
        val far = t.update(at - 20_000, r.length - (at - 20_000))!!
        assertEquals("만남휴게소", far.name)
        assertNull(far.speak)
        assertEquals(20_000.0, far.distance, 1.0)
        val near = t.update(at - 4_900, r.length - (at - 4_900))!!
        assertNotNull(near.speak)
        assertTrue(near.speak!!, "만남휴게소입니다" in near.speak!!)
        assertNull(t.update(at - 3_000, r.length - (at - 3_000))!!.speak) // 한 번만
        // 지나면 다음 휴게소
        val after = t.update(at + 500, r.length - (at + 500))!!
        assertEquals("다음휴게소", after.name)
        assertTrue(after.distance > 40_000)
        // 마지막 휴게소를 지나면 없음
        assertNull(t.update(stops[1].along + 500, 1000.0))
    }

    @Test
    fun noAnnouncementWhenTheDestinationIsRightAfterTheRestArea() {
        val r = route()
        val stops = RestAhead.match(r, listOf(rest(r, "끝(서울)휴게소", 40_000.0, 30.0)))
        val t = RestTracker(stops)
        val at = stops[0].along
        // 목적지까지 남은 거리가 휴게소까지 + 1km 밖에 안 됨
        val u = t.update(at - 3_000, 3_000.0 + 1_000.0)!!
        assertNull(u.speak)
        assertEquals(3_000.0, u.distance, 1.0)
    }
}
