package com.eunho.tagohub

import com.eunho.tagohub.data.TrainKinds
import com.eunho.tagohub.data.TrainTimetable
import com.eunho.tagohub.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

class TrainKindsTest {
    private fun trip(no: String?, kind: String) = Trip(kind, no, "서울", "부산", null, null, emptyList())

    @Test
    fun learnsNamesByNumberAndIgnoresUnknownOnes() {
        TrainKinds.clear()
        TrainKinds.learn(listOf(trip("101", "KTX"), trip("1001", "ITX-새마을"), trip("55", TrainKinds.UNKNOWN), trip("56", ""), trip(null, "KTX")))
        assertEquals("KTX", TrainKinds.get("101"))
        assertEquals("ITX-새마을", TrainKinds.get("1001"))
        assertNull(TrainKinds.get("55"))
        assertNull(TrainKinds.get("56"))
    }

    @Test
    fun timetableTripsGetTheLearnedNameInsteadOfGenericTrain() {
        TrainKinds.clear()
        TrainTimetable.set(listOf(TrainTimetable.Train("101", "", "실적평일", listOf("서울" to 480, "부산" to 640))))
        val mon = LocalDate.of(2026, 10, 26)
        assertEquals(TrainKinds.UNKNOWN, TrainTimetable.tripsBetween("서울", "부산", mon).single().kind)
        TrainKinds.learn(listOf(trip("101", "KTX")))
        assertEquals("KTX", TrainTimetable.tripsBetween("서울", "부산", mon).single().kind)
    }

    @Test
    fun savesAndReloadsFromTheFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kinds-test-${System.nanoTime()}").also { it.mkdirs() }
        TrainKinds.clear()
        TrainKinds.init(dir)
        TrainKinds.learn(listOf(trip("8", "무궁화호")))
        TrainKinds.clear()
        TrainKinds.init(dir)
        assertEquals("무궁화호", TrainKinds.get("8"))
        dir.deleteRecursively()
    }
}
