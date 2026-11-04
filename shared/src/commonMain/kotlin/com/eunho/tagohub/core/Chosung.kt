package com.eunho.tagohub.core

/**
 * 초성 검색: "ㅅㅇㅇ" 로 "서울역", "ㅅ울" 처럼 초성과 글자를 섞어서도 찾는다.
 * 검색어의 글자마다 (초성 자모면 그 글자의 초성만, 아니면 글자 그대로) 비교해 연속으로 들어 있으면 일치.
 */
object Chosung {
    private const val HANGUL_START = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** 완성형 한글이면 그 글자의 초성, 아니면 null */
    private fun initialOf(c: Char): Char? =
        if (c.code in HANGUL_START..HANGUL_END) INITIALS[(c.code - HANGUL_START) / (21 * 28)] else null

    /** 검색어에 초성 자모가 하나라도 들어 있는지 (있어야 초성 비교가 필요함) */
    fun hasInitials(query: String): Boolean = query.any { it in INITIALS }

    /** [query] 가 [text] 안에 (초성 비교 포함) 연속으로 들어 있는지. 공백은 무시 */
    fun contains(text: String, query: String): Boolean = indexOf(text, query) >= 0

    /** [query] 가 처음 들어 있는 위치 (없으면 -1). 앞쪽 일치를 먼저 보이게 정렬할 때 씀 */
    fun indexOf(text: String, query: String): Int {
        val t = text.replace(" ", "")
        val q = query.replace(" ", "")
        if (q.isEmpty()) return -1
        if (!hasInitials(q)) return t.indexOf(q)
        if (q.length > t.length) return -1
        for (start in 0..t.length - q.length) {
            var ok = true
            for (i in q.indices) {
                val qc = q[i]
                val tc = t[start + i]
                val same = if (qc in INITIALS) initialOf(tc) == qc || tc == qc else tc == qc
                if (!same) { ok = false; break }
            }
            if (ok) return start
        }
        return -1
    }
}
