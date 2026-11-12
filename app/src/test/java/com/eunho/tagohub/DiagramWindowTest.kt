package com.eunho.tagohub

import com.eunho.tagohub.ui.map.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** 배치도가 그릴 행 범위: 뷰포트 밖 행은 그리지 않고, 위아래 한 행씩만 여유를 둔다 */
class DiagramWindowTest {
    @Test
    fun `맨 위에서는 첫 행부터 뷰포트에 보이는 행까지`() {
        assertEquals(0..5, visibleRows(0, 1000f, 230f, 25))
    }

    @Test
    fun `스크롤하면 그 위치의 행부터`() {
        assertEquals(9..15, visibleRows(2300, 1000f, 230f, 25))
    }

    @Test
    fun `맨 아래에서는 마지막 행을 넘지 않는다`() {
        assertEquals(19..24, visibleRows(4750, 1000f, 230f, 25))
    }

    @Test
    fun `내용이 뷰포트보다 짧으면 전체`() {
        assertEquals(0..4, visibleRows(0, 2000f, 230f, 5))
    }

    @Test
    fun `행이 없으면 빈 범위`() {
        assertEquals(IntRange.EMPTY, visibleRows(0, 1000f, 230f, 0))
    }

    @Test
    fun `행 높이가 0이면 빈 범위`() {
        assertEquals(IntRange.EMPTY, visibleRows(0, 1000f, 0f, 10))
    }

    @Test
    fun `뷰포트 높이가 0이면 빈 범위`() {
        assertEquals(IntRange.EMPTY, visibleRows(0, 0f, 230f, 10))
    }

    @Test
    fun `범위를 넘는 스크롤도 마지막 행에서 멈춘다`() {
        assertEquals(24..24, visibleRows(99999, 1000f, 230f, 25))
    }
}
