package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.RoadHazard
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.RoadAlertKind
import com.eunho.tagohub.data.RoadAlertTracker
import com.eunho.tagohub.data.RoadAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 경로 안내의 결빙구간·톨게이트·전광판 알림 */
class RoadAlertsTest {
    private fun route(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    private fun hazard(r: NavRoute, kind: RoadAlertKind, name: String, along: Double, sideM: Double = 0.0): RoadHazard {
        val p = r.pointAt(along)
        val q = r.pointAt(along + 20.0)
        val heading = Geo.bearing(p.lat, p.lng, q.lat, q.lng)
        val (la, ln) = Geo.offset(p.lat, p.lng, if (sideM >= 0) heading + 90.0 else heading - 90.0, kotlin.math.abs(sideM))
        return RoadHazard(kind, name, null, la, ln, "$kind:$name:$along")
    }

    @Test
    fun hazardsOnTheRouteAreFoundInOrder() {
        val r = route()
        val alerts = RoadAlerts.match(
            r,
            listOf(
                hazard(r, RoadAlertKind.Ice, "상습 결빙 구간", 60_000.0, 20.0),
                hazard(r, RoadAlertKind.Ice, "상습 결빙 구간", 20_000.0, 10.0),
                hazard(r, RoadAlertKind.Ice, "상습 결빙 구간", 40_000.0, 800.0),   // 길에서 멀어 다른 길
                hazard(r, RoadAlertKind.Risk, "도로 위험 예보 구간", 30_000.0, 30.0),
                hazard(r, RoadAlertKind.Safety, "교통안전 지점", 90_000.0, 20.0),
            ),
        )
        assertEquals(listOf(RoadAlertKind.Ice, RoadAlertKind.Risk, RoadAlertKind.Ice, RoadAlertKind.Safety), alerts.map { it.kind })
        assertEquals(20_000.0, alerts[0].along, 150.0)
    }

    @Test
    fun tollGatesOnTheOppositeSideAreIgnored() {
        val r = route()
        val alerts = RoadAlerts.match(
            r,
            listOf(hazard(r, RoadAlertKind.Toll, "서울 톨게이트", 5_000.0, 5.0), hazard(r, RoadAlertKind.Toll, "반대편 톨게이트", 50_000.0, -60.0)),
        )
        assertEquals(listOf("서울 톨게이트"), alerts.map { it.name })
    }

    @Test
    fun sameKindCloseTogetherCollapses() {
        val r = route()
        val alerts = RoadAlerts.match(
            r,
            listOf(hazard(r, RoadAlertKind.Disaster, "재난 정보", 30_000.0, 10.0), hazard(r, RoadAlertKind.Disaster, "재난 정보", 30_100.0, 15.0)),
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun speaksOncePerAlertAndOnlyWhenClose() {
        val r = route()
        val alerts = RoadAlerts.match(r, listOf(hazard(r, RoadAlertKind.Toll, "대전 톨게이트", 100_000.0, 5.0)))
        val t = RoadAlertTracker(alerts)
        assertNull(t.update(50_000.0))                       // 아직 멀다 (2km 밖)
        val near = t.update(98_500.0)
        assertNotNull(near)
        assertNotNull(near!!.speak)                          // 처음 가까워질 때 한 번 말함
        assertNull(t.update(99_000.0)!!.speak)               // 다시 말하지 않음
        assertNull(t.update(100_500.0))                      // 지나간 뒤에는 없음
    }
}
