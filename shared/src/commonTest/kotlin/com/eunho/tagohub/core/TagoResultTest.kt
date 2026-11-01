package com.eunho.tagohub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagoResultTest {
    @Test
    fun mapTransformsSuccess() {
        assertEquals(
            TagoResult.Success("42"),
            TagoResult.Success(42).map { it.toString() },
        )
    }

    @Test
    fun mapPreservesError() {
        val error = TagoResult.Error("timeout")
        assertEquals(error, error.map { it.toString() })
    }

    @Test
    fun loadHelpersTranslateAndReadValues() {
        assertEquals(Load.Ok("ready"), TagoResult.Success("ready").toLoad())
        assertEquals("ready", Load.Ok("ready").valueOrNull())
        assertNull(Load.Loading.valueOrNull())
    }
}
