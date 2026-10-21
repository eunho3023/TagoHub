package com.eunho.tagohub

import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavEngine
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.NavState
import com.eunho.tagohub.guidance.GuidanceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class GuidanceTextTest {
    private val turn = DriveGuide("양재역", "서초IC 방면으로 우회전", 940, 2, 37.48, 127.03)
    private val dest = DriveGuide("목적지", "목적지", 29, DriveGuide.TYPE_DESTINATION, 36.35, 127.38)

    private fun state(guide: DriveGuide?, dist: Double, remainingM: Double = 42_000.0, sec: Int = 3300, off: Boolean = false, arrived: Boolean = false) =
        NavState(0.0, off, arrived, guide, dist, remainingM, sec, emptyList())

    @Test
    fun titleShowsTheNextTurnWithDistance() {
        assertEquals("300m 앞 · 서초IC 방면으로 우회전", GuidanceText.title(state(turn, 300.0)))
        assertEquals("1.2km 앞 · 서초IC 방면으로 우회전", GuidanceText.title(state(turn, 1200.0)))
        assertEquals("500m 앞 목적지", GuidanceText.title(state(dest, 500.0)))
        assertEquals("경로 안내 중", GuidanceText.title(state(null, 0.0)))
    }

    @Test
    fun titleReflectsOffRouteAndArrival() {
        assertEquals("경로를 다시 찾는 중", GuidanceText.title(state(turn, 300.0, off = true)))
        assertEquals("목적지에 도착했습니다", GuidanceText.title(state(dest, 10.0, arrived = true)))
    }

    @Test
    fun detailShowsRemainingDistanceMinutesAndArrivalClock() {
        val tz = TimeZone.getTimeZone("Asia/Seoul")
        // 2026-10-22 09:00 KST + 55분 = 09:55
        val now = java.time.ZonedDateTime.of(2026, 10, 22, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        assertEquals("남은 42.0km · 약 55분 · 도착 09:55", GuidanceText.detail(state(turn, 300.0), now, tz))
        // 61초는 올림해서 2분
        assertEquals("남은 100m · 약 2분 · 도착 09:01", GuidanceText.detail(state(turn, 50.0, remainingM = 100.0, sec = 61), now, tz))
    }

    @Test
    fun etaLabelIsKoreanAmPmAndDurationIsReadable() {
        val tz = TimeZone.getTimeZone("Asia/Seoul")
        val now = java.time.ZonedDateTime.of(2026, 10, 22, 23, 50, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        // 23:50 + 47분 = 다음 날 00:37
        assertEquals("오전 12:37", GuidanceText.etaLabel(now, 47 * 60, tz))
        assertEquals("오후 11:50", GuidanceText.etaLabel(now, 0, tz))
        assertEquals("0분", GuidanceText.duration(0))
        assertEquals("1분", GuidanceText.duration(1))
        assertEquals("55분", GuidanceText.duration(3300))
        assertEquals("2시간", GuidanceText.duration(7200))
        assertEquals("2시간 10분", GuidanceText.duration(7800))
        assertEquals("0분", GuidanceText.duration(-5))
    }

    @Test
    fun titleReadsClockPhrasesAsWords() {
        val clock = DriveGuide("", "11시 방향", 100, 29, 37.0, 127.0)
        assertEquals("200m 앞 · 왼쪽 방향", GuidanceText.title(state(clock, 200.0)))
    }

    /** 가상 주행하며 알림·배너 문구를 매번 만들어 봄: 비어 있거나 이상한 값이 없어야 함 */
    @Test
    fun realDriveNeverProducesBlankOrBrokenText() {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        val route = NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
        val engine = NavEngine(route)
        var d = 0.0
        var shown = 0
        while (d <= route.length) {
            val seg = route.segmentAt(d)
            val a = route.points[seg]
            val b = route.points[seg + 1]
            val len = route.cum[seg + 1] - route.cum[seg]
            val t = if (len > 0) (d - route.cum[seg]) / len else 0.0
            val st = engine.update(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t, 27.8)
            val title = GuidanceText.title(st)
            val detail = GuidanceText.detail(st, 0L, TimeZone.getTimeZone("UTC"))
            assertTrue("제목이 비어 있음 @${d.toInt()}m", title.isNotBlank())
            assertTrue("NaN·음수 값: $detail", !detail.contains("NaN") && !detail.contains("-"))
            shown++
            d += 500.0
        }
        assertTrue(shown > 100)
    }
}
