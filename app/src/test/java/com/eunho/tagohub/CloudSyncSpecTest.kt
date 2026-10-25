package com.eunho.tagohub

import com.eunho.tagohub.data.CloudSyncSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncSpecTest {
    @Test
    fun fieldNamesHaveNoDotsAndAreUnique() {
        val names = CloudSyncSpec.items.map { (f, k) -> CloudSyncSpec.field(f, k) }
        assertTrue(names.none { '.' in it })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun syncsFavoritesButNotCaches() {
        assertTrue(CloudSyncSpec.items.contains("bus" to "favorites"))
        assertFalse(CloudSyncSpec.items.any { it.second.startsWith("its_road") })
    }
}
