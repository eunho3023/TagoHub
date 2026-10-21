package com.eunho.tagohub

import com.eunho.tagohub.data.ExMapLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExCongestTest {
    private fun vdsText(): String {
        val f = listOf("src/main/assets/ex_vds.json", "app/src/main/assets/ex_vds.json").map { File(it) }.first { it.exists() }
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun bundledVdsFileHasCoordinatesInsideKorea() {
        val vds = ExMapLayers.parseVds(vdsText())
        assertTrue("VDS 수 ${vds.size}", vds.size > 6000)
        assertTrue(vds.values.all { (lat, lng) -> lat in 32.0..39.5 && lng in 123.0..133.0 })
        // 실제 API 값(경부선 1.4km, 부산 구서IC 근처)이 맞게 변환됐는지
        val (lat, lng) = vds.getValue("0010VDE00100")
        assertEquals(35.257, lat, 0.01)
        assertEquals(129.102, lng, 0.01)
    }

    @Test
    fun congestionBecomesMarkersAndSkipsUnknownPositions() {
        val coords = mapOf("0010VDE26500" to (36.8 to 127.15))
        val rows = listOf(
            mapOf("stdHour" to "2203", "routeName" to "경부선", "updownTypeCode" to "E", "vdsId" to "0010VDE26500", "conzoneId" to "0010CZE380", "conzoneName" to "천안IC-북천안IC", "speed" to "29", "grade" to "3"),
            // 같은 구간의 다른 검지기: 더 느린 쪽만 남김
            mapOf("stdHour" to "2203", "routeName" to "경부선", "updownTypeCode" to "E", "vdsId" to "0010VDE26600", "conzoneId" to "0010CZE380", "conzoneName" to "천안IC-북천안IC", "speed" to "40", "grade" to "3"),
            // 위치를 모르는 검지기
            mapOf("stdHour" to "2203", "routeName" to "논산천안선", "vdsId" to "0252VDE18870", "conzoneId" to "0252CZE080", "conzoneName" to "정안IC-남풍세IC", "speed" to "11"),
        )
        val f = ExMapLayers.congest(rows, coords)
        assertEquals(1, f.size)
        assertEquals("경부선 천안IC-북천안IC 정체", f.single().title)
        assertTrue("속도 29km/h" in f.single().details)
        assertTrue("22:03 기준" in f.single().details)
        assertEquals(36.8, f.single().lat, 1e-9)
    }
}
