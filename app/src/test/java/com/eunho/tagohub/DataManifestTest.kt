package com.eunho.tagohub

import com.eunho.tagohub.data.DataManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataManifestTest {
    @Test
    fun parse_manifestFromPipeline() {
        // pipeline/build_data.py 가 쓰는 형식 그대로
        val m = DataManifest.parse("""{
          "version": "202610200300", "file": "bus_stops.db.gz",
          "sha256": "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
          "size": 25123456, "dbSize": 66000000, "complete": false, "tagoRoutes": 5321 }""")!!
        assertEquals("202610200300", m.version)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", m.sha256)   // 소문자로
        assertEquals(25123456L, m.size)
        assertEquals(false, m.complete)
        assertEquals(null, DataManifest.parse("""{"version":"1","file":"a","sha256":"짧음"}"""))       // 검증값이 이상하면 안 받음
        assertEquals(null, DataManifest.parse("<html>404</html>"))
        assertEquals("https://github.com/u/r/releases/download/data-latest/bus_stops.db.gz",
            DataManifest.fileUrl("https://github.com/u/r/releases/download/data-latest/manifest.json", "bus_stops.db.gz"))
    }

    @Test
    fun newerByDateThenTime() {
        assertTrue(DataManifest.isNewer("202610200300", "20261013c"))     // 서버가 더 늦은 날
        assertTrue(!DataManifest.isNewer("202610100300", "20261013c"))    // 앱 업데이트로 앱 안 데이터가 더 새것 → 앱 안 데이터
        assertTrue(!DataManifest.isNewer("202610130300", "20261013c"))    // 같은 날이면 앱 안 데이터(손으로 넣은 것) 우선
        assertTrue(DataManifest.isNewer("202610270300", "202610200300"))  // 서버끼리는 시각으로
        assertTrue(!DataManifest.isNewer("202610200300", "202610200300")) // 같으면 안 받음
        assertTrue(DataManifest.isNewer("202610200300", null))
        assertTrue(!DataManifest.isNewer(null, "20261013c"))
    }
}
