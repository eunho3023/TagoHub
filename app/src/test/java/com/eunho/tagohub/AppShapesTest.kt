package com.eunho.tagohub

import com.eunho.tagohub.data.AppShapes
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Polyline
import org.junit.Assert.assertEquals
import org.junit.Test

class AppShapesTest {
    /** 앱이 올리는 폴리라인은 웹·서버(정밀도 5)와 같은 글자: 구글 예시 값 */
    @Test fun encodeMatchesGoogleExample() {
        val pts = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", AppShapes.encode(pts))
        assertEquals(pts, Polyline.decode(AppShapes.encode(pts), 5))
    }
}
