package com.eunho.tagohub

import com.eunho.tagohub.data.RouteDirections
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 방면을 말할 수 없는 노선은 나누지 않음 */
class RouteSplitTest {
    private fun st(i: Int, name: String, ud: String?) = RouteStop("N$i", name, null, i, ud, 35.8 + i * 0.001, 127.1)
    private fun route(turnName: String, ud: Boolean = true) = listOf(
        st(1, "기점", if (ud) "0" else null), st(2, "가", if (ud) "0" else null), st(3, turnName, if (ud) "0" else null),
        st(4, "나", if (ud) "1" else null), st(5, "다", if (ud) "1" else null), st(6, "기점", if (ud) "1" else null),
    )

    @Test fun splitsWhenBothDirectionsHaveNames() {
        assertEquals(2, RouteDirections.splitIndex(route("시청"), "100"))
    }

    @Test fun noSplitWithoutDirectionCodesOrForLoopsOrSameNames() {
        assertNull(RouteDirections.splitIndex(route("시청", ud = false), "100"))   // 방향 코드 없음
        assertNull(RouteDirections.splitIndex(route("시청"), "순환1"))              // 순환 노선
        assertNull(RouteDirections.splitIndex(route("기점"), "100"))               // 두 방면 이름이 같음
    }
}
