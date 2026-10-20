package com.eunho.tagohub

import com.eunho.tagohub.data.CctvProxy
import org.junit.Assert.assertEquals
import org.junit.Test

class CctvProxyTest {
    private val url = "http://1.2.3.4:8080/live/cctv01.m3u8?a=1&b=2"

    @Test
    fun withoutProxyKeepsOriginalUrl() {
        assertEquals(url, CctvProxy.wrap(url, "", ""))
        assertEquals(url, CctvProxy.wrap(url, "https://cctv.example.com", ""))
        assertEquals(url, CctvProxy.wrap(url, "", "secretsecret12"))
    }

    @Test
    fun withProxyEncodesTargetAndKey() {
        val wrapped = CctvProxy.wrap(url, "https://cctv.example.com/", "secret key+12")
        assertEquals(
            "https://cctv.example.com/p?u=http%3A%2F%2F1.2.3.4%3A8080%2Flive%2Fcctv01.m3u8%3Fa%3D1%26b%3D2&k=secret+key%2B12",
            wrapped,
        )
    }
}
