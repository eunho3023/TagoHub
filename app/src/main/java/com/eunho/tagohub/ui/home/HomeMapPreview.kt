package com.eunho.tagohub.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eunho.tagohub.ui.bus.hasLocationPermission
import com.eunho.tagohub.ui.bus.lastKnownLocation
import com.eunho.tagohub.ui.common.FitText
import com.eunho.tagohub.ui.map.KakaoCanvas
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.LatLng as KLatLng

/**
 * 홈의 작은 지도: 내 위치 주변을 보여 주기만 하고(손으로 움직이지 않음), 누르면 지도 탭으로.
 * 위치 권한·카카오맵 키가 없거나 지도를 못 띄우면 아무것도 그리지 않음 (홈의 나머지는 그대로).
 */
@Composable
internal fun HomeMapPreview(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (!KakaoCanvas.available || !hasLocationPermission(ctx)) return
    val loc = remember { lastKnownLocation(ctx) } ?: return
    var failed by remember { mutableStateOf(false) }
    if (failed) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        MapView(ctx).also { mv ->
            runCatching {
                mv.start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() = Unit
                        override fun onMapError(error: Exception) { failed = true }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun onMapReady(kakaoMap: KakaoMap) = Unit
                        override fun getPosition(): KLatLng = KLatLng.from(loc.latitude, loc.longitude)
                        override fun getZoomLevel(): Int = 16
                    },
                )
            }.onFailure { failed = true }
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { view.resume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { view.pause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        runCatching { view.resume() }
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { view.pause(); view.finish() }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // 내 위치 (지도 한가운데)
        Box(Modifier.align(Alignment.Center).size(20.dp).clip(CircleShape).background(Color.White).padding(3.5.dp).background(Color(0xFF2563EB), CircleShape))
        // 손으로 움직이지 않고 누르면 지도 탭으로
        Box(Modifier.matchParentSize().clickable(onClick = onOpen))
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            FitText(
                "지도 열기 ›",
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                maxSizeSp = 14f, minSizeSp = 10f, fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
