package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoDiag
import com.eunho.tagohub.data.KakaoDiag.Probe
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoDiagTest {
    private val pkg = "com.eunho.tagohub"
    private val hash = "QS7W0zaF5YNWWJ3r+wg1Gw7twCY="
    private val disabled = Probe(403, """{"errorType":"NotAuthorizedError","message":"App(TagoHub) disabled OPEN_MAP_AND_LOCAL service."}""")
    private val wrongType = Probe(401, """{"errorType":"AccessDeniedError","message":"wrong appKey type"}""")

    @Test
    fun mapKeyIsActuallyRestKey() {
        val d = KakaoDiag.diagnose(pkg, hash, Probe(200, "{}"), null)
        assertTrue(d.contains("네이티브 앱 키가 아니라 REST API 키"))
        val d2 = KakaoDiag.diagnose(pkg, hash, disabled, null)
        assertTrue(d2.contains("REST API 키") && d2.contains("'TagoHub'") && d2.contains("사용 설정"))
    }

    @Test
    fun mapServiceDisabled_showsAppName() {
        val d = KakaoDiag.diagnose(pkg, hash, wrongType, disabled)
        assertTrue(d.contains("'TagoHub'") && d.contains("사용 설정이 꺼져") && d.contains("disabled OPEN_MAP_AND_LOCAL"))
    }

    @Test
    fun restOk_pointsToPlatformInfo() {
        val d = KakaoDiag.diagnose(pkg, hash, wrongType, Probe(200, "{}"))
        assertTrue(d.contains("켜져 있습니다") && d.contains(pkg) && d.contains(hash))
    }

    @Test
    fun noRestKey_and_offline() {
        assertTrue(KakaoDiag.diagnose(pkg, hash, wrongType, null).contains("KAKAO_REST_KEY"))
        assertTrue(KakaoDiag.diagnose(pkg, hash, Probe(null, null), null).contains("인터넷"))
    }
}
