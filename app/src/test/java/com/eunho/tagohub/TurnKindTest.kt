package com.eunho.tagohub

import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavEngine
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TurnKindTest {
    private fun route(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    private fun guide(text: String, type: Int = 29, name: String = "") = DriveGuide(name, text, 100, type, 37.0, 127.0)

    @Test
    fun classifiesEveryRealGuideInTheFixture() {
        val r = route()
        val kinds = r.guides.groupBy { TurnKind.of(it) }.mapValues { it.value.size }
        assertEquals(1, kinds[TurnKind.Start])
        assertEquals(1, kinds[TurnKind.Destination])
        assertEquals(2, kinds[TurnKind.Left])
        assertEquals(6, kinds[TurnKind.Right])
        assertEquals(2, kinds[TurnKind.HighwayEnter])
        assertEquals(2, kinds[TurnKind.HighwayExit])
        assertEquals(3, kinds[TurnKind.Toll])
        // 교차로를 직진으로 지나가는 안내("12시 방향", "왼쪽 직진")가 대부분
        assertEquals(38, kinds[TurnKind.Straight])
        assertEquals(55, kinds.values.sum())
    }

    @Test
    fun routeKeepsOnlyRealManeuvers() {
        val r = route()
        // 회전 8 + 고속도로 입·출구 4 + 톨게이트 3 + 목적지 1
        assertEquals(16, r.maneuvers.size)
        assertTrue(r.maneuvers.none { TurnKind.of(r.guides[it]) == TurnKind.Straight })
        assertTrue(r.guides[r.maneuvers.last()].isDestination)
    }

    @Test
    fun clockPhrasesAreReadablePhrases() {
        assertEquals(TurnKind.Straight, TurnKind.of(guide("12시 방향")))
        assertEquals(TurnKind.SlightLeft, TurnKind.of(guide("11시 방향")))
        assertEquals(TurnKind.Left, TurnKind.of(guide("9시 방향")))
        assertEquals(TurnKind.SlightRight, TurnKind.of(guide("1시 방향")))
        assertEquals(TurnKind.Right, TurnKind.of(guide("3시 방향")))
        assertEquals(TurnKind.UTurn, TurnKind.of(guide("유턴")))
        assertEquals("왼쪽 방향", TurnKind.text(guide("11시 방향")))
        assertEquals("서초IC에서 오른쪽 방향", TurnKind.text(guide("2시 방향", name = "서초IC")))
        // 일반 문구는 그대로
        assertEquals("양재역 방면으로 좌회전", TurnKind.text(guide("양재역 방면으로 좌회전", type = 1)))
        assertEquals("좌회전 · 양재역 방면", TurnKind.bannerText(guide("양재역 방면으로 좌회전", type = 1)))
        assertEquals("오른쪽 방향 · 서초IC", TurnKind.bannerText(guide("2시 방향", name = "서초IC")))
        assertEquals("직진", TurnKind.bannerText(guide("직진")))
    }

    @Test
    fun engineSkipsStraightGuidesAndShowsTheNextTwoTurns() {
        val r = route()
        val engine = NavEngine(r)
        val p = r.points.first()
        val st = engine.update(p.lat, p.lng, 10.0)
        // 첫 동작은 출발 후 328m 지점의 우회전 (그 앞의 직진 안내는 건너뜀)
        assertEquals("역삼초교 도곡로 방면으로 우회전", st.nextGuide!!.guidance)
        assertEquals(TurnKind.Right, TurnKind.of(st.nextGuide!!))
        assertTrue("다음 회전까지 거리 ${st.distanceToNext}", st.distanceToNext in 300.0..360.0)
        assertTrue(st.afterGuide != null)
        assertTrue(st.distanceNextToAfter > 0)
        assertFalse(TurnKind.of(st.afterGuide!!) == TurnKind.Straight)
    }

    @Test
    fun previewStepsListOnlyManeuversAndSumToTheRouteLength() {
        val r = route()
        val steps = com.eunho.tagohub.data.driveSteps(r.guides)
        // 출발 + 회전·입출구·톨게이트 15 + 목적지 = 17
        assertEquals(17, steps.size)
        assertEquals(TurnKind.Start, steps.first().kind)
        assertEquals("출발", steps.first().text)
        assertEquals(TurnKind.Destination, steps.last().kind)
        assertEquals(0, steps.last().distanceAfterMeters)
        assertTrue(steps.none { it.kind == TurnKind.Straight })
        assertTrue(steps.dropLast(1).all { it.distanceAfterMeters > 0 })
        // 각 단계 뒤 거리의 합 = 경로 전체 길이
        assertEquals(r.guides.sumOf { it.distanceFromPrev }, steps.sumOf { it.distanceAfterMeters })
    }

    @Test
    fun trafficSpansCoverTheRemainingRouteAndShrinkAsYouDrive() {
        val r = route()
        val all = r.trafficSpans(0.0)
        assertTrue(all.isNotEmpty())
        assertEquals(0f, all.first().from, 1e-4f)
        assertEquals(1f, all.last().to, 1e-3f)
        // 서로 겹치지 않고 이어져야 함
        all.zipWithNext().forEach { (a, b) -> assertTrue("겹침 ${a.to} > ${b.from}", a.to <= b.from + 1e-4f) }
        assertTrue(all.any { it.state == 4 })
        // 절반쯤 갔을 때: 남은 구간 기준으로 다시 0~1
        val half = r.trafficSpans(r.length / 2)
        assertEquals(0f, half.first().from, 1e-4f)
        assertEquals(1f, half.last().to, 1e-3f)
        assertTrue(r.trafficSpans(r.length).isEmpty())
    }
}
