package com.eunho.tagohub

import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.NavEngine
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.NavState
import com.eunho.tagohub.data.NavText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos

class DriveNavTest {
    /** 실제 카카오모빌리티 응답(강남 → 대전, 153km) */
    private fun realRoute(): NavRoute {
        val f = listOf(
            "src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json",
            "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json",
        ).map { File(it) }.first { it.exists() }
        val (status, route) = KakaoDrive.parse(f.readText(Charsets.UTF_8))
        assertEquals("OK", status)
        return NavRoute.from(route!!)
    }

    private data class Heard(val progress: Double, val text: String, val next: DriveGuide?)

    /** [stepM] 미터마다 위치를 찍으며 경로를 따라 달린다. [offsetM] 은 위치 번호별로 북쪽으로 얼마나 벗어날지(GPS 흔들림·이탈 흉내) */
    private fun drive(
        route: NavRoute,
        speedMps: Double,
        stepM: Double = 25.0,
        offsetM: (Int) -> Double = { 0.0 },
        onState: (Int, NavState) -> Unit = { _, _ -> },
    ): List<Heard> {
        val engine = NavEngine(route)
        val heard = ArrayList<Heard>()
        var i = 0
        var d = 0.0
        while (d <= route.length) {
            val seg = route.segmentAt(d)
            val a = route.points[seg]
            val b = route.points[seg + 1]
            val len = route.cum[seg + 1] - route.cum[seg]
            val t = if (len > 0) (d - route.cum[seg]) / len else 0.0
            val lat = a.lat + (b.lat - a.lat) * t + offsetM(i) / 110_540.0
            val lng = a.lng + (b.lng - a.lng) * t
            val st = engine.update(lat, lng, speedMps)
            st.speak.forEach { heard += Heard(st.progressMeters, it, st.nextGuide) }
            onState(i, st)
            i++
            d += stepM
        }
        // 마지막 지점에서 한 번 더 (도착 처리)
        val end = route.points.last()
        engine.update(end.lat, end.lng, 0.0).also { st -> st.speak.forEach { heard += Heard(st.progressMeters, it, st.nextGuide) } }
        return heard
    }

    @Test
    fun parsesRealResponseWithFareGuidesAndTraffic() {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        val route = KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!
        assertEquals("거리", 153486, route.distanceMeters)
        assertEquals("통행요금 ${route.tollWon}", 8500, route.tollWon)
        assertEquals("안내 수", 55, route.guides.size)
        assertTrue("첫 안내 종류 ${route.guides.first().type}", route.guides.first().isStart)
        assertTrue("마지막 안내 종류 ${route.guides.last().type}", route.guides.last().isDestination)
        // 안내 거리를 모두 더하면 경로 전체 거리와 같음 (엔진이 이 값으로 안내 위치를 계산)
        assertEquals("안내 거리 합", route.distanceMeters, route.guides.sumOf { it.distanceFromPrev })
        assertTrue("도로 수 ${route.roads.size}", route.roads.size > 300)
        assertTrue("원활 구간 없음: ${route.roads.map { it.trafficState }.toSet()}", route.roads.any { it.trafficState == 4 })
        // 고속도로는 점이 드묾(평균 170m 간격)
        assertTrue("점 수 ${route.points.size}", route.points.size > 500)
    }

    @Test
    fun fullDriveAnnouncesEveryTurnInOrderAndArrives() {
        val route = realRoute()
        var last = -1.0
        var offEver = false
        var arrived = false
        var firstRemaining = -1
        var prevRemaining = Long.MAX_VALUE / 2
        val heard = drive(route, speedMps = 27.8) { _, st ->
            assertTrue("진행 거리가 줄어듦: ${st.progressMeters} < $last", st.progressMeters >= last)
            last = st.progressMeters
            if (st.offRoute) offEver = true
            if (st.arrived) arrived = true
            if (firstRemaining < 0) firstRemaining = st.remainingSeconds
            assertTrue("남은 시간이 늘어남: ${st.remainingSeconds} > $prevRemaining @${st.progressMeters.toInt()}m", st.remainingSeconds.toLong() <= prevRemaining + 1)
            prevRemaining = st.remainingSeconds.toLong()
        }
        assertFalse("정상 주행에서 이탈로 판정됨", offEver)
        assertTrue("도착 처리 안 됨", arrived)
        assertEquals(1, heard.count { "목적지 부근" in it.text })
        // 처음 남은 시간이 서비스가 준 전체 시간과 비슷해야 함
        assertTrue("처음 남은 시간 $firstRemaining vs ${route.totalSeconds}", abs(firstRemaining - route.totalSeconds) < route.totalSeconds * 0.03)

        // 말한 문구는 모두 '다음 안내'에 대한 것이어야 하고, 한 안내에 3번(2km·1km·500m)을 넘게 말하지 않음
        val turnSpeaks = heard.filter { "앞" in it.text }
        assertTrue("안내가 너무 적음: ${turnSpeaks.size}", turnSpeaks.size > 40)
        turnSpeaks.forEach { h -> assertTrue("엉뚱한 안내: ${h.text}", h.next != null && h.text.contains(h.next.guidance)) }
        val perGuide = turnSpeaks.groupBy { it.next!!.name + "@" + it.next.lat }
        assertTrue(perGuide.values.all { it.size <= 3 })
        // 고속도로 속도에서는 2km 앞 예고가 나옴
        assertTrue(turnSpeaks.any { it.text.startsWith("2킬로미터 앞") })
        // 안내 순서: 처음 말한 위치가 점점 커짐
        val firstSpokenAt = perGuide.values.map { it.minOf { h -> h.progress } }
        assertEquals(firstSpokenAt.sorted(), firstSpokenAt)
    }

    @Test
    fun gpsNoiseWithinTwentyFiveMetersIsNotAnOffRoute() {
        val route = realRoute()
        var off = false
        var firstOff = -1
        drive(route, speedMps = 20.0, stepM = 20.0, offsetM = { if (it % 2 == 0) 25.0 else -25.0 }) { i, st -> if (st.offRoute) { off = true; if (firstOff < 0) firstOff = i } }
        assertFalse("흔들림이 이탈로 판정됨 (첫 위치 번호 $firstOff)", off)
    }

    @Test
    fun leavingTheRouteIsDetectedOnceAndRecovers() {
        val route = realRoute()
        val total = (route.length / 25.0).toInt()
        val start = (total * 0.4).toInt()
        var offAt = -1
        var recoveredAt = -1
        val heard = drive(route, speedMps = 25.0, offsetM = { i -> if (i in start until start + 6) 300.0 else 0.0 }) { i, st ->
            if (st.offRoute && offAt < 0) offAt = i
            if (!st.offRoute && offAt >= 0 && recoveredAt < 0) recoveredAt = i
        }
        assertTrue("이탈을 못 찾음", offAt in start..start + 5)
        assertTrue("이탈 3번째 갱신에서 판정", offAt == start + 2)
        assertTrue("경로로 돌아왔는데 이탈이 안 풀림", recoveredAt == start + 6)
        assertEquals(1, heard.count { "벗어났" in it.text })
    }

    @Test
    fun outAndBackRouteDoesNotJumpToTheOtherLeg() {
        // 동쪽으로 5km 갔다가 40m 북쪽에서 같은 길로 되돌아오는 경로 (돌아오는 길이 출발점 가까이에서 끝남)
        val lat0 = 37.0
        val lng0 = 127.0
        val mLng = 1.0 / (111_320.0 * cos(Math.toRadians(lat0)))
        val mLat = 1.0 / 110_540.0
        val pts = ArrayList<LatLng>()
        for (x in 0..5000 step 100) pts += LatLng(lat0, lng0 + x * mLng)
        for (x in 5000 downTo 0 step 100) pts += LatLng(lat0 + 40 * mLat, lng0 + x * mLng)
        val guides = listOf(
            DriveGuide("출발", "출발지", 0, DriveGuide.TYPE_START, lat0, lng0),
            DriveGuide("반환점", "유턴하세요", 5000, 5, lat0, lng0 + 5000 * mLng),
            DriveGuide("목적지", "목적지", 5040, DriveGuide.TYPE_DESTINATION, lat0 + 40 * mLat, lng0),
        )
        val route = NavRoute(pts, guides, emptyList(), 10040, 600)
        val engine = NavEngine(route)
        // 출발점에서 시작: 40m 떨어진 도착 쪽이 아니라 처음 쪽으로 잡아야 함
        assertTrue(engine.update(lat0, lng0, 0.0).progressMeters < 100)
        var minOnReturn = Double.MAX_VALUE
        drive(route, speedMps = 15.0, stepM = 20.0) { _, st -> if (st.progressMeters > 5100) minOnReturn = minOf(minOnReturn, st.progressMeters) }
        assertTrue("돌아오는 길에서 진행 거리가 5100m 보다 작아지지 않아야 함", minOnReturn >= 5100)
    }

    @Test
    fun spokenDistancesAreRoundAndReadable() {
        assertEquals("300미터", NavText.distance(299.0))
        assertEquals("100미터", NavText.distance(120.0))
        assertEquals("50미터", NavText.distance(10.0))
        assertEquals("1킬로미터", NavText.distance(1000.0))
        assertEquals("1.5킬로미터", NavText.distance(1480.0))
        assertEquals("2킬로미터", NavText.distance(2200.0))
        assertEquals("42.0km", NavText.short(42_000.0))
        assertEquals("850m", NavText.short(850.0))
    }
}
