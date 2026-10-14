package com.eunho.tagohub.data

/**
 * 카카오맵 403·401 원인 점검.
 * 지도 SDK 는 "403" 만 알려주지만, 같은 카카오 앱의 REST API(키워드 장소 검색)는 거부 이유를 문장으로 돌려준다.
 * 1) 앱에 넣은 지도 키(KAKAO_NATIVE_KEY)로 REST 호출 → 통과하거나 '카카오맵 꺼짐'이 오면 그 키는 REST API 키 (네이티브 앱 키가 아님)
 * 2) REST 키(KAKAO_REST_KEY)로 호출 → 'disabled OPEN_MAP_AND_LOCAL' 이면 카카오맵 사용 설정이 꺼짐(또는 권한 심사 대기). 응답의 App(이름) = 키가 속한 카카오 앱
 * 3) 둘 다 문제없으면 남은 원인 = 네이티브 앱 키 아래 Android 앱 정보(패키지명·키 해시)
 */
object KakaoDiag {
    /** http == null 이면 인터넷 연결 실패 */
    data class Probe(val http: Int?, val body: String?)

    private fun Probe.disabled() = body.orEmpty().contains("OPEN_MAP_AND_LOCAL", ignoreCase = true)
    private fun Probe.ok() = http == 200
    private fun Probe.appName() = Regex("App\\(([^)]*)\\)").find(body.orEmpty())?.groupValues?.get(1)

    /** 서버가 보낸 문장 그대로 (예: "HTTP 403 NotAuthorizedError: App(TagoHub) disabled OPEN_MAP_AND_LOCAL service.") */
    fun raw(p: Probe): String = when {
        p.http == null -> "인터넷 연결 실패"
        p.ok() -> "HTTP 200 정상"
        else -> "HTTP ${p.http} " + (KakaoLocal.error(p.body.orEmpty()) ?: p.body.orEmpty().take(120))
    }

    fun diagnose(packageName: String, keyHash: String?, nativeAsRest: Probe, rest: Probe?): String {
        val reg = "카카오디벨로퍼스 [앱] > [플랫폼 키] > [네이티브 앱 키] > [Android 앱 정보]에 패키지명 $packageName · 키 해시 ${keyHash ?: "(확인 실패)"}"
        if (nativeAsRest.http == null) return "점검 실패: 인터넷 연결을 확인하세요."
        // 1) 지도 키가 REST 키로 통한다 = 네이티브 앱 키가 아님
        if (nativeAsRest.ok()) {
            return "원인: 앱에 넣은 지도 키(KAKAO_NATIVE_KEY)는 네이티브 앱 키가 아니라 REST API 키입니다. " +
                "[앱 키]의 '네이티브 앱 키'를 KAKAO_NATIVE_KEY 에 넣고 다시 빌드하세요. (지금 키는 KAKAO_REST_KEY 에 넣으면 장소 검색·길찾기에 씁니다)"
        }
        if (nativeAsRest.disabled()) {
            return "원인 두 가지: ① 앱에 넣은 지도 키는 네이티브 앱 키가 아니라 REST API 키입니다 → [앱 키]의 '네이티브 앱 키'로 바꾸세요. " +
                "② 이 키의 카카오 앱 '${nativeAsRest.appName() ?: "?"}' 에 카카오맵 사용 설정이 꺼져 있습니다 → [제품 설정] > [카카오맵] 사용 설정 ON (버튼이 비활성화면 앱 권한 신청). " +
                "카카오 응답: ${raw(nativeAsRest)}"
        }
        // 지도 키는 REST 로 안 통함 = 정상(네이티브 앱 키로 보임). REST 키로 카카오맵 사용 설정 확인
        if (rest == null) {
            return "지도 키는 네이티브 앱 키로 보입니다. 카카오맵 사용 설정까지 확인하려면 local.properties 에 KAKAO_REST_KEY(같은 앱의 REST API 키)를 넣으세요. " +
                "그동안 확인할 것: ① [제품 설정] > [카카오맵] 사용 설정 ON (계정에 카카오맵 쓰는 다른 앱이 있으면 앱 권한 신청·승인 필요) ② $reg. " +
                "(지도 키로 REST 호출한 결과: ${raw(nativeAsRest)})"
        }
        if (rest.http == null) return "점검 실패: 인터넷 연결을 확인하세요."
        if (rest.disabled()) {
            return "원인: 카카오 앱 '${rest.appName() ?: "?"}' 에 카카오맵 사용 설정이 꺼져 있거나 권한 심사 대기 중입니다. " +
                "이 이름이 설정을 켠 앱과 같은지 확인하세요. [제품 설정] > [카카오맵] 사용 설정 ON (버튼이 비활성화면 앱 권한 신청 후 승인). " +
                "카카오 응답: ${raw(rest)}"
        }
        if (rest.ok()) {
            return "카카오맵 사용 설정은 켜져 있습니다(REST 정상). 남은 원인: ① 지도 키(KAKAO_NATIVE_KEY)가 REST 키와 같은 카카오 앱의 네이티브 앱 키인지 ② $reg " +
                "(디버그·릴리즈 키 해시 모두, 줄을 바꿔 여러 개 입력). 저장 후 새로고침 버튼을 누르세요."
        }
        return "REST 키 확인 실패 — 키가 맞는지 확인하세요. 카카오 응답: ${raw(rest)}"
    }
}
