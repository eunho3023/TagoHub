package com.eunho.tagohub.data

/**
 * TAGO 가 바꾼 정류장·노선 ID 맞추기. 앱 안 정류장 파일(국토교통부 전국 정류장 2025-10)은 예전 ID 를 쓴다.
 * 인천: 정류장 ICB1…→ICB3…, ICB2…→ICB4…, 노선 ICB1…→ICB3… (뒤 숫자는 그대로).
 * 예전 ID 로 물으면 TAGO 가 경유노선·도착정보를 하나도 주지 않아서, 파일에서 꺼낸 ID 는 [current] 로 바꿔 쓰고
 * 파일을 찾을 때는 [legacy] 로 되돌린다. (2026-11 확인: 표본 459곳 중 425곳이 같은 이름으로 맞고 나머지는 이름 표기만 다름)
 */
object TagoIds {
    fun current(id: String): String {
        if (id.length < 5 || !id.startsWith("ICB")) return id
        return when (id[3]) {
            '1' -> "ICB3" + id.substring(4)
            '2' -> "ICB4" + id.substring(4)
            else -> id
        }
    }

    fun legacy(id: String): String {
        if (id.length < 5 || !id.startsWith("ICB")) return id
        return when (id[3]) {
            '3' -> "ICB1" + id.substring(4)
            '4' -> "ICB2" + id.substring(4)
            else -> id
        }
    }
}
