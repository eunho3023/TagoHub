package com.eunho.tagohub

import com.eunho.tagohub.data.KlidBusJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KlidBusRepositoryTest {
    @Test
    fun parsesKlidPageWithRowsAndKoreanText() {
        val json = """
            {
              "header":{"resultCode":"K0","resultMsg":"NORMAL_SERVICE"},
              "body":{"totalCount":1,"pageNo":1,"numOfRows":1000,
                "items":{"item":[
                  {"stdgCd":"4686000000","lclgvNm":"전라남도 함평군","rteId":"R1"}
                ]}
              }
            }
        """.trimIndent()

        val page = KlidBusJson.parsePage(json, "rteId")

        assertEquals(1, page.totalCount)
        assertEquals("4686000000", page.rows.single().text("stdgCd"))
        assertEquals("전라남도 함평군", page.rows.single().text("lclgvNm"))
    }

    @Test
    fun rejectsApiErrorInsteadOfReturningEmptyData() {
        val json = """{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED"},"body":{"totalCount":0}}"""

        val error = assertThrows(java.io.IOException::class.java) {
            KlidBusJson.parsePage(json, "rteId")
        }

        assertEquals(true, error.message.orEmpty().contains("30"))
    }

    @Test
    fun explainsHttp400ServiceUnavailableResponse() {
        val body = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"NO_OPENAPI_SERVICE_ERROR","returnAuthMsg":"해당 오픈API 서비스가 없거나 폐기됨","returnReasonCode":"12"}}}"""

        val message = KlidBusJson.httpError(400, body)

        assertEquals(true, message.contains("HTTP 400"))
        assertEquals(true, message.contains("코드 12"))
        assertEquals(true, message.contains("오픈API 서비스가 없거나 폐기됨"))
    }
}
