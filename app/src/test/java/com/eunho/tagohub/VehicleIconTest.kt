package com.eunho.tagohub

import com.eunho.tagohub.data.VehicleIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleIconTest {
    @Test
    fun savedCodesMapBackAndUnknownFallsBackToDefault() {
        VehicleIcon.entries.forEach { assertEquals(it, VehicleIcon.of(it.code)) }
        assertEquals(VehicleIcon.Default, VehicleIcon.of(null))
        assertEquals(VehicleIcon.Default, VehicleIcon.of("spaceship"))
    }

    @Test
    fun sixChoicesAndOnlyTheDotIsNotAVehicle() {
        assertEquals(listOf("기본", "세단", "트럭", "버스", "오토바이", "자전거"), VehicleIcon.entries.map { it.label })
        assertFalse(VehicleIcon.Default.isVehicle)
        assertTrue(VehicleIcon.entries.filter { it != VehicleIcon.Default }.all { it.isVehicle })
        assertEquals(VehicleIcon.entries.size, VehicleIcon.entries.map { it.code }.toSet().size)   // 코드 중복 없음
    }
}
