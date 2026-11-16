package com.eunho.tagohub

import com.eunho.tagohub.data.SeoulTopis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeoulTopisTest {
    /** 소통 등급: TOPIS 기준 (도시고속도로 70·30, 일반도로 25·15 km/h), 웹 topis.js speedColor 와 같음 */
    @Test
    fun grade_followsTopisThresholds() {
        assertEquals(1, SeoulTopis.grade("02", 70))
        assertEquals(2, SeoulTopis.grade("02", 69))
        assertEquals(3, SeoulTopis.grade("02", 29))
        assertEquals(1, SeoulTopis.grade("04", 25))
        assertEquals(2, SeoulTopis.grade("04", 15))
        assertEquals(3, SeoulTopis.grade("05", 14))
    }

    @Test
    fun inSeoul_onlyAroundSeoul() {
        assertTrue(SeoulTopis.inSeoul(37.49, 127.02, 37.51, 127.04))      // 강남역
        assertFalse(SeoulTopis.inSeoul(35.80, 127.10, 35.85, 127.15))     // 전주
    }
}
