package com.eunho.tagohub

import com.eunho.tagohub.data.SubwayScheduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubwayScheduleFileTest {
    @Test
    fun readsStationsAndDepartures() {
        // 05:20 → 320 = "8w", 25:10(01:10) → 1510 = "15y"
        SubwayScheduleFile.source = {
            "S\tMTRARA1A01\t서울역\t공항\tMTRARA1\nT\tMTRARA1A01\t01\tD\tMTRARA1A07~검암|MTRARA1A11~인천공항2터미널\t8wb 15yA\nT\tMTRARA1A01\t03\tU\t\t\n"
        }
        assertEquals("MTRARA1A01", SubwayScheduleFile.stations("서울")!!.single().id)
        assertNull(SubwayScheduleFile.stations("부산"))
        val d = SubwayScheduleFile.schedule("MTRARA1A01", "01", "D")!!
        assertEquals(listOf(320, 1510), d.map { it.serviceMinute })
        assertEquals("인천공항2터미널", d[0].endStationName)
        assertTrue(d[1].arrivalOnly && d[1].endStationId == "MTRARA1A07")
        assertEquals(emptyList<Any>(), SubwayScheduleFile.schedule("MTRARA1A01", "03", "U"))   // 운행 없음
        assertNull(SubwayScheduleFile.schedule("MTRARA1A01", "02", "D"))                       // 파일에 없음 → API
    }
}
