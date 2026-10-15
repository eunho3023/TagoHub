package com.eunho.tagohub

import com.eunho.tagohub.data.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    @Test
    fun parse_andReportUrl() {
        val n = Notices.parse("""[{"date":"2026-10-01","title":"옛 공지","body":"a"},{"date":"2026-10-15","title":"새 공지","body":"b"},{"date":"x"}]""")
        assertEquals(listOf("새 공지", "옛 공지"), n.map { it.title })          // 최신순, 제목 없는 건 뺌
        val repo = java.io.File("../notices.json").takeIf { it.exists() } ?: java.io.File("notices.json")
        if (repo.exists()) assertTrue(Notices.parse(repo.readText()).isNotEmpty())   // 저장소의 notices.json 형식
        val u = Notices.reportUrl("오류", "정류장이 안 떠요\n전주")
        assertTrue(u.startsWith("https://github.com/eunho3023/TagoHub/issues/new?title=") && u.contains("%EC%98%A4%EB%A5%98") && !u.contains("+"))
    }
}
