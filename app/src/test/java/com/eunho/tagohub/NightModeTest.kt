package com.eunho.tagohub

import com.eunho.tagohub.data.NightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    @Test
    fun nightIsEveningToMorning() {
        assertTrue(NightMode.isNightHour(19)); assertTrue(NightMode.isNightHour(23)); assertTrue(NightMode.isNightHour(0)); assertTrue(NightMode.isNightHour(5))
        assertFalse(NightMode.isNightHour(6)); assertFalse(NightMode.isNightHour(12)); assertFalse(NightMode.isNightHour(18))
    }

    @Test
    fun dimsOnlyWhileDrivingAndAccordingToTheSetting() {
        assertFalse(NightMode.dimNow(NightMode.Always, driving = false, hour = 23))      // 안 달리면 안 어둡게
        assertTrue(NightMode.dimNow(NightMode.Always, driving = true, hour = 12))        // 항상
        assertFalse(NightMode.dimNow(NightMode.Auto, driving = true, hour = 12))         // 낮
        assertTrue(NightMode.dimNow(NightMode.Auto, driving = true, hour = 22))          // 밤
        assertFalse(NightMode.dimNow(NightMode.Off, driving = true, hour = 22))          // 끔
    }

    @Test
    fun savedCodeFallsBackToAuto() {
        NightMode.entries.forEach { assertEquals(it, NightMode.of(it.code)) }
        assertEquals(NightMode.Auto, NightMode.of(null))
        assertEquals(NightMode.Auto, NightMode.of("zzz"))
    }
}
