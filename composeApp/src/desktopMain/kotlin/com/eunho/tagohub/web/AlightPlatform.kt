package com.eunho.tagohub.web

/** PC 버전은 위치를 받지 못함 (하차 알림 단추도 안 보임) */
actual fun watchLocation(onPos: (lat: Double, lng: Double, accuracyM: Double) -> Unit, onError: (String) -> Unit): Int {
    onError("PC 버전에서는 위치를 받을 수 없어요")
    return -1
}

actual fun stopLocation(id: Int) {}

actual val locationSupported: Boolean = false

actual fun announce(text: String, vibrate: Boolean) {}
