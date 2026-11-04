package com.eunho.tagohub

import com.eunho.tagohub.data.DataProxy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataProxyTest {
    @Test
    fun onlyWhenUrlAndTokenAreSet() {
        assertNull(DataProxy.of("", "t"))
        assertNull(DataProxy.of("https://p.example.workers.dev", " "))
        assertNull(DataProxy.of("http://p.example.workers.dev", "t"))   // https 만
        assertEquals("https://p.example.workers.dev/dg/1613000/", DataProxy.of(" https://p.example.workers.dev/ ", "t")!!.base("1613000"))
    }

    private val p = DataProxy("https://p.example.workers.dev", "t")

    @Test
    fun rewritesKeyedHostsToServer() {
        assertEquals(
            "https://p.example.workers.dev/h/https/apis.data.go.kr/1613000/ArvlInfoInqireService/getX?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&cityCode=25",
            p.rewrite("https://apis.data.go.kr/1613000/ArvlInfoInqireService/getX?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&cityCode=25".toHttpUrl()).toString(),
        )
        // 서울 지하철은 키가 경로에, http
        assertEquals(
            "https://p.example.workers.dev/h/http/swopenapi.seoul.go.kr/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x/0/5/%EA%B0%95%EB%82%A8",
            p.rewrite("http://swopenapi.seoul.go.kr/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x/0/5/%EA%B0%95%EB%82%A8".toHttpUrl()).toString(),
        )
        assertEquals("https://p.example.workers.dev/h/https/dapi.kakao.com/v2/local/search/keyword.json?query=a%20b",
            p.rewrite("https://dapi.kakao.com/v2/local/search/keyword.json?query=a%20b".toHttpUrl()).toString())
    }

    @Test
    fun leavesOtherHostsAndPortsAlone() {
        assertNull(p.rewrite("https://openapi.its.go.kr:9443/cctvInfo?apiKey=x".toHttpUrl()))
        assertNull(p.rewrite("https://router.project-osrm.org/route/v1".toHttpUrl()))
        assertNull(p.rewrite("https://p.example.workers.dev/h/https/apis.data.go.kr/x".toHttpUrl()))
        assertNull(p.rewrite("https://apis.data.go.kr:8443/x".toHttpUrl()))
    }
}
