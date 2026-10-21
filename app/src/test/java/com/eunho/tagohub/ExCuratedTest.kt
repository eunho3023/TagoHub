package com.eunho.tagohub

import com.eunho.tagohub.data.ExCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExCuratedTest {
    private fun catalog() = ExCatalog.parse(
        listOf("src/main/assets/ex_catalog.json", "app/src/main/assets/ex_catalog.json").map { File(it) }.first { it.exists() }.readText(Charsets.UTF_8),
    )

    @Test
    fun curatedIdsAllExistInTheCatalog() {
        val ids = catalog().map { it.id }.toSet()
        val missing = ExCatalog.CURATED_IDS.filterNot { it in ids }
        assertTrue("카탈로그에 없는 번호: $missing", missing.isEmpty())
    }

    @Test
    fun curatedListHidesIrrelevantStatistics() {
        val shown = catalog().filter { it.id in ExCatalog.CURATED_IDS }
        assertEquals(ExCatalog.CURATED_IDS.size, shown.size)
        // 이동·운전에 쓸모 있는 것은 있고, 이동과 무관한 통계는 없어야 함
        val names = shown.map { it.name }
        assertTrue(names.any { "통행요금" in it })
        assertTrue(names.any { "정체" in it })
        assertTrue(names.none { "전자조달" in it || "지적 재산권" in it || "개별공시지가" in it || "식중독" in it || "불쾌지수" in it })
    }
}
