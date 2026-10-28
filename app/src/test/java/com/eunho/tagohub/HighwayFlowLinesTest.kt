package com.eunho.tagohub

import com.eunho.tagohub.data.ExMapLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighwayFlowLinesTest {
    private fun row(id: String, grade: String, speed: String = "80") =
        mapOf("vdsId" to id, "grade" to grade, "speed" to speed, "routeName" to "경부선", "conzoneName" to "A-B", "updownTypeCode" to "E")

    // 같은 노선·방향(0010VDE)의 검지기 3개가 약 1.1km 간격으로 늘어선 모습
    private val coords = mapOf(
        "0010VDE00100" to (37.000 to 127.000),
        "0010VDE00200" to (37.010 to 127.000),
        "0010VDE00300" to (37.020 to 127.000),
    )

    @Test
    fun neighbouringDetectorsBecomeOneColouredLinePerGrade() {
        val rows = listOf(row("0010VDE00100", "1"), row("0010VDE00200", "3", "20"), row("0010VDE00300", "3", "25"))
        val (lines, used) = ExMapLayers.flowLines(rows, coords)
        // 원활 → 정체로 바뀌는 가운데에서 갈라져 두 가닥
        assertEquals(listOf("1", "3"), lines.map { it.id.split(':')[1] })
        assertTrue(lines.all { (it.path?.size ?: 0) >= 2 })
        assertEquals(3, used.size)
        // 정체 쪽 선은 두 번째 검지기에서 세 번째 검지기까지 이어짐
        val red = lines.first { it.id.split(':')[1] == "3" }.path!!
        assertEquals(37.005, red.first().lat, 1e-9)
        assertEquals(37.020, red.last().lat, 1e-9)
    }

    @Test
    fun lonelyDetectorFallsBackToADotAndFarNeighboursAreNotJoined() {
        val far = coords + ("0010VDE00400" to (38.0 to 127.0))   // 100km 떨어짐
        val rows = listOf(row("0010VDE00100", "1"), row("0010VDE00200", "1"), row("0010VDE00400", "3"))
        val all = ExMapLayers.flowFeatures(rows, far)
        assertEquals(1, all.count { it.path != null })
        val dot = all.single { it.path == null }
        assertEquals(38.0, dot.lat, 0.0)
    }
}
