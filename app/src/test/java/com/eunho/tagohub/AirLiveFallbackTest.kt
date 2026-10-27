package com.eunho.tagohub

import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.AirSchedule
import com.eunho.tagohub.data.KacIntlClient
import com.eunho.tagohub.data.Trip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AirLiveFallbackTest {
    private fun trip(kind: String) = Trip(kind, "KE1", "김포", "제주", null, null, emptyList())

    @Test
    fun fileSeasonNeverCallsTheApi() = runBlocking {
        var called = false
        val r = KacIntlClient.fileOrLive(AirSchedule.Result(listOf(trip("파일")), projected = false)) { called = true; TagoResult.Success(listOf(trip("API"))) }
        assertEquals(false, called)
        assertEquals("파일", (r as TagoResult.Success).data.single().kind)
    }

    @Test
    fun afterTheFileSeasonRealApiWinsOverProjection() = runBlocking {
        val r = KacIntlClient.fileOrLive(AirSchedule.Result(listOf(trip("예상")), projected = true)) { TagoResult.Success(listOf(trip("API"))) }
        assertEquals("API", (r as TagoResult.Success).data.single().kind)
    }

    @Test
    fun projectionStaysWhenApiFailsOrIsEmpty() = runBlocking {
        val file = AirSchedule.Result(listOf(trip("예상")), projected = true)
        val empty = KacIntlClient.fileOrLive(file) { TagoResult.Success(emptyList()) }
        val failed = KacIntlClient.fileOrLive(file) { TagoResult.Error("실패") }
        assertEquals("예상", (empty as TagoResult.Success).data.single().kind)
        assertEquals("예상", (failed as TagoResult.Success).data.single().kind)
    }
}
