package com.eunho.tagohub

import com.eunho.tagohub.data.Amenity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmenityTest {
    @Test
    fun tilesFromCenter() {
        assertEquals(listOf("186_634", "186_635", "187_634", "187_635"), Amenity.tilesIn(37.39, 126.99, 37.41, 127.01).sorted())
        assertEquals("186_634", Amenity.tilesIn(37.0, 126.6, 37.79, 127.39).first())
    }

    @Test
    fun parseAndFeature() {
        val body = """[["삼각지역",37.5345,126.9731,"공중화장실 · 24시간",""],["x","bad",1,"",""]]"""
        val list = Amenity.parseTile("amenity_toilet", "187_634", body)
        assertEquals(1, list.size)
        assertEquals(listOf("공중화장실 · 24시간"), list[0].lines)
        val f = Amenity.feature("amenity_toilet", list[0])
        assertEquals("amenity_toilet:187_634:0", f.id)
        assertEquals("amenity_toilet", f.layer)
        assertTrue(Amenity.parseTile("amenity_toilet", "x", "oops").isEmpty())
        assertTrue("amenity_mobility" in Amenity.LAYERS)
    }
}
