package com.eunho.tagohub.web

@JsFun(
    """(onPos, onErr) => {
  try {
    if (!navigator.geolocation) { onErr('이 브라우저는 위치를 알려 주지 않아요'); return -1; }
    return navigator.geolocation.watchPosition(
      (p) => onPos(p.coords.latitude, p.coords.longitude, p.coords.accuracy || 0),
      (e) => onErr(e && e.code === 1 ? '위치 사용을 허락해야 하차 알림을 쓸 수 있어요' : '위치를 받지 못했어요'),
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 30000 });
  } catch (e) { onErr('위치를 받지 못했어요'); return -1; }
}"""
)
private external fun jsWatch(onPos: (Double, Double, Double) -> Unit, onErr: (String) -> Unit): Int

@JsFun("(id) => { try { navigator.geolocation.clearWatch(id); } catch (e) {} }")
private external fun jsClear(id: Int)

@JsFun(
    """(text, vib) => {
  try { if (vib && navigator.vibrate) navigator.vibrate([400, 200, 400]); } catch (e) {}
  try {
    const u = new SpeechSynthesisUtterance(text); u.lang = 'ko-KR';
    window.speechSynthesis.cancel(); window.speechSynthesis.speak(u);
  } catch (e) {}
}"""
)
private external fun jsAnnounce(text: String, vibrate: Boolean)

actual fun watchLocation(onPos: (lat: Double, lng: Double, accuracyM: Double) -> Unit, onError: (String) -> Unit): Int =
    jsWatch({ a, b, c -> onPos(a, b, c) }, { onError(it) })

actual fun stopLocation(id: Int) = jsClear(id)

actual val locationSupported: Boolean = true

actual fun announce(text: String, vibrate: Boolean) = jsAnnounce(text, vibrate)
