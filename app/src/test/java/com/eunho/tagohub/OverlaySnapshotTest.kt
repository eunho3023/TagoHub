package com.eunho.tagohub

import com.eunho.tagohub.data.ItsCctvParser
import com.eunho.tagohub.data.ItsRoadParser
import com.eunho.tagohub.data.OverlaySnapshot
import com.eunho.tagohub.data.UticIncidentParser
import com.eunho.tagohub.data.UticRoadRiskParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class OverlaySnapshotTest {
    @Test
    fun metaParsesSecondsPerFile() {
        val m = OverlaySnapshot.parseMeta("""{"updated":{"ov_its_events.xml":1792540014,"ov_utic_incidents.xml":1792540011},"generated":1792540020}""")
        assertEquals(1792540014L, m["ov_its_events.xml"])
        assertEquals(2, m.size)
        assertTrue(OverlaySnapshot.parseMeta("not json at all").isEmpty())
    }

    @Test
    fun staleOrFutureSnapshotsAreRejected() {
        val now = 1_800_000_000L
        assertTrue(OverlaySnapshot.isFresh(now - 60, now, OverlaySnapshot.MAX_AGE_SEC))
        assertFalse(OverlaySnapshot.isFresh(now - 31 * 60, now, OverlaySnapshot.MAX_AGE_SEC))
        assertFalse(OverlaySnapshot.isFresh(now + 3600, now, OverlaySnapshot.MAX_AGE_SEC))
        assertFalse(OverlaySnapshot.isFresh(0, now, OverlaySnapshot.MAX_AGE_SEC))
    }

    /** pipeline/build_overlays.py 가 만든 실제 저장본이 로컬에 있으면 앱의 해석기로 읽히는지 확인 (없으면 건너뜀) */
    @Test
    fun realSnapshotsParseWithAppParsers() {
        val dir = listOf("pipeline/out_overlays", "../pipeline/out_overlays").map(::File).firstOrNull { it.isDirectory }
        assumeTrue(dir != null)
        fun text(name: String): String? = File(dir, "$name.gz").takeIf { it.exists() }?.let {
            GZIPInputStream(it.inputStream()).bufferedReader(Charsets.UTF_8).readText()
        }
        text("ov_utic_incidents.xml")?.let { assertTrue(UticIncidentParser.parse(it).isNotEmpty()) }
        text("ov_utic_road_risk.json")?.let { assertTrue(UticRoadRiskParser.parse(it).isNotEmpty()) }
        text("ov_its_events.xml")?.let { assertTrue(ItsRoadParser.events(it).isNotEmpty()) }
        text("ov_its_safe_driving.xml")?.let { assertTrue(ItsRoadParser.safeDriving(it).isNotEmpty()) }
        text("ov_its_cctv_ex.xml")?.let { assertTrue(ItsCctvParser.parse(it, "ex").size > 100) }
        text("ov_its_cctv_its.xml")?.let { assertTrue(ItsCctvParser.parse(it, "its").size > 100) }
    }
}

class UticJsonErrorTest {
    @org.junit.Test
    fun jsonErrorBecomesReadableMessage() {
        val err = try {
            com.eunho.tagohub.data.UticIncidentParser.parse("""[{"resultCode":"02","resultMsg":"유효한 KEY값이 아닙니다."}]""")
            null
        } catch (e: java.io.IOException) {
            e.message
        }
        org.junit.Assert.assertEquals("UTIC 돌발정보 오류 (02) 유효한 KEY값이 아닙니다.", err)
    }
}
