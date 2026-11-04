package com.eunho.tagohub.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpTextTest {
    private val seen = ArrayList<String>()
    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = "<ok/>") = HttpClient(MockEngine { req ->
        seen += req.url.toString()
        respond(body, status)
    })

    @Test fun buildsTagoUrlAndSkipsNulls() = runTest {
        val api = TagoApi { path, q -> client().getText("https://apis.data.go.kr/1613000/", path, q) }
        assertEquals("<ok/>", api.busStops("25", "전주 역", null, 1, 10))
        assertEquals(
            "https://apis.data.go.kr/1613000/BusSttnInfoInqireService/getSttnNoList?cityCode=25&nodeNm=%EC%A0%84%EC%A3%BC%20%EC%97%AD&pageNo=1&numOfRows=10",
            seen.single(),
        )
    }

    @Test fun proxyBaseKeepsItsPath() = runTest {
        client().getText("https://p.example.workers.dev/dg/1613000/", "ArvlInfoInqireService/getX", listOf("a" to "1"))
        assertEquals("https://p.example.workers.dev/dg/1613000/ArvlInfoInqireService/getX?a=1", seen.single())
    }

    @Test fun nonSuccessKeepsBody() = runTest {
        val e = assertFailsWith<HttpStatusException> { client(HttpStatusCode.Forbidden, "<err/>").getText("https://x.example/") }
        assertEquals(403, e.code)
        assertEquals("<err/>", e.body)
    }
}
