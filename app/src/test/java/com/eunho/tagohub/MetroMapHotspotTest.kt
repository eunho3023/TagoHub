package com.eunho.tagohub

import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.ui.home.MetroMapAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 노선도 그림에서 역 이름 자리를 읽고, 누른 자리에서 가장 가까운 역을 찾기 */
class MetroMapHotspotTest {
    private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map { File(it) }.first { it.exists() }

    @Test
    fun parsesLinesAndSkipsBadOnes() {
        val spots = MetroMapAsset.parseHotspots("강남\t0.51\t0.62\n잘못된줄\n교대\t0.5\tabc\n서울\t0.4\t0.3\n")
        assertEquals(listOf("강남", "서울"), spots.map { it.name })
        assertEquals(0.51f, spots[0].x, 1e-6f)
    }

    @Test
    fun nearestWithinRadiusOnly() {
        val spots = MetroMapAsset.parseHotspots("가\t0.10\t0.10\n나\t0.20\t0.10\n")
        assertEquals("가", MetroMapAsset.nearest(spots, 0.11f, 0.10f, 0.03f)!!.name)
        assertEquals("나", MetroMapAsset.nearest(spots, 0.19f, 0.11f, 0.03f)!!.name)
        assertNull(MetroMapAsset.nearest(spots, 0.15f, 0.5f, 0.03f))     // 어느 역과도 멀면 없음
    }

    @Test
    fun bundledHotspotsCoverMostCapitalStations() {
        val spots = MetroMapAsset.parseHotspots(asset("seoul_metro_map_hotspots.tsv").readText(Charsets.UTF_8))
        val index = StationIndex.parseTsv(asset("subway_stations.tsv").readText(Charsets.UTF_8))
        val capital = index.groups.filter { com.eunho.tagohub.data.SubwayRegion.of(it.lat, it.lng) == com.eunho.tagohub.data.SubwayRegion.Capital }.map { it.name }.toSet()
        val covered = capital.count { n -> spots.any { it.name == n } }
        // 글자 인식이라 전부는 아님. 대부분(80% 이상)은 눌러서 열려야 함
        assertTrue("수도권 역 ${capital.size}개 중 $covered 개", covered >= capital.size * 0.8)
        // 주요 역은 모두 있어야 함
        for (n in listOf("강남", "서울", "시청", "홍대입구", "잠실", "신도림", "고속터미널", "왕십리")) {
            assertNotNull("$n 없음", spots.firstOrNull { it.name == n })
        }
        // 위치는 그림 안
        assertTrue(spots.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
