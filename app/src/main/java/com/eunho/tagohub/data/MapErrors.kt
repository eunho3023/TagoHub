package com.eunho.tagohub.data

/** 카카오맵 인증 오류 문구에서 코드 읽기: "MapAuthException(401): Unauthorized" → 401 */
object MapErrors {
    fun kakaoCode(text: String?): Int? {
        if (text == null) return null
        return Regex("\\((-?\\d+)\\)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

}
