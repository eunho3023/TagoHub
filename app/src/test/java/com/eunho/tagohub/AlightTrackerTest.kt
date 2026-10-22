package com.eunho.tagohub

import com.eunho.tagohub.data.AlightStage
import com.eunho.tagohub.data.AlightTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlightTrackerTest {
    private val tLat = 35.8200
    private val tLng = 127.1400

    /** 목적지에서 정북쪽으로 [metersNorth] 미터 떨어진 지점 (음수면 남쪽) */
    private fun at(metersNorth: Double): Pair<Double, Double> = (tLat + metersNorth / 110_540.0) to tLng

    private fun ride(t: AlightTracker, from: Double, to: Double, step: Double, acc: Double = 5.0) = buildList {
        var m = from
        while (if (from > to) m >= to else m <= to) {
            val (la, ln) = at(m)
            add(t.update(la, ln, acc))
            m += if (from > to) -step else step
        }
    }

    @Test
    fun busRideAnnouncesEachStageOnceInOrder() {
        val t = AlightTracker("전북대앞", tLat, tLng)
        val updates = ride(t, 3000.0, 0.0, 20.0)
        val spoken = updates.mapNotNull { it.speak }
        assertEquals(spoken.toString(), 3, spoken.size)
        assertTrue(spoken[0], "전북대앞 정류장까지" in spoken[0] && "남았습니다" in spoken[0])
        assertTrue(spoken[1], "곧 전북대앞 정류장입니다" in spoken[1])
        assertTrue(spoken[2], "전북대앞 정류장입니다. 내리세요" in spoken[2])
        // 먼 곳에서는 아무 말도 하지 않음, 곧·내리세요는 진동도
        assertTrue(updates.takeWhile { it.distanceM > 800 }.all { it.speak == null && it.stage == AlightStage.Watching })
        assertFalse(updates.first { it.speak == spoken[0] }.vibrate)
        assertTrue(updates.first { it.speak == spoken[1] }.vibrate)
        assertTrue(updates.first { it.speak == spoken[2] }.vibrate)
        assertTrue(t.arrivedAnnounced)
    }

    @Test
    fun thresholdsAreWhereTheyShouldBe() {
        val t = AlightTracker("가", tLat, tLng)
        val (a, b) = at(790.0)
        assertEquals(AlightStage.Heads, t.update(a, b, 5.0).stage)
        val (c, d) = at(340.0)
        assertEquals(AlightStage.Soon, t.update(c, d, 5.0).stage)
        val (e, f) = at(80.0)
        assertEquals(AlightStage.Now, t.update(e, f, 5.0).stage)
    }

    @Test
    fun firstFixAlreadyCloseSpeaksOnlyTheMostUrgentStage() {
        val t = AlightTracker("나", tLat, tLng)
        val (la, ln) = at(60.0)
        val u = t.update(la, ln, 5.0)
        assertEquals(AlightStage.Now, u.stage)
        assertTrue(u.speak!!.contains("내리세요"))
        val (la2, ln2) = at(40.0)
        assertNull(t.update(la2, ln2, 5.0).speak) // 이미 알렸으니 다시 말하지 않음
    }

    @Test
    fun poorAccuracyFixesAreIgnored() {
        val t = AlightTracker("다", tLat, tLng)
        val (la, ln) = at(50.0)
        val u = t.update(la, ln, accuracyM = 200.0) // 오차가 너무 큼
        assertNull(u.speak)
        assertEquals(AlightStage.Watching, u.stage)
        assertEquals(AlightStage.Now, t.update(la, ln, accuracyM = 20.0).stage)
    }

    @Test
    fun drivingPastTheStopWarnsOnceAndFinishes() {
        val t = AlightTracker("라", tLat, tLng)
        val updates = ride(t, 400.0, -1500.0, 25.0) // 정류장을 서지 않고 지나침
        val passed = updates.filter { it.stage == AlightStage.Passed }
        assertTrue(passed.isNotEmpty())
        assertEquals(1, updates.count { it.speak?.contains("지나쳤습니다") == true })
        assertTrue(updates.last().finished)
        assertTrue(updates.first { it.speak?.contains("지나쳤습니다") == true }.vibrate)
        // 지나친 뒤에는 단계가 다시 내려가지 않음
        assertTrue(updates.dropWhile { it.stage != AlightStage.Passed }.all { it.stage == AlightStage.Passed })
    }

    @Test
    fun gettingOffAndWalkingAwayDoesNotWarnBeforeTheServiceStops() {
        val t = AlightTracker("마", tLat, tLng)
        ride(t, 300.0, 20.0, 10.0)
        // 내려서 사람 걸음(1.4m/s)으로 2분 걸으면 약 170m: 지나쳤다고 하지 않아야 함
        val walked = (1..120).map { s -> val (la, ln) = at(20.0 + 1.4 * s); t.update(la, ln, 8.0) }
        assertTrue(walked.none { it.speak?.contains("지나쳤습니다") == true })
    }

    @Test
    fun subwayAnnouncesEarlierAndNamesTheStation() {
        val t = AlightTracker("전주", tLat, tLng, subway = true)
        assertEquals("전주역", t.label)
        val (a, b) = at(1900.0)
        val u = t.update(a, b, 10.0)
        assertEquals(AlightStage.Heads, u.stage)
        assertTrue(u.speak!!, "전주역까지" in u.speak!!)
        // 버스 정류장 이름 붙이기
        assertEquals("시청 정류장", AlightTracker("시청", tLat, tLng).label)
        assertEquals("시청앞정류장", AlightTracker("시청앞정류장", tLat, tLng).label)
        assertEquals("서울역", AlightTracker("서울역", tLat, tLng, subway = true).label)
    }

    @Test
    fun particlesFollowTheLastSyllable() {
        val bus = AlightTracker("시청", tLat, tLng)
        val (a, b) = at(300.0)
        bus.update(a, b, 5.0)
        val (c, d) = at(-600.0)
        val passed = bus.update(c, d, 5.0)
        assertTrue(passed.speak!!, "시청 정류장을 지나쳤습니다" in passed.speak!!)
        // 받침이 없는 이름은 "를"
        val sub = AlightTracker("서울", tLat, tLng, subway = true)
        val (e, f) = at(100.0)
        sub.update(e, f, 5.0)
        val (g, h) = at(-700.0)
        val p2 = sub.update(g, h, 5.0)
        assertTrue(p2.speak!!, "서울역을 지나쳤습니다" in p2.speak!!) // "역" 에 받침이 있어 "을"
    }
}
