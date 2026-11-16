package com.eunho.tagohub

import com.eunho.tagohub.core.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 다른 앱에 갔다 오면 다음 주기를 기다리지 않고 바로, 못 받으면 2·4·6초 뒤 다시 */
class AppStatePauseTest {
    @Test fun retryStepsThenNormal() {
        assertEquals(15_000L, AppState.retryMs(0, 15_000))
        assertEquals(listOf(2_000L, 4_000L, 6_000L), (1..3).map { AppState.retryMs(it, 15_000) })
        assertEquals(15_000L, AppState.retryMs(4, 15_000))   // 세 번 넘게 실패하면 보통 주기
    }

    @Test fun pauseEndsWhenAppComesBack() = runBlocking {
        AppState.foreground.value = true
        val t0 = System.currentTimeMillis()
        launch {
            delay(100); AppState.foreground.value = false   // 다른 앱으로
            delay(100); AppState.foreground.value = true    // 돌아옴
        }
        AppState.pause(10_000)
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun pauseWaitsFullTimeWhileShown() = runBlocking {
        AppState.foreground.value = true
        val t0 = System.currentTimeMillis()
        AppState.pause(300)
        assertTrue(System.currentTimeMillis() - t0 >= 290)
    }
}
