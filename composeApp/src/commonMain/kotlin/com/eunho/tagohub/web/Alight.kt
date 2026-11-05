package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 브라우저 위치를 계속 받기 시작 (못 하면 [onError]). 돌려준 값으로 [stopLocation] */
expect fun watchLocation(onPos: (lat: Double, lng: Double, accuracyM: Double) -> Unit, onError: (String) -> Unit): Int
expect fun stopLocation(id: Int)
/** 이 기기에서 위치를 받을 수 있는지 (PC 버전은 못 받음) */
expect val locationSupported: Boolean
/** 소리 내어 읽고 ([vibrate] 면 진동도) */
expect fun announce(text: String, vibrate: Boolean)

/** 하차 알림 단계 (클수록 내릴 때가 가까움) */
enum class AlightStage { Watching, Heads, Soon, Now, Passed }

/** 한 번 갱신한 결과 (폰 앱 AlightUpdate 와 같음) */
data class AlightUpdate(val stage: AlightStage, val distanceM: Double, val speak: String?, val vibrate: Boolean, val finished: Boolean)

/**
 * 내릴 정류장에 다가가는 것을 위치로 지켜보다가 거리에 따라 한 번씩 알려 줌 (폰 앱 AlightTracker 와 같은 규칙, 버스).
 * 800m 남으면 안내, 350m 곧 내림, 90m 내리세요. 가장 가까이 갔던 곳에서 멀어지면 지나친 것.
 */
class AlightTracker(val targetName: String, val lat: Double, val lng: Double) {
    private var stage = AlightStage.Watching
    private var minDistance = Double.MAX_VALUE
    private var done = false

    val label: String
        get() = if (targetName.endsWith("정류장") || targetName.endsWith("정류소")) targetName else "$targetName 정류장"

    fun update(lat: Double, lng: Double, accuracyM: Double = 0.0): AlightUpdate {
        val d = WebRepo.meters(lat, lng, this.lat, this.lng)
        if (accuracyM > MAX_ACCURACY_M) return AlightUpdate(stage, d, null, false, done)
        minDistance = min(minDistance, d)
        val reached = when {
            d <= NOW_M -> AlightStage.Now
            d <= SOON_M -> AlightStage.Soon
            d <= HEADS_M -> AlightStage.Heads
            else -> AlightStage.Watching
        }
        val passed = stage >= AlightStage.Soon && stage != AlightStage.Passed && d > max(NOW_M * 2.5, minDistance + PASSED_EXTRA_M)
        var speak: String? = null
        var vibrate = false
        if (passed) {
            stage = AlightStage.Passed
            speak = "$label${if (hasBatchim(label)) "을" else "를"} 지나쳤습니다. 다음 정류장에서 내려 되돌아가세요."
            vibrate = true
            done = true
        } else if (stage != AlightStage.Passed && reached > stage) {
            stage = reached
            when (reached) {
                AlightStage.Heads -> speak = "${label}까지 약 ${distanceText(d)} 남았습니다."
                AlightStage.Soon -> { speak = "곧 ${label}입니다. 내릴 준비를 하세요."; vibrate = true }
                AlightStage.Now -> { speak = "${label}입니다. 내리세요."; vibrate = true }
                else -> Unit
            }
        }
        return AlightUpdate(stage, d, speak, vibrate, done)
    }

    companion object {
        const val HEADS_M = 800.0
        const val SOON_M = 350.0
        const val NOW_M = 90.0
        const val MAX_ACCURACY_M = 80.0
        const val PASSED_EXTRA_M = 250.0

        fun hasBatchim(word: String): Boolean {
            val c = word.lastOrNull() ?: return false
            return c in '가'..'힣' && (c - '가') % 28 != 0
        }

        fun distanceText(m: Double): String =
            if (m >= 1000) "${(m / 100).roundToInt() / 10.0}km".replace(".0km", "km") else "${(m / 10).roundToInt() * 10}m"
    }
}

/** 지금 켜 둔 하차 알림 (한 번에 하나, 화면을 바꿔도 계속) */
object AlightAlert {
    var tracker by mutableStateOf<AlightTracker?>(null)
        private set
    var distanceM by mutableStateOf<Double?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var watchId = -1

    fun isTarget(name: String, lat: Double, lng: Double) = tracker?.let { it.targetName == name && it.lat == lat && it.lng == lng } == true

    fun start(name: String, lat: Double, lng: Double) {
        stop()
        val t = AlightTracker(name, lat, lng)
        tracker = t
        message = "위치를 받는 중이에요. 이 화면을 켜 두세요."
        watchId = watchLocation(
            onPos = { la, lo, acc ->
                if (tracker !== t) return@watchLocation
                val u = t.update(la, lo, acc)
                distanceM = u.distanceM
                u.speak?.let { message = it; announce(it, u.vibrate) }
                if (u.finished) stopWatchOnly()
            },
            onError = { why -> if (tracker === t) message = why },
        )
    }

    private fun stopWatchOnly() {
        if (watchId >= 0) stopLocation(watchId)
        watchId = -1
    }

    fun stop() {
        stopWatchOnly()
        tracker = null
        distanceM = null
        message = null
    }
}

/** 화면 위쪽 하차 알림 띠 (켜 두었을 때만) */
@Composable
internal fun AlightBanner() {
    val t = AlightAlert.tracker ?: return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B7F4B)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "하차 알림 · ${t.label}" + (AlightAlert.distanceM?.let { "  ${AlightTracker.distanceText(it)}" } ?: ""),
                color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            )
            AlightAlert.message?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            "끄기", Modifier.clickable { AlightAlert.stop() }.padding(start = 12.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        )
    }
}
