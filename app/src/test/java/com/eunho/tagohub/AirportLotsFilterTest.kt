package com.eunho.tagohub

import com.eunho.tagohub.data.AirportLive
import org.junit.Assert.assertEquals
import org.junit.Test

class AirportLotsFilterTest {
    private fun lot(airport: String, name: String) = AirportLive.Lot(airport, name, null, null, null, null, null)
    private val all = listOf(
        lot("김포국제공항", "국내선 제1주차장"), lot("김해국제공항", "P1 여객주차장"),
        lot("대구국제공항", "여객주차장"), lot("대구국제공항", "화물주차장"), lot("군산공항", "여객주차장"),
    )

    @Test
    fun keepsOnlyThatAirportsLots() {
        assertEquals(listOf("여객주차장", "화물주차장"), AirportLive.lotsOf("TAE", all).map { it.name })
        assertEquals(listOf("국내선 제1주차장"), AirportLive.lotsOf("GMP", all).map { it.name })
    }

    @Test
    fun airportMissingFromTheApiGetsNothing() {
        assertEquals(emptyList<AirportLive.Lot>(), AirportLive.lotsOf("KPO", all))
    }
}
