package com.eunho.tagohub.guidance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eunho.tagohub.MainActivity
import com.eunho.tagohub.R
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BoardRule
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.ui.bus.liveArrivalsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 승차 알림: 정류장에서 기다리는 버스의 도착 정보를 20초마다 받아, 정한 만큼(예: 3정류장 전) 가까워지면
 * 음성·진동·알림으로 알리고 끝낸다. 앱을 닫거나 화면을 꺼도 계속 동작한다. 위치(GPS)는 쓰지 않음.
 * 오래(90분) 기다려도 안 오면 스스로 끝낸다. 알림의 [알림 끄기] 로도 끌 수 있다.
 */
class BoardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voice: GuidanceVoice? = null
    private var begun = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!begun) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        begun = true
        createChannels()
        val t = BoardAlert.target.value ?: run { stopSelf(); return }
        val title = "${t.routeNo}번 승차 알림"
        try {
            val first = ongoing(title, "${t.stopName} · ${t.rule.label}이면 알려드려요")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ONGOING_ID, first)
        } catch (e: Exception) {
            Log.w(TAG, "포그라운드 서비스를 시작하지 못함", e)
            BoardAlert.target.value = null
            stopSelf()
            return
        }
        voice = GuidanceVoice(this)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            while (true) {
                val next = runCatching { nextBus(t) }.getOrNull()
                if (t.rule.reached(next)) {
                    val text = "${t.routeNo}번 버스가 ${BoardRule.phrase(next!!)}이에요. ${t.stopName}에서 타세요."
                    alert(title, text)
                    voice?.speak(text)
                    vibrate()
                    BoardAlert.status.value = text
                    delay(8_000L)   // 말을 끝까지 하고
                    stopSelf()
                    return@launch
                }
                val line = next?.let { ArrivalText.line(it) } ?: "도착 정보를 기다리는 중"
                BoardAlert.status.value = line
                notifyOngoing(title, "${t.stopName} · $line")
                if (System.currentTimeMillis() - startedAt > MAX_WAIT_MS) {
                    alert(title, "${t.routeNo}번 버스가 오래 오지 않아 승차 알림을 껐어요.")
                    stopSelf()
                    return@launch
                }
                delay(POLL_MS)
            }
        }
        voice?.speak("${t.routeNo}번 버스가 ${t.rule.label}이면 알려드릴게요.")
    }

    /** 이 정류장(같은 자리의 다른 시군 정류장 포함)에서 이 노선의 가장 빨리 오는 버스 */
    private suspend fun nextBus(t: BoardAlert.Target): BusArrival? {
        val lists = kotlinx.coroutines.coroutineScope {
            t.stops.map { s -> async(Dispatchers.IO) { liveArrivalsFor(Graph.repo, s).orEmpty() } }.awaitAll()
        }
        return BoardRule.next(lists.flatten(), t.routeId)
    }

    private fun openIntent(code: Int) = PendingIntent.getActivity(
        this, code,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 12, Intent(this, BoardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(10))
            .addAction(0, "알림 끄기", stop)
            .build()
    }

    private fun notifyOngoing(title: String, text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ONGOING_ID, ongoing(title, text))
    }

    private fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(openIntent(11))
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n)
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 250, 700, 250, 700), -1)) }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(ONGOING_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "승차 알림 진행", NotificationManager.IMPORTANCE_LOW).apply {
                description = "승차 알림이 켜져 있는 동안 기다리는 버스가 얼마나 남았는지 보여줍니다."
                setSound(null, null)
            })
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "승차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "기다리는 버스가 가까워지면 화면에 띄워 알려줍니다."
                setSound(null, null)       // 소리는 음성 안내가, 진동은 앱이 직접 냄
                enableVibration(false)
            })
        }
    }

    override fun onDestroy() {
        voice?.shutdown()
        scope.cancel()
        BoardAlert.target.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.eunho.tagohub.guidance.BOARD_START"
        const val ACTION_STOP = "com.eunho.tagohub.guidance.BOARD_STOP"
        private const val ONGOING_CHANNEL = "board_ongoing"
        private const val ALERT_CHANNEL = "board_alert"
        private const val ONGOING_ID = 4202
        private const val ALERT_ID = 4203
        private const val TAG = "TagoBoard"
        private const val POLL_MS = 20_000L
        private const val MAX_WAIT_MS = 90L * 60 * 1000
    }
}
