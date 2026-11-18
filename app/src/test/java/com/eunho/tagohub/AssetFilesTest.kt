package com.eunho.tagohub

import com.eunho.tagohub.data.AirRoutes
import com.eunho.tagohub.data.ExMapLayers
import com.eunho.tagohub.data.UlsanSignals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** API 대신 넣은 앱 파일(pipeline/build_api_files.py)이 앱의 해석 함수로 그대로 읽히는지 */
class AssetFilesTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.exists() }.readText()

    @Test
    fun expresswayPlacesReadAsApiRows() {
        val json = asset("ex_places.json")
        for (layer in listOf(ExMapLayers.REST, ExMapLayers.TOLL, ExMapLayers.IC)) {
            val rows = ExMapLayers.parsePlaces(json, layer)
            assertTrue("$layer ${rows.size}", rows.size > 100)
            assertTrue(rows.count { it["xValue"].orEmpty().isNotEmpty() } > rows.size / 2)   // 좌표 없는 행은 API 에도 있음 (앱이 건너뜀)
        }
    }

    @Test
    fun airRoutesAndUlsanCrossroadsParse() {
        val air = AirRoutes.parse(asset("air_routes.json"))
        assertTrue(air.size > 300)
        assertTrue(AirRoutes.find(air, "GMP", "CJU") != null)
        assertTrue(UlsanSignals.parseIntersections(asset("ulsan_crossroads.json")).size > 300)
    }

    @Test
    fun subwayScheduleFileHasNationwideTimetables() {
        val text = asset("subway_schedule.tsv")
        com.eunho.tagohub.data.SubwayScheduleFile.source = { text }
        assertTrue(com.eunho.tagohub.data.SubwayScheduleFile.stations("서울역")!!.isNotEmpty())
        val d = com.eunho.tagohub.data.SubwayScheduleFile.schedule("MTRARA1A01", "01", "D")!!   // 공항철도 서울역 평일 하행
        assertTrue(d.size > 100 && d.zipWithNext().all { (a, b) -> a.serviceMinute <= b.serviceMinute })
        assertTrue(d.all { it.endStationName.isNotEmpty() && it.serviceMinute in 180 until 1620 })
    }
}
