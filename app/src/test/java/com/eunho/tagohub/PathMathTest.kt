package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.PathMath
import com.eunho.tagohub.data.MapErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathMathTest {
    private val east = listOf(LatLng(35.80, 127.10), LatLng(35.80, 127.11), LatLng(35.80, 127.12))

    @Test
    fun offset_rightOfTravel_byMeters() {
        val o = PathMath.offsetMeters(east, 12.0)            // 동쪽으로 가면 오른쪽 = 남쪽
        assertTrue(o.all { it.lat < 35.80 })
        o.zip(east).forEach { (a, b) -> assertEquals(12.0, Geo.meters(a.lat, a.lng, b.lat, b.lng), 0.3) }
        val back = PathMath.offsetMeters(east.reversed(), 12.0) // 반대 방향은 북쪽 → 두 방향이 서로 반대편
        assertTrue(back.all { it.lat > 35.80 })
    }

    @Test
    fun vehicle_glidesThenFollowsPath() {
        val from = LatLng(35.799, 127.10)
        val mid = PathMath.vehicleAt(east, 0.0, 1.0, 10_000, from, 600, 300)    // 미끄러지는 중: 시작점과 직전 자리 사이
        assertTrue(mid.lat > 35.799 && mid.lat < 35.80)
        val half = PathMath.vehicleAt(east, 0.0, 1.0, 10_000, from, 600, 600 + 5_000)
        assertEquals(127.11, half.lng, 1e-4)                                   // 절반 = 가운데
        val end = PathMath.vehicleAt(east, 0.0, 1.0, 10_000, null, 0, 99_000)
        assertEquals(127.12, end.lng, 1e-9)                                     // 끝에서 멈춤
        assertEquals(35.80, PathMath.vehicleAt(listOf(LatLng(35.80, 127.1)), 0.0, 0.0, 0, null, 0, 0).lat, 1e-9)
    }

    @Test
    fun metersPerPixel() {
        val mpp = PathMath.metersPerPixel(LatLng(35.80, 127.10), LatLng(35.80, 127.11), 1000)
        assertTrue(mpp in 0.85..0.95) // 약 905m / 1000px
    }

    /** 카카오 개발자 포럼에 올라온 실제 로그 문구 */
    @Test
    fun kakaoAuthErrorCode() {
        assertEquals(401, MapErrors.kakaoCode("com.kakao.vectormap.MapAuthException: MapAuthException(401): Unauthorized"))
        assertEquals(-2, MapErrors.kakaoCode("MapAuthException(-2): connect fail"))
        assertEquals(null, MapErrors.kakaoCode("RenderViewException: no gl"))
        assertTrue(MapErrors.kakaoNeedsRegistration(401) && !MapErrors.kakaoNeedsRegistration(-2))
    }
}
