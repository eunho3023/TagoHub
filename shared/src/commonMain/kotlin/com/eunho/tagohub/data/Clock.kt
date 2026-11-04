package com.eunho.tagohub.data

/** 시각 글자 다루기 (폰·웹·PC 공용) */
object Clock {
    /** "05:30"·"0530"·"25:10" → 하루 중 분 (자정 넘긴 운행은 30시까지). 못 읽으면 null */
    fun parse(t: String?): Int? {
        val m = Regex("""(\d{1,2}):?(\d{2})""").find(t ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        return if (h < 30 && mm < 60) h * 60 + mm else null
    }
}
