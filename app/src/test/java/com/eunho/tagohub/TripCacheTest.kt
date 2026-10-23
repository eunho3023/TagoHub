package com.eunho.tagohub

import com.eunho.tagohub.data.TripCache
import com.eunho.tagohub.data.Trip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime

/** 마지막 조회 저장본: 저장·읽기, 오래된 것 정리, 깨진 줄 무시 */
class TripCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun reset() {
        TripCache.clock = { System.currentTimeMillis() }
    }

    private val ktx = Trip(
        "KTX", "1", "서울", "부산", LocalDateTime.of(2026, 10, 23, 5, 13), LocalDateTime.of(2026, 10, 23, 7, 50),
        listOf("어른" to 59800), depId = "NAT010000", arrId = "NAT014445",
    )
    private val noFare = Trip("무궁화", null, "서울", "대전", null, null, emptyList())

    @Test
    fun encodeAndDecodeRoundTripIncludingMissingFields() {
        val back = TripCache.decode(TripCache.encode(listOf(ktx, noFare)))
        assertEquals(listOf(ktx, noFare), back)
    }

    @Test
    fun putThenGetReturnsTheSameTripsAndUnknownKeysAreNull() {
        TripCache.init(tmp.root)
        val key = TripCache.key("NAT010000", "NAT014445", LocalDate.of(2026, 10, 23), null)
        assertNull(TripCache.get(key))
        TripCache.put(key, listOf(ktx))
        assertEquals(listOf(ktx), TripCache.get(key))
        assertNull(TripCache.get(TripCache.key("NAT010000", "NAT014445", LocalDate.of(2026, 10, 24), null)))
        assertNull(TripCache.get(TripCache.key("NAT010000", "NAT014445", LocalDate.of(2026, 10, 23), "00")))   // 차종 필터가 다르면 다른 조회
    }

    @Test
    fun emptyResultsAreNotSavedSoAGoodSavedCopyIsNotReplacedByNothing() {
        TripCache.init(tmp.root)
        val key = TripCache.key("A", "B", LocalDate.of(2026, 10, 23), null)
        TripCache.put(key, listOf(ktx))
        TripCache.put(key, emptyList())
        assertEquals(listOf(ktx), TripCache.get(key))
    }

    @Test
    fun savedCopiesOlderThanAWeekAreNotUsed() {
        TripCache.init(tmp.root)
        val key = TripCache.key("A", "B", LocalDate.of(2026, 10, 23), null)
        TripCache.put(key, listOf(ktx))
        val now = System.currentTimeMillis()
        TripCache.clock = { now + 8L * 24 * 3600 * 1000 }
        assertNull(TripCache.get(key))
    }

    @Test
    fun onlyTheNewestFortyQueriesAreKept() {
        TripCache.init(tmp.root)
        val base = System.currentTimeMillis()
        for (i in 0 until 45) {
            TripCache.clock = { base + i * 1000L }
            TripCache.put(TripCache.key("A$i", "B", LocalDate.of(2026, 10, 23), null), listOf(ktx))
        }
        val files = tmp.root.resolve("trip_cache").listFiles { f -> f.extension == "tsv" }!!
        assertEquals(40, files.size)
        TripCache.clock = { base + 50_000L }
        assertNull(TripCache.get(TripCache.key("A0", "B", LocalDate.of(2026, 10, 23), null)))     // 가장 오래된 것은 지워짐
        assertTrue(TripCache.get(TripCache.key("A44", "B", LocalDate.of(2026, 10, 23), null)) != null)
    }

    @Test
    fun brokenLinesAreIgnored() {
        assertEquals(emptyList<Trip>(), TripCache.decode("not\ta\tvalid\tline"))
        assertEquals(1, TripCache.decode("not valid\n" + TripCache.encode(listOf(ktx))).size)
    }
}
