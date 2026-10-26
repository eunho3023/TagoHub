package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import java.io.IOException

/**
 * 공공데이터포털은 키가 거절되면 HTTP 200 에 오류 본문(OpenAPI_ServiceResponse)을 준다.
 * 이걸 "운항편 없음"으로 착각하지 않도록, 본문에서 오류를 찾아 사용자가 고칠 수 있는 말로 바꿔 던진다.
 */
object DataGoError {
    fun check(body: String, apiName: String) {
        val text = body.trim()
        if (!text.startsWith("{") && !text.startsWith("<")) return
        val reason = if (text.startsWith("{")) {
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return
            val head = (root["OpenAPI_ServiceResponse"] as? Map<*, *>)?.get("cmmMsgHeader") as? Map<*, *> ?: return
            head["errMsg"]?.toString()
        } else {
            if ("OpenAPI_ServiceResponse" !in text) return
            Regex("<errMsg>([^<]*)</errMsg>").find(text)?.groupValues?.get(1)
        } ?: return
        throw IOException(message(reason, apiName))
    }

    fun message(reason: String, apiName: String): String = when {
        "NOT_REGISTERED" in reason -> "$apiName: 인증키가 이 API에 등록되지 않았습니다. 공공데이터포털에서 이 API의 활용신청을 하고 승인된 키를 local.properties 에 넣어 주세요."
        "LIMITED_NUMBER_OF_SERVICE_REQUESTS" in reason -> "$apiName: 오늘 호출 한도를 넘었습니다. 내일 다시 시도해 주세요."
        "NO_OPENAPI_SERVICE" in reason -> "$apiName: 이 API 주소가 없어졌거나 바뀌었습니다."
        else -> "$apiName: 공공데이터포털 오류 ($reason)"
    }
}
