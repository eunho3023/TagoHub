package com.eunho.tagohub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainTableTest {
    private val text = "1\t\t실적평일\t서울@313;광명@333;대전@377;동대구@419;부산@474\n" +
        "3\t\t실적토\t서울@327;대전(구)@400;부산@504\n" +
        "5\tKTX\t금토일\t서울@1430;대전@1490\n" +
        "\t\t\tbad\n"

    @Test fun parsesAndFindsLegs() {
        val trains = TrainTable.parse(text)
        assertEquals(3, trains.size)
        assertEquals(listOf("1"), TrainTable.between(trains, "서울", "대전", 3).map { it.no })        // 수요일
        assertEquals(listOf("3", "5"), TrainTable.between(trains, "서울", "대전", 6).map { it.no })   // 토요일 (괄호 무시)
        assertTrue(TrainTable.between(trains, "대전", "서울", 3).isEmpty())                          // 반대 방향 없음
        val leg = TrainTable.between(trains, "서울", "대전", 5).last()
        assertEquals("00:50", TrainTable.clock(leg.arrMin))
        assertEquals("서울", TrainTable.stationNames(trains)["서울"])
        assertEquals("대전", TrainTable.stationNames(trains)["대전"])
    }

    @Test fun runsOnDays() {
        assertTrue(TrainTable.runsOn("실적평일", 1) && !TrainTable.runsOn("실적평일", 6))
        assertTrue(TrainTable.runsOn("월화수", 2) && !TrainTable.runsOn("월화수", 4))
        assertTrue(TrainTable.runsOn("실적일", 7))
    }
}
