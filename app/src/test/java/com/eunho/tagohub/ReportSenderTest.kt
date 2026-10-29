package com.eunho.tagohub

import com.eunho.tagohub.data.ReportSender
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportSenderTest {
    @Test
    fun everyReportKindIsAllowedByTheFirestoreRules() {
        val rules = File("../firestore.rules").takeIf { it.exists() } ?: File("firestore.rules")
        val text = rules.readText()
        ReportSender.KINDS.forEach { assertTrue("규칙에 '$it' 가 없음", "'$it'" in text) }
        assertTrue("규칙의 글자 수 한도와 같아야 함", "<= ${ReportSender.MAX_CHARS}" in text)
    }
}
