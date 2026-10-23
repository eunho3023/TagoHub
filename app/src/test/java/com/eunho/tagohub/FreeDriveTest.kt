package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.FreeDrive
import com.eunho.tagohub.data.FreeDriveAnnouncer
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.ZoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 목적지 없이 달릴 때 앞쪽 단속카메라·보호구역 찾기 */
class FreeDriveTest {
    private val lat = 37.5
    private val lng = 127.0

    private fun zoneAt(bearing: Double, meters: Double, side: Double = 0.0, kind: ZoneKind = ZoneKind.SpeedCamera, limit: Int? = 80): SafetyZone {
        val (la, ln) = Geo.offset(lat, lng, bearing, meters)
        val (la2, ln2) = if (side != 0.0) Geo.offset(la, ln, bearing + 90, side) else la to ln
        return SafetyZone(kind, "z", la2, ln2, limit)
    }

    @Test
    fun onlyThingsInFrontOfTheCarAreReported() {
        val zones = listOf(
            zoneAt(0.0, 500.0),       // 앞 500m
            zoneAt(180.0, 300.0),     // 뒤
            zoneAt(90.0, 300.0),      // 옆길
            zoneAt(0.0, 3000.0),      // 너무 멂
        )
        val found = FreeDrive.zonesAhead(lat, lng, 0.0, zones)
        assertEquals(1, found.size)
        assertEquals(500.0, found[0].distance, 5.0)
    }

    @Test
    fun nearestComesFirstAndSideOffsetIsLimited() {
        val zones = listOf(zoneAt(0.0, 900.0), zoneAt(0.0, 200.0, side = 10.0), zoneAt(0.0, 600.0, side = 200.0))
        val found = FreeDrive.zonesAhead(lat, lng, 0.0, zones)
        assertEquals(2, found.size)
        assertEquals(200.0, found[0].distance, 15.0)
        assertEquals(900.0, found[1].distance, 30.0)
    }

    @Test
    fun headingIsRespected() {
        val zones = listOf(zoneAt(90.0, 400.0))
        assertEquals(1, FreeDrive.zonesAhead(lat, lng, 85.0, zones).size)     // 동쪽으로 달리면 앞
        assertTrue(FreeDrive.zonesAhead(lat, lng, 270.0, zones).isEmpty())    // 서쪽으로 달리면 뒤
    }

    @Test
    fun compassWords() {
        assertEquals("북", FreeDrive.compassWord(0.0))
        assertEquals("북", FreeDrive.compassWord(355.0))
        assertEquals("북동", FreeDrive.compassWord(45.0))
        assertEquals("동", FreeDrive.compassWord(90.0))
        assertEquals("남서", FreeDrive.compassWord(225.0))
        assertEquals("북서", FreeDrive.compassWord(315.0))
    }

    @Test
    fun announcerSpeaksEachZoneOnceWhenClose() {
        val cam = FreeDrive.zonesAhead(lat, lng, 0.0, listOf(zoneAt(0.0, 600.0))).first()
        val far = FreeDrive.zonesAhead(lat, lng, 0.0, listOf(zoneAt(0.0, 1200.0))).first()
        val a = FreeDriveAnnouncer()
        assertNull(a.speakFor(far))                    // 700m 밖이면 아직
        val line = a.speakFor(cam)
        assertNotNull(line)
        assertTrue(line!!.contains("과속단속카메라") && line.contains("80"))
        assertNull(a.speakFor(cam))                    // 두 번 말하지 않음
        a.forget()
        assertNotNull(a.speakFor(cam))
    }
}
