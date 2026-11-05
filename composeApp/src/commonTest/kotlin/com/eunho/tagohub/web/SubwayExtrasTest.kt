package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals

class SubwayExtrasTest {
    @Test fun congestionAndSlots() {
        val t = SubwayExtrasRepo.parseCongestion("W\t1호선\t서울역\t상선\t9,22,x\nbad\n")
        assertEquals(listOf(9, 22, -1), t["W|1호선|서울"]!![0].values)
        assertEquals(0, SubwayExtrasRepo.slotOf(4, 0))
        assertEquals(1, SubwayExtrasRepo.slotOf(6, 10))
        assertEquals(38, SubwayExtrasRepo.slotOf(0, 40))
        assertEquals("00:30", SubwayExtrasRepo.slotLabel(38))
        assertEquals("다소 혼잡", SubwayExtrasRepo.describe(120))
    }

    @Test fun facilitiesPickCityAndLine() {
        val t = SubwayExtrasRepo.parseFacilities(
            "operator\tline\tstation\tcategory\tdetail\n" +
                "서울교통공사\t1호선\t서울역\t화장실\t개찰구 안\n" +
                "부산교통공사\t1호선\t서울\t화장실\t부산 것\n" +
                "코레일\t경의중앙\t서울역\t승강장\t2번\n",
        )
        val seoul1 = SubwayExtrasRepo.pick(t["서울"]!!, SubwayStationW("MTRS11150", "서울역", "1호선"))
        assertEquals(listOf("개찰구 안"), seoul1.map { it.detail })
        val busan = SubwayExtrasRepo.pick(t["서울"]!!, SubwayStationW("MTRBS10100", "서울", "1호선"))
        assertEquals(listOf("부산 것"), busan.map { it.detail })
        val gj = SubwayExtrasRepo.pick(t["서울"]!!, SubwayStationW("MTRKRK4P313", "서울역", "경의중앙"))
        assertEquals(listOf("2번"), gj.map { it.detail })
    }
}
