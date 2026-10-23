package com.eunho.tagohub

import com.eunho.tagohub.core.Chosung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosungTest {
    @Test
    fun initialsMatchConsecutiveSyllables() {
        assertTrue(Chosung.contains("서울역", "ㅅㅇㅇ"))
        assertTrue(Chosung.contains("서울역", "ㅇㅇ"))          // 가운데부터
        assertFalse(Chosung.contains("서울역", "ㅅㅂㅇ"))
        assertFalse(Chosung.contains("서울", "ㅅㅇㅇ"))         // 검색어가 더 김
    }

    @Test
    fun initialsAndRealSyllablesCanBeMixed() {
        assertTrue(Chosung.contains("서울역", "ㅅ울"))
        assertTrue(Chosung.contains("서울역", "서ㅇㅇ"))
        assertFalse(Chosung.contains("서울역", "서ㅂ"))
    }

    @Test
    fun plainTextWorksLikeContains() {
        assertTrue(Chosung.contains("강남역 2호선", "남역"))
        assertFalse(Chosung.contains("강남역", "서울"))
        assertEquals(0, Chosung.indexOf("강남", "강"))
        assertEquals(1, Chosung.indexOf("강남", "ㄴ"))
    }

    @Test
    fun spacesAreIgnoredAndBlankNeverMatches() {
        assertTrue(Chosung.contains("서울 시청", "ㅅㅇㅅㅊ"))
        assertTrue(Chosung.contains("서울시청", "ㅅㅇ ㅅㅊ"))
        assertFalse(Chosung.contains("서울", ""))
    }

    @Test
    fun doubleInitialsAreDistinct() {
        assertTrue(Chosung.contains("까치산", "ㄲㅊㅅ"))
        assertFalse(Chosung.contains("가치산", "ㄲㅊㅅ"))
    }

    @Test
    fun hasInitialsDetectsJamo() {
        assertTrue(Chosung.hasInitials("ㄱㄴ"))
        assertFalse(Chosung.hasInitials("강남"))
    }
}
