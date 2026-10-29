package com.eunho.tagohub

import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카카오가 회전교차로를 "회전교차로에서 오른쪽 3시 방향"(종류 70~81)으로 알려 주는 것을 실제 응답대로 */
class RoundaboutWordingTest {
    private fun guide(text: String, type: Int = 72, name: String = "") = DriveGuide(name, text, 300, type, 36.5, 127.2)

    @Test
    fun exitToTheRightIsNotCalledATurnRight() {
        val g = guide("조치원 방면으로 회전교차로에서 오른쪽 3시 방향")
        assertEquals("조치원 방면으로 회전교차로에서 오른쪽으로 나가세요", TurnKind.text(g))
        assertEquals("회전교차로 오른쪽 · 조치원 방면", TurnKind.bannerText(g))
    }

    @Test
    fun exitToTheLeftAndStraight() {
        assertEquals("대전 방면으로 회전교차로에서 왼쪽으로 나가세요", TurnKind.text(guide("대전 방면으로 회전교차로에서 왼쪽 8시 방향", 77)))
        assertEquals("회전교차로에서 직진하세요", TurnKind.text(guide("회전교차로에서 12시 방향", 81)))
        assertEquals("회전교차로 직진", TurnKind.bannerText(guide("회전교차로에서 12시 방향", 81)))
    }

    @Test
    fun goingStraightThroughARoundaboutIsStillAnnounced() {
        assertTrue(TurnKind.isManeuverGuide(guide("회전교차로에서 12시 방향", 81)))
        assertFalse(TurnKind.isManeuverGuide(guide("12시 방향", 29)))   // 일반 교차로 직진은 안내하지 않음
    }

    @Test
    fun ordinaryTurnsKeepTheirWording() {
        assertEquals("우회전", TurnKind.text(guide("우회전", 2)))
        assertEquals("서초IC에서 오른쪽 방향", TurnKind.text(guide("1시 방향", 6, "서초IC")))
        assertEquals("서초IC에서 우회전", TurnKind.text(guide("3시 방향", 6, "서초IC")))
    }
}
