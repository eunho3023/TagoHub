package com.eunho.tagohub

import com.eunho.tagohub.core.AppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱이 안 보일 때 주기적으로 받는 반복문이 멈추고, 보이면 바로 이어지는지 */
class AppStateTest {
    @After
    fun reset() {
        AppState.foreground.value = true
        AppState.mapVisible.value = false
    }

    @Test
    fun awaitForegroundReturnsImmediatelyWhenVisibleAndWaitsWhenHidden() = runBlocking {
        AppState.foreground.value = true
        withTimeout(1000) { AppState.awaitForeground() }

        AppState.foreground.value = false
        assertNull(withTimeoutOrNull(100) { AppState.awaitForeground() })      // 내려가 있으면 기다림

        val resumed = CompletableDeferred<Boolean>()
        val waiter = async { AppState.awaitForeground(); resumed.complete(true) }
        delay(50)
        assertTrue(!resumed.isCompleted)
        AppState.foreground.value = true                                        // 다시 보이면 바로 이어짐
        withTimeout(1000) { waiter.await() }
        assertEquals(true, resumed.await())
    }

    @Test
    fun mapLoopsNeedBothTheAppAndTheMapScreenToBeVisible() = runBlocking {
        AppState.foreground.value = true
        AppState.mapVisible.value = false                                       // 홈이 지도를 덮고 있음
        assertNull(withTimeoutOrNull(100) { AppState.awaitMapVisible() })

        AppState.mapVisible.value = true
        withTimeout(1000) { AppState.awaitMapVisible() }

        AppState.foreground.value = false                                       // 지도가 맨 앞이어도 앱이 내려가면 멈춤
        assertNull(withTimeoutOrNull(100) { AppState.awaitMapVisible() })
    }
}
