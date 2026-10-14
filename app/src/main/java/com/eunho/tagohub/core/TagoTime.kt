package com.eunho.tagohub.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TAGO 시간 포맷이 서비스마다 전부 다르다 (PDF 실제 응답 기준):
 *   항공 202610091000        (12자리)
 *   고속 202610090600        (12자리)
 *   시외 20261009115000      (14자리)
 *   기차 20261009063700      (14자리)
 *   고속도착 arrPrdtTm "2026-10-09 16:59", depTm "12:10"
 *   지하철 depTime "052000" (HHmmss), 운행없음 "000000"
 *   버스노선 startvehicletime "0600" (HHmm)
 */
object TagoTime {
    private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun ymd(d: LocalDate): String = d.format(YMD)

    fun dateTime(raw: String?): LocalDateTime? {
        val d = raw?.filter(Char::isDigit) ?: return null
        if (d.length != 12 && d.length != 14) return null
        return try {
            val base = LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
            val h = d.substring(8, 10).toLong()
            val m = d.substring(10, 12).toLong()
            val s = if (d.length == 14) d.substring(12, 14).toLong() else 0L
            // "2400" 같은 24시 표기도 다음날로 자연스럽게 넘어가도록 더하기로 계산
            base.atStartOfDay().plusHours(h).plusMinutes(m).plusSeconds(s)
        } catch (e: DateTimeException) {
            null
        }
    }

    /** "12:10" / "0600" / "052000" → 자정 기준 분. 24시 이후 표기(2530)도 그대로 1530분 */
    fun minutesOfDay(raw: String?): Int? {
        val d = raw?.filter(Char::isDigit) ?: return null
        val p = when (d.length) {
            3 -> d.padStart(4, '0')
            5 -> d.padStart(6, '0')
            12, 14 -> d.substring(8) // yyyyMMddHHmm(ss) — 서울 버스 API 첫차·막차 "20261011040000"
            else -> d
        }
        if (p.length != 4 && p.length != 6) return null
        val h = p.substring(0, 2).toInt()
        val m = p.substring(2, 4).toInt()
        if (m >= 60 || h >= 30) return null
        return h * 60 + m
    }

    fun clockText(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    fun clockText(raw: String?): String? = minutesOfDay(raw)?.let(::clockText)
    fun hm(t: LocalDateTime): String = String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)

    private val hourRe = Regex("""(\d+)\s*시간""")
    private val minRe = Regex("""(\d+)\s*분""")

    /** 고속버스 rmnTm "01시간 10분" → 70, "도착완료" → null */
    fun remainMinutes(raw: String?): Int? {
        if (raw == null) return null
        val h = hourRe.find(raw)?.groupValues?.get(1)?.toInt()
        val m = minRe.find(raw)?.groupValues?.get(1)?.toInt()
        if (h == null && m == null) return null
        return (h ?: 0) * 60 + (m ?: 0)
    }

    /** 지하철 운행일 기준 분: 03시 이전은 전날 운행의 연장으로 보고 +24h */
    fun serviceMinute(minutes: Int): Int = if (minutes < 180) minutes + 1440 else minutes
}
