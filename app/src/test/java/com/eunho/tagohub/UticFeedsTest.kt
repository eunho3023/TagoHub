package com.eunho.tagohub

import com.eunho.tagohub.data.UticFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UticFeedsTest {
    @Test
    fun buildsAllRequestedUrlsWithTheConfiguredKey() {
        val key = "sample key"
        val urls = UticFeeds.all.map { it.url(key) }

        assertEquals(7, urls.size)
        assertTrue(urls[0].startsWith("http://www.utic.go.kr/etc/telMap.do?key=sample%20key"))
        assertTrue(urls[1].startsWith("http://www.utic.go.kr/guide/imsOpenData.do?key=sample%20key"))
        assertTrue(urls[2].startsWith("http://www.utic.go.kr/guide/tsdmsOpenData.do?key=sample%20key"))
        assertTrue(urls[3].startsWith("http://www.utic.go.kr/guide/cctvOpenData.do?key=sample%20key"))
        assertTrue(urls[4].startsWith("http://www.utic.go.kr/guide/getRoadAccJson.do?key=sample%20key"))
        assertEquals(
            "http://tsihub.utic.go.kr/tsi/api/PlanCrossRoadInfoService/getPlanCRRSInfo?serviceKey=sample%20key&type=json",
            urls[5],
        )
        assertTrue(urls[6].startsWith("http://www.utic.go.kr/guide/tcsOpenData.do?key=sample%20key"))
    }
}
