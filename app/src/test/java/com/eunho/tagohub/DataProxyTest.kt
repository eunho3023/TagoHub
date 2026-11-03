package com.eunho.tagohub

import com.eunho.tagohub.data.DataProxy
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
}
