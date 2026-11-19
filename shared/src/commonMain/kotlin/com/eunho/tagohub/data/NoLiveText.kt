package com.eunho.tagohub.data

/**
 * 실시간 도착이 없는 노선 줄에 "정보없음" 대신 보여 줄 글 (첫차·막차·배차 = 기점 출발 시각 기준). 웹 busdata.js noLive 와 같은 규칙.
 *  - 운행 시간 밖: "운행종료" / "첫차 06:10 · 8시간 20분 후" (막차 뒤면 "내일 첫차")
 *  - 운행 중, 배차를 알면: "다음 출발 14:30" / "25분 후 · 기점"
 *  - 운행 중, 배차를 모르면: "막차 20:10" / "1시간 5분 후 · 기점" (첫차 전이면 "첫차 …")
 *  - 막차가 떠난 뒤 90분 안(아직 달리는 중일 수 있음): "막차 출발함" / "내일 첫차 06:10 · …"
 * 첫차·막차를 모르면 null (그때만 "정보없음").
 * 운행 시간은 [com.eunho.tagohub.ui.bus] outsideService 와 같게: 첫차 30분 전부터 막차 출발 뒤 90분까지.
 */
object NoLiveText {
    data class Text(val main: String, val sub: String?, val ended: Boolean)

    private fun min(t: String?): Int? = t?.let { Regex("(\\d{1,2}):(\\d{2})").find(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    private fun clock(m: Int): String { val x = ((m % 1440) + 1440) % 1440; return "${(x / 60).toString().padStart(2, '0')}:${(x % 60).toString().padStart(2, '0')}" }

    /** 분 → "곧" / "25분" / "1시간" / "8시간 20분" */
    fun duration(m: Int): String = when {
        m <= 0 -> "곧"
        m < 60 -> "${m}분"
        m % 60 == 0 -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }

    private fun after(m: Int) = if (m <= 0) "곧" else "${duration(m)} 후"

    fun describe(first: String?, last: String?, interval: Int?, nowMin: Int): Text? {
        val f = min(first) ?: return null
        var l = min(last) ?: return null
        var n = nowMin
        if (l < f) { l += 1440; if (n < f) n += 1440 }   // 자정 넘어 막차
        val fc = clock(f)
        if (n < f - 30) return Text("운행종료", "첫차 $fc · ${after(f - n)}", true)
        if (n > l + 90) return Text("운행종료", "내일 첫차 $fc · ${after(f + 1440 - n)}", true)
        if (n > l) return Text("막차 출발함", "내일 첫차 $fc · ${after(f + 1440 - n)}", false)
        if (n < f) return Text("첫차 $fc", "${after(f - n)} · 기점", false)
        val gap = interval?.takeIf { it > 0 }
        if (gap != null) {
            val k = (n - f + gap - 1) / gap
            val t = f + k * gap
            if (t <= l) return Text("다음 출발 약 ${clock(t)}", "${after(t - n)} · 기점", false)
        }
        return Text("막차 ${clock(l)}", "${after(l - n)} · 기점", false)
    }
}
