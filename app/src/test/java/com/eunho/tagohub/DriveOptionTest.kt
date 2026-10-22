package com.eunho.tagohub

import com.eunho.tagohub.data.DriveOption
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveOptionTest {
    @Test
    fun eachOptionSendsTheRightKakaoParameters() {
        assertEquals(listOf("priority" to "RECOMMEND"), DriveOption.Recommend.query())
        assertEquals(listOf("priority" to "DISTANCE"), DriveOption.Shortest.query())
        assertEquals(listOf("priority" to "RECOMMEND", "avoid" to "toll"), DriveOption.NoToll.query())
        assertEquals(listOf("priority" to "RECOMMEND", "avoid" to "motorway"), DriveOption.NoMotorway.query())
    }

    @Test
    fun optionsHaveDistinctReadableLabels() {
        val labels = DriveOption.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(listOf("추천", "최단거리", "무료도로", "고속도로 제외"), labels)
    }
}
