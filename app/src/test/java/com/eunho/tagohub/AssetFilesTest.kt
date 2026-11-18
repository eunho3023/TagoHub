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
}
