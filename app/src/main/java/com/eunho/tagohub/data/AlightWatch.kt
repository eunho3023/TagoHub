package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlin.math.max
import kotlin.math.min

/** 하차 알림의 진행 단계. 숫자가 클수록 내릴 때가 가까움 */
enum class AlightStage { Watching, Heads, Soon, Now, Passed }

/**
 * 한 번 갱신한 결과: [distanceM] 은 내릴 곳까지 직선거리, [speak] 는 지금 말하고 띄울 문구(없으면 null),
 * [vibrate] 는 진동으로도 알려야 하는지(곧 내릴 때·지나쳤을 때), [finished] 는 알림을 끝내도 되는 때.
 */
data class AlightUpdate(
    val stage: AlightStage,
    val distanceM: Double,
    val speak: String?,
    val vibrate: Boolean,
    val finished: Boolean,
)

/**
 * 내릴 곳([targetName], 좌표)에 다가가는 것을 GPS 위치로 지켜보다가 거리에 따라 한 번씩 알려준다.
 * 지하철은 터널에서 GPS 가 늦게 잡혀 정확도가 떨어지므로 더 멀리서부터 알린다.
 * 각 단계는 한 번만 알리고, 처음 위치가 이미 가까우면 그중 가장 급한 단계 하나만 알린다.
 */
class AlightTracker(
    val targetName: String,
    val lat: Double,
    val lng: Double,
    val subway: Boolean = false,
) {
    private val headsM = if (subway) 2000.0 else 800.0
    private val soonM = if (subway) 900.0 else 350.0
    private val nowM = if (subway) 250.0 else 90.0

    private var stage = AlightStage.Watching
    private var minDistance = Double.MAX_VALUE
    private var done = false

    /** 문구에 쓰는 이름: 지하철은 "○○역", 버스는 "○○ 정류장" (이미 붙어 있으면 그대로) */
    val label: String
        get() = when {
            subway -> if (targetName.endsWith("역")) targetName else "${targetName}역"
            else -> if (targetName.endsWith("정류장") || targetName.endsWith("정류소")) targetName else "$targetName 정류장"
        }

    /** [accuracyM] 이 너무 크면(80m 초과) 위치를 믿지 않고 단계를 올리지 않는다 */
    fun update(lat: Double, lng: Double, accuracyM: Double = 0.0): AlightUpdate {
        val d = Geo.meters(lat, lng, this.lat, this.lng)
        if (accuracyM > MAX_ACCURACY_M) return AlightUpdate(stage, d, null, false, done)
        minDistance = min(minDistance, d)

        val reached = when {
            d <= nowM -> AlightStage.Now
            d <= soonM -> AlightStage.Soon
            d <= headsM -> AlightStage.Heads
            else -> AlightStage.Watching
        }
        // 가장 가까이 갔던 곳에서 멀어지고 있으면 지나친 것 (이미 곧 내릴 단계까지 왔을 때만)
        val passed = stage >= AlightStage.Soon && stage != AlightStage.Passed &&
            d > max(nowM * 2.5, minDistance + PASSED_EXTRA_M)

        var speak: String? = null
        var vibrate = false
        if (passed) {
            stage = AlightStage.Passed
            speak = "$label${if (hasBatchim(label)) "을" else "를"} 지나쳤습니다. 다음 ${if (subway) "역" else "정류장"}에서 내려 되돌아가세요."
            vibrate = true
            done = true
        } else if (stage != AlightStage.Passed && reached > stage) {
            stage = reached
            when (reached) {
                AlightStage.Heads -> speak = "${label}까지 약 ${NavText.distance(d)} 남았습니다."
                AlightStage.Soon -> { speak = "곧 ${label}입니다. 내릴 준비를 하세요."; vibrate = true }
                AlightStage.Now -> { speak = "${label}입니다. 내리세요."; vibrate = true }
                else -> Unit
            }
        }
        return AlightUpdate(stage, d, speak, vibrate, done)
    }

    /** "내리세요"까지 알렸다면 잠시 뒤 알림을 끝낼 수 있음 */
    val arrivedAnnounced: Boolean get() = stage >= AlightStage.Now

    private companion object {
        /** 마지막 글자에 받침이 있으면 을/은, 없으면 를/는 */
        fun hasBatchim(word: String): Boolean {
            val c = word.lastOrNull() ?: return false
            return c in '가'..'힣' && (c - '가') % 28 != 0
        }

        const val MAX_ACCURACY_M = 80.0
        const val PASSED_EXTRA_M = 250.0
    }
}
