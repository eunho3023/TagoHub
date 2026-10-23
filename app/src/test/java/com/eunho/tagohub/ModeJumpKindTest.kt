package com.eunho.tagohub

import com.eunho.tagohub.ui.ModeJump
import com.eunho.tagohub.ui.ModeJump.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 지도에서 누른 장소의 이름으로 기차·고속시외·항공을 가려내기 (이름에 들어 있기만 한 장소는 제외) */
class ModeJumpKindTest {
    @Test
    fun realStationsTerminalsAndAirports() {
        assertEquals(Kind.Train, ModeJump.kindOf("서울역"))
        assertEquals(Kind.Train, ModeJump.kindOf("대전역 (KTX)"))
        assertEquals(Kind.Coach, ModeJump.kindOf("서울고속버스터미널"))
        assertEquals(Kind.Coach, ModeJump.kindOf("동서울터미널"))
        assertEquals(Kind.Air, ModeJump.kindOf("김포국제공항"))
        assertEquals(Kind.Air, ModeJump.kindOf("인천국제공항 제1여객터미널"))
        assertEquals(Kind.Air, ModeJump.kindOf("제주공항 국내선"))
    }

    @Test
    fun placesThatOnlyMentionThemAreNot() {
        assertNull(ModeJump.kindOf("서울역 맛집"))
        assertNull(ModeJump.kindOf("공항초등학교"))
        assertNull(ModeJump.kindOf("터미널 약국"))
        assertNull(ModeJump.kindOf("공항철도 청라국제도시역"))
        assertNull(ModeJump.kindOf("강남역 스타벅스"))
        assertNull(ModeJump.kindOf("화물터미널"))
    }
}
