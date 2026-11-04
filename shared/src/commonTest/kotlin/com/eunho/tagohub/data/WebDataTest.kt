package com.eunho.tagohub.data

import kotlin.test.Test
import kotlin.test.assertEquals

class WebDataTest {
    /** pipeline/test_build_web_data.py 와 같은 값 */
    @Test
    fun sameAsPipeline() {
        assertEquals(992, WebData.bucket("BSB5200111000"))
        assertEquals(871, WebData.bucket("JJB306000104"))
        assertEquals(521, WebData.bucket("정류장"))
        assertEquals("358_1271", WebData.tile(35.8235, 127.1478))
        assertEquals(listOf("358_1271", "358_1272", "359_1271", "359_1272"), WebData.tiles(35.85, 127.15, 35.95, 127.25))
        assertEquals("d64d", WebData.nameShard(" 홍산로"))
        assertEquals("data/routes/871.json", WebData.routePath("JJB306000104"))
    }
}
