package com.eunho.tagohub

import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.FavoriteCodec
import com.eunho.tagohub.data.TagoIds
import org.junit.Assert.assertEquals
import org.junit.Test

class TagoIdsTest {
    /** 실제 TAGO 응답과 앱 안 파일로 맞춰 본 인천 ID (2026-11) */
    @Test
    fun incheonIds() {
        assertEquals("ICB366000623", TagoIds.current("ICB166000623"))   // 동서식품 정류장
        assertEquals("ICB410000001", TagoIds.current("ICB210000001"))   // 삼산체육관역 정류장
        assertEquals("ICB361000002", TagoIds.current("ICB161000002"))   // 6777번 노선
        assertEquals("ICB166000623", TagoIds.legacy("ICB366000623"))
        assertEquals("ICB210000001", TagoIds.legacy("ICB410000001"))
    }

    @Test
    fun otherIdsUnchanged() {
        for (id in listOf("ICB900000001", "GGB166000624", "JUB312101702", "SEB100000017", "TSB312000093", "ICB", "")) {
            assertEquals(id, TagoIds.current(id))
            assertEquals(id, TagoIds.legacy(id))
        }
    }

    @Test
    fun savedFavoritesMoveToNewIds() {
        val saved = FavoriteCodec.encode(listOf(Favorite(Favorite.Kind.Stop, "ICB166000623", "동서식품", null, "23")))
        assertEquals("ICB366000623", FavoriteCodec.decode(saved).single().id)
    }
}
