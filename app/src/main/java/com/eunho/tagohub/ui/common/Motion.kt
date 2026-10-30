package com.eunho.tagohub.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 기기의 "애니메이션 제거"(개발자 옵션·접근성)가 켜져 있으면 true: 이때는 움직임 없이 바로 바뀜 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * 화면에 처음 들어올 때 14dp 위로 올라오며 나타남 (240ms). [index] 번째는 40ms 씩 늦게 시작해 차례로 보임.
 * 한 번 보인 뒤에는 다시 움직이지 않음 (스크롤로 다시 그려져도 그대로).
 */
fun Modifier.riseIn(index: Int = 0): Modifier = composed {
    val reduce = rememberReduceMotion()
    val shown = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown.value < 1f) {
            delay(index * 40L)
            shown.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        }
    }
    val rise = with(LocalDensity.current) { 14.dp.toPx() }
    graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * rise
    }
}

/** 누르는 동안 97% 로 살짝 작아졌다 돌아옴 (90ms). [source] 는 클릭 가능한 쪽과 같은 것을 넘겨야 함 */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val reduce = rememberReduceMotion()
    val pressed by source.collectIsPressedAsState()
    val scale = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.97f else 1f,
        animationSpec = tween(90),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
