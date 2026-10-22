package com.eunho.tagohub

import com.eunho.tagohub.reminder.CommuteReminder
import com.eunho.tagohub.reminder.LastBusReminder
import com.eunho.tagohub.reminder.ReminderCodec
import com.eunho.tagohub.reminder.ReminderLogic
import com.eunho.tagohub.reminder.ReminderLogic.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderLogicTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    // 2026-10-22 는 목요일
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, seoul)

    @Test
    fun nextFireIsTheNextTimeAfterNow() {
        assertEquals(at(22, 22, 30), ReminderLogic.nextFire(at(22, 20), 22 * 60 + 30))
        // 오늘 시각이 이미 지났으면 내일
        assertEquals(at(23, 22, 30), ReminderLogic.nextFire(at(22, 23), 22 * 60 + 30))
    }

    @Test
    fun pastMidnightLastBusFiresInTheEarlyMorningOfTheNextDay() {
        // 막차 25:10(다음 날 01:10) 의 30분 전 = 24:40 → 다음 날 00:40
        val fire = ReminderLogic.lastBusFireMinutes(25 * 60 + 10, 30)
        assertEquals(24 * 60 + 40, fire)
        assertEquals(at(23, 0, 40), ReminderLogic.nextFire(at(22, 20), fire))
        // 지금이 새벽 00:20 이면 어제 운행일의 알림(오늘 00:40)이 아직 남음
        assertEquals(at(23, 0, 40), ReminderLogic.nextFire(at(23, 0, 20), fire))
    }

    @Test
    fun weekdaysOnlySkipsTheWeekend() {
        // 금요일(23일) 18시에 맞춘 알림을 금요일 19시에 구하면 월요일(26일)
        assertEquals(at(26, 18), ReminderLogic.nextFire(at(23, 19), 18 * 60, weekdaysOnly = true))
        // 토요일에 구해도 월요일
        assertEquals(at(26, 8), ReminderLogic.nextFire(at(24, 9), 8 * 60, weekdaysOnly = true))
        // 평일 제한이 없으면 토요일도 울림
        assertEquals(at(24, 8), ReminderLogic.nextFire(at(23, 9), 8 * 60, weekdaysOnly = false))
    }

    @Test
    fun commuteDecisionWaitsThenNotifiesThenWarnsWhenLate() {
        // 09:00 도착, 소요 50분 → 나갈 시각 = 540-50-5 = 485 (08:05)
        val arrive = 9 * 60
        // 06:30 (390): 95분 남음 → 20분 뒤에 다시 확인
        assertEquals(Decision.CheckAt(390 + 20), ReminderLogic.decide(390, arrive, 50))
        // 07:55 (475): 10분 남음 → 나갈 시각 -2분(483)에 확인
        assertEquals(Decision.CheckAt(483), ReminderLogic.decide(475, arrive, 50))
        // 08:01 (481): 4분 남음 → 알림
        assertEquals(Decision.Notify(4), ReminderLogic.decide(481, arrive, 50))
        // 08:05 (485): 지금
        assertEquals(Decision.Notify(0), ReminderLogic.decide(485, arrive, 50))
        // 08:15 (495): 10분 지났지만 아직 알림 (15분 이내)
        assertEquals(Decision.Notify(0), ReminderLogic.decide(495, arrive, 50))
        // 08:30 (510): 25분 지남 → 늦음
        assertEquals(Decision.Late(25), ReminderLogic.decide(510, arrive, 50))
    }

    @Test
    fun checksNeverScheduleBackwardsInTime() {
        for (now in 0 until 600) {
            val d = ReminderLogic.decide(now, 600, 40)
            if (d is Decision.CheckAt) assertTrue("now=$now next=${d.minuteOfDay}", d.minuteOfDay > now)
        }
    }

    @Test
    fun firstCheckIsTwoAndAHalfHoursBeforeButNotBeforeMidnight() {
        assertEquals(9 * 60 - 150, ReminderLogic.firstCheckMinutes(9 * 60))
        assertEquals(0, ReminderLogic.firstCheckMinutes(60))
    }

    @Test
    fun textsAreReadable() {
        assertEquals("지금 나가야 해요. 소요 약 50분, 09:00까지 도착", ReminderLogic.departText(0, 50, 540))
        assertEquals("4분 뒤에 나가세요. 소요 약 50분, 09:00까지 도착", ReminderLogic.departText(4, 50, 540))
        assertTrue(ReminderLogic.lastBusText("123", 30, 23 * 60 + 40).contains("23:40"))
        assertEquals("01:10", ReminderLogic.clock(25 * 60 + 10))
    }

    @Test
    fun storeCodecRoundTripsAndRejectsBrokenLines() {
        val list = listOf(
            LastBusReminder("R|1", "123", "25", "기점 | 이름", 25 * 60 + 10, 30),
            LastBusReminder("R2", "5-1", "11", "서울역", 23 * 60, 15),
        )
        assertEquals(list, ReminderCodec.decodeLastBus(ReminderCodec.encodeLastBus(list)))
        assertEquals(emptyList<LastBusReminder>(), ReminderCodec.decodeLastBus("깨진|줄\n\n a|b|c|d|x|30"))
        val c = CommuteReminder(true, 9 * 60)
        assertEquals(c, ReminderCodec.decodeCommute(ReminderCodec.encodeCommute(c)))
        assertEquals(CommuteReminder(false, 480), ReminderCodec.decodeCommute("0|480"))
        assertNull(ReminderCodec.decodeCommute("1|5000"))
        assertNull(ReminderCodec.decodeCommute(""))
        assertNull(ReminderCodec.decodeCommute(null))
    }
}
