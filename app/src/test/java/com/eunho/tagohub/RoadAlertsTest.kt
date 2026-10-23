package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.ItsRoadFeature
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

    private fun feature(r: NavRoute, layer: String, title: String, along: Double, sideM: Double = 0.0, details: String = ""): ItsRoadFeature {
        val p = r.pointAt(along)
        val q = r.pointAt(along + 20.0)
        val heading = Geo.bearing(p.lat, p.lng, q.lat, q.lng)
        val (la, ln) = Geo.offset(p.lat, p.lng, if (sideM >= 0) heading + 90.0 else heading - 90.0, kotlin.math.abs(sideM))
        return ItsRoadFeature("$layer:$title:$along", layer, title, details, la, ln)
    }

    @Test
    fun iceSectionsOnTheRouteAreFoundInOrder() {
        val r = route()
        val alerts = RoadAlerts.match(
            r, RoadAlertKind.Ice,
            listOf(feature(r, "its_ice_sections", "B", 60_000.0, 20.0), feature(r, "its_ice_sections", "A", 20_000.0, 10.0), feature(r, "its_ice_sections", "먼곳", 40_000.0, 800.0)),
        )
        assertEquals(2, alerts.size)
        assertEquals(20_000.0, alerts[0].along, 150.0)
        assertEquals("상습 결빙 구간", alerts[0].name)
    }

    @Test
    fun tollGatesOnTheOppositeSideAreIgnored() {
        val r = route()
        val alerts = RoadAlerts.match(
            r, RoadAlertKind.Toll,
            listOf(feature(r, "ex_toll", "서울", 5_000.0, 5.0), feature(r, "ex_toll", "반대편", 50_000.0, -60.0)),
        )
        assertEquals(listOf("서울 톨게이트"), alerts.map { it.name })
    }

    @Test
    fun speaksOncePerAlertAndOnlyWhenClose() {
        val r = route()
        val alerts = RoadAlerts.match(r, RoadAlertKind.Toll, listOf(feature(r, "ex_toll", "대전톨게이트", 100_000.0, 5.0)))
        val t = RoadAlertTracker(alerts)
        assertNull(t.update(50_000.0))                       // 아직 멀다 (2km 밖)
        val near = t.update(98_500.0)
        assertNotNull(near)
        assertNotNull(near!!.speak)                          // 처음 가까워질 때 한 번 말함
        assertNull(t.update(99_000.0)!!.speak)               // 다시 말하지 않음
        assertNull(t.update(100_500.0))                      // 지나간 뒤에는 없음
    }
}
