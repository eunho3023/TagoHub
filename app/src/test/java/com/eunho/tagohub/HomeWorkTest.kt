package com.eunho.tagohub

import com.eunho.tagohub.data.HomeWork
import com.eunho.tagohub.data.SavedKind
import com.eunho.tagohub.data.SavedPlace
import com.eunho.tagohub.data.SavedPlaceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWorkTest {
    @Test
    fun codecRoundTripsNamesWithSpecialCharacters() {
        val p = SavedPlace("서울시 | 강남구 \\ 역삼동", 37.5, 127.03)
        assertEquals(p, SavedPlaceCodec.decode(SavedPlaceCodec.encode(p)))
        assertEquals("", SavedPlaceCodec.encode(null))
        assertNull(SavedPlaceCodec.decode(""))
        assertNull(SavedPlaceCodec.decode(null))
    }

    @Test
    fun codecRejectsBrokenOrImpossibleValues() {
        assertNull(SavedPlaceCodec.decode("이름|abc|127.0"))
        assertNull(SavedPlaceCodec.decode("이름|37.5"))
        assertNull(SavedPlaceCodec.decode("이름|137.5|127.0")) // 위도 범위 밖
        assertNull(SavedPlaceCodec.decode("a|1.0|2.0|3.0"))
    }

    @Test
    fun withReplacesOnlyTheGivenKind() {
        val home = SavedPlace("집", 35.8, 127.1)
        val work = SavedPlace("회사", 35.9, 127.2)
        val hw = HomeWork().with(SavedKind.Home, home).with(SavedKind.Work, work)
        assertEquals(home, hw[SavedKind.Home])
        assertEquals(work, hw[SavedKind.Work])
        val cleared = hw.with(SavedKind.Home, null)
        assertNull(cleared.home)
        assertEquals(work, cleared.work)
    }

    @Test
    fun morningSuggestsWorkAndEveningSuggestsHome() {
        assertEquals(SavedKind.Work, HomeWork.suggested(7))
        assertEquals(SavedKind.Work, HomeWork.suggested(4))
        assertEquals(SavedKind.Work, HomeWork.suggested(13))
        assertEquals(SavedKind.Home, HomeWork.suggested(14))
        assertEquals(SavedKind.Home, HomeWork.suggested(18))
        assertEquals(SavedKind.Home, HomeWork.suggested(23))
        assertEquals(SavedKind.Home, HomeWork.suggested(2))
    }
}
