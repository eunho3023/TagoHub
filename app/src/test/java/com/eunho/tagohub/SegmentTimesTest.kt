package com.eunho.tagohub

import com.eunho.tagohub.data.SegmentTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SegmentTimesTest {
    private val nowSec = System.currentTimeMillis() / 1000

    private fun setup(lines: List<String>): File {
        val root = createTempDir()
        SegmentTimes.init(root)
        File(root, "seg_times").resolve("R1.tsv").writeText(lines.joinToString("") { it + "\n" })
        return root
    }

    @Test
    fun sameSegmentUsesMedian() {
        setup(listOf("1\t2\t60\t$nowSec", "1\t2\t80\t$nowSec", "1\t2\t70\t$nowSec"))
        assertEquals(70.0, SegmentTimes.seconds("R1", 1, 2)!!, 0.001)
    }

    @Test
    fun shortSegmentsAreAddedUp() {
        setup(listOf("1\t2\t60\t$nowSec", "2\t3\t40\t$nowSec"))
        assertEquals(100.0, SegmentTimes.seconds("R1", 1, 3)!!, 0.001)
    }

    @Test
    fun skippedStopsAreSplitEvenly() {
        // 1→4 를 한 번에 90초로 본 기록뿐이면 1→2 는 30초로 나눠 쓴다
        setup(listOf("1\t4\t90\t$nowSec"))
        assertEquals(30.0, SegmentTimes.seconds("R1", 1, 2)!!, 0.001)
        assertEquals(60.0, SegmentTimes.seconds("R1", 1, 3)!!, 0.001)
    }

    @Test
    fun unknownSegmentIsNull() {
        setup(listOf("5\t6\t60\t$nowSec"))
        assertNull(SegmentTimes.seconds("R1", 1, 2))
        assertNull(SegmentTimes.seconds("R1", 2, 2))
    }
}
