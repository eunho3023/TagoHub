package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.SafetyZoneSnapshot
import com.eunho.tagohub.data.ZoneGroup
import com.eunho.tagohub.data.ZoneKind
import com.eunho.tagohub.data.ZoneTracker
import com.eunho.tagohub.data.matchRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafetyZonesTest {
    private fun route(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    /** 경로 [along] 미터 지점에서 옆으로 [sideM] 미터 벗어난 곳에 둔 안전 지점 */
    private fun zoneAt(r: NavRoute, along: Double, kind: ZoneKind, limit: Int? = null, sideM: Double = 0.0): SafetyZone {
        val p = r.pointAt(along)
        val (lat, lng) = Geo.offset(p.lat, p.lng, 90.0, sideM)
        return SafetyZone(kind, "시험 $kind", lat, lng, limit)
    }

    @Test
    fun snapshotRowsBecomeZonesAndBadOnesAreDropped() {
        val json = """[["speed","서초대로",37.5,127.0,80],["child","역삼초",37.51,127.03,null],["cam","모름",37.52,127.0,"abc"],
            ["xxx","이상한 종류",37.5,127.0,null],["child","범위 밖",10.0,127.0,null],["speed","짧은 행"]]"""
        val z = SafetyZoneSnapshot.parse(json)
        assertEquals(3, z.size)
        assertEquals(ZoneKind.SpeedCamera, z[0].kind)
        assertEquals(80, z[0].limitKmh)
        assertEquals(ZoneKind.ChildZone, z[1].kind)
        assertNull(z[1].limitKmh)
        assertNull(z[2].limitKmh) // 숫자가 아니면 제한속도 없음
    }

    @Test
    fun onlyZonesNearTheRouteAreKeptInOrder() {
        val r = route()
        val zones = listOf(
            zoneAt(r, 60_000.0, ZoneKind.SpeedCamera, 100),
            zoneAt(r, 20_000.0, ZoneKind.ChildZone, sideM = 12.0),
            zoneAt(r, 40_000.0, ZoneKind.SpeedCamera, 100, sideM = 400.0), // 옆길 → 제외
            SafetyZone(ZoneKind.ChildZone, "서울 밖", 35.0, 129.0, null),    // 경로와 멀리 → 제외
        )
        val on = matchRoute(r, zones)
        assertEquals(2, on.size)
        assertEquals(ZoneKind.ChildZone, on[0].zone.kind)
        assertEquals(20_000.0, on[0].along, 120.0)
        assertEquals(60_000.0, on[1].along, 120.0)
    }

    @Test
    fun oppositeLaneDuplicatesMergeButDifferentKindsStay() {
        val r = route()
        val a = zoneAt(r, 30_000.0, ZoneKind.SpeedCamera, null, sideM = 6.0)
        val b = zoneAt(r, 30_040.0, ZoneKind.SpeedCamera, 90, sideM = -6.0)   // 반대 차선 카메라 → 하나로 (제한속도 있는 쪽)
        val c = zoneAt(r, 30_050.0, ZoneKind.ChildZone)                       // 종류가 달라 따로
        val on = matchRoute(r, listOf(a, b, c))
        assertEquals(2, on.size)
        assertTrue(on.any { it.zone.kind == ZoneKind.SpeedCamera && it.zone.limitKmh == 90 })
        assertTrue(on.any { it.zone.kind == ZoneKind.ChildZone })
    }

    @Test
    fun trackerAnnouncesEachLeadOnceAndNotAfterPassing() {
        val r = route()
        val on = matchRoute(r, listOf(zoneAt(r, 10_000.0, ZoneKind.SpeedCamera, 80)))
        val t = ZoneTracker(on)
        val at = on[0].along
        // 고속(80km/h, 제한속도 안): 1000m, 400m 앞에서 한 번씩
        assertTrue(t.update(at - 2000, 80.0).speak.isEmpty())
        val s1 = t.update(at - 990, 80.0).speak
        assertEquals(1, s1.size)
        assertTrue(s1[0], "과속단속카메라" in s1[0] && "제한속도 80킬로미터" in s1[0])
        assertTrue(t.update(at - 900, 80.0).speak.isEmpty())  // 같은 안내는 다시 안 함
        assertEquals(1, t.update(at - 390, 80.0).speak.size)
        // 지나친 뒤: 다음 지점이 없음
        val past = t.update(at + 100, 80.0)
        assertNull(past.next)
        assertTrue(past.speak.isEmpty())
    }

    @Test
    fun childZoneAnnouncesOnceAndSlowCarsGetShorterLeads() {
        val r = route()
        val child = matchRoute(r, listOf(zoneAt(r, 5_000.0, ZoneKind.ChildZone)))
        val t = ZoneTracker(child)
        val at = child[0].along
        assertTrue(t.update(at - 600, 40.0).speak.isEmpty())
        val s = t.update(at - 390, 40.0).speak
        assertEquals(1, s.size)
        assertTrue(s[0], "어린이 보호구역" in s[0])
        assertTrue(t.update(at - 100, 40.0).speak.isEmpty())

        val cam = ZoneTracker(matchRoute(r, listOf(zoneAt(r, 8_000.0, ZoneKind.SignalCamera))))
        val c = cam.update(8_000.0 - 480, 30.0).speak
        assertEquals(1, c.size) // 저속은 500m 앞에서 첫 안내
        assertTrue(c[0], "신호위반" in c[0])
    }

    @Test
    fun overspeedWarnsOncePerCameraAndOnlyWhenAboveTheLimit() {
        val r = route()
        val on = matchRoute(r, listOf(zoneAt(r, 12_000.0, ZoneKind.SpeedCamera, 80)))
        val t = ZoneTracker(on)
        val at = on[0].along
        t.update(at - 1200, 80.0)
        val ok = t.update(at - 500, 82.0)           // 제한 +3 이내: 경고 없음
        assertFalse(ok.overspeed)
        val over = t.update(at - 450, 95.0)
        assertTrue(over.overspeed)
        assertTrue(over.speak.any { "과속" in it })
        val again = t.update(at - 300, 96.0)
        assertTrue(again.overspeed)                 // 화면 표시는 계속
        assertTrue(again.speak.none { "과속입니다" in it }) // 말은 한 번만
    }

    @Test
    fun remainingRoadsDropWhatYouAlreadyDrove() {
        val r = route()
        val all = r.remainingRoads(0.0)
        assertEquals(r.roads.size, all.size)
        val progress = r.length / 2
        val rest = r.remainingRoads(progress)
        assertTrue(rest.size < all.size)
        // 첫 점은 현재 위치, 끝은 목적지 그대로
        val here = r.pointAt(progress)
        assertEquals(here.lat, rest.first().points.first().lat, 1e-9)
        assertEquals(r.points.last().lat, rest.last().points.last().lat, 1e-9)
        // 남은 선의 길이 ≈ 남은 거리
        val len = rest.sumOf { road -> road.points.zipWithNext().sumOf { (a, b) -> Geo.meters(a.lat, a.lng, b.lat, b.lng) } }
        assertEquals(r.length - progress, len, r.length * 0.03)
        assertTrue(r.remainingRoads(r.length + 10).isEmpty())
        val tail = r.remainingPoints(progress)
        assertEquals(here.lat, tail.first().lat, 1e-9)
        assertEquals(r.points.last(), tail.last())
    }

    @Test
    fun bearingAndOffsetAgree() {
        assertEquals(0.0, Geo.bearing(37.0, 127.0, 38.0, 127.0), 0.01)
        assertEquals(90.0, Geo.bearing(37.0, 127.0, 37.0, 128.0), 0.5)
        assertEquals(180.0, Geo.bearing(38.0, 127.0, 37.0, 127.0), 0.01)
        assertEquals(270.0, Geo.bearing(37.0, 128.0, 37.0, 127.0), 0.5)
        val (la, ln) = Geo.offset(37.5, 127.0, 45.0, 1000.0)
        assertEquals(1000.0, Geo.meters(37.5, 127.0, la, ln), 5.0)
        assertEquals(45.0, Geo.bearing(37.5, 127.0, la, ln), 0.5)
        assertNotNull(Geo.offset(37.5, 127.0, 0.0, 0.0))
    }

    @Test
    fun csvLinesParseAndNamesMayContainCommas() {
        val z = SafetyZoneSnapshot.parseLines(
            sequenceOf("bump,37.50000,127.00000,,가파로67번길", "child,37.51000,127.01000,,역삼초등학교, 병설유치원", "xxx,37.5,127.0,,이상한 종류", "child,10.0,127.0,,범위 밖", "bump,abc,127.0,,숫자 아님", "child,37.5"),
        )
        assertEquals(2, z.size)
        assertEquals(ZoneKind.SpeedBump, z[0].kind)
        assertEquals(ZoneGroup.Bump, z[0].kind.group)
        assertEquals("역삼초등학교, 병설유치원", z[1].name)
        assertEquals(ZoneGroup.Protect, z[1].kind.group)
        assertFalse(z[0].kind.isCamera)
    }

    @Test
    fun speedBumpsAnnounceOnceAndDoNotMergeWithChildZones() {
        val r = route()
        val on = matchRoute(r, listOf(zoneAt(r, 7_000.0, ZoneKind.SpeedBump), zoneAt(r, 7_030.0, ZoneKind.ChildZone)))
        assertEquals(2, on.size) // 종류 묶음이 달라 합치지 않음
        val bump = on.first { it.zone.kind == ZoneKind.SpeedBump }
        val t = ZoneTracker(listOf(bump))
        assertTrue(t.update(bump.along - 300, 40.0).speak.isEmpty())
        val s = t.update(bump.along - 190, 40.0).speak
        assertEquals(1, s.size)
        assertTrue(s[0], "과속방지턱" in s[0])
        assertTrue(t.update(bump.along - 100, 40.0).speak.isEmpty())
    }

    /** 앱에 넣은 실제 파일(assets/safety_zones.csv)을 읽어 실제 경로에 맞춰 봄 */
    @Test
    fun bundledAssetLoadsAndMatchesTheRealRoute() {
        val f = listOf("src/main/assets/safety_zones.csv", "app/src/main/assets/safety_zones.csv").map { File(it) }.first { it.exists() }
        val zones = f.bufferedReader(Charsets.UTF_8).use { SafetyZoneSnapshot.parseLines(it.lineSequence()) }
        assertTrue("점 수 ${zones.size}", zones.size > 90_000)
        assertTrue(zones.count { it.kind == ZoneKind.SpeedBump } > 50_000)
        assertTrue(zones.count { it.kind == ZoneKind.ChildZone } > 10_000)
        val r = route()
        val t0 = System.nanoTime()
        val on = matchRoute(r, zones)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("강남→대전 경로 위 안전 지점: ${on.size}곳 (${on.groupingBy { it.zone.kind }.eachCount()}), $ms ms")
        assertTrue("너무 오래 걸림: ${ms}ms", ms < 8_000)
        assertTrue(on.zipWithNext().all { (a, b) -> a.along <= b.along })
    }
}
