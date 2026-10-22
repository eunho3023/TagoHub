package com.eunho.tagohub.guidance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eunho.tagohub.MainActivity
import com.eunho.tagohub.R
import com.eunho.tagohub.data.AlightStage
import com.eunho.tagohub.data.AlightTracker
import com.eunho.tagohub.data.NavText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 하차 알림: 버스·지하철을 타고 가는 동안 GPS 로 내릴 곳까지의 거리를 재서, 가까워지면 음성·진동·알림으로 알려준다.
 * 앱을 닫거나 화면을 꺼도 계속 동작한다(경로 안내와 달리 앱을 닫아도 끄지 않음: 주머니에 넣고 타는 것이 목적).
 * 내릴 곳을 알린 뒤 잠시 지나면 스스로 끝낸다. 알림의 [알림 끄기] 로도 끌 수 있다.
 */
class AlightService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tracker: AlightTracker? = null
    private var voice: GuidanceVoice? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopJob: Job? = null
    private var begun = false
    private var lastNotifyAt = 0L
    private var lastFixAt = 0L

    private val listener = LocationListener { loc -> handleLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!begun) begin()
        // 시스템이 죽였을 때 내릴 곳 없이 되살아나지 않도록 자동 재시작 안 함
        return START_NOT_STICKY
    }

    private fun begin() {
        begun = true
        createChannels()
        val target = AlightAlert.target.value
        if (target == null) {
            stopSelf()
            return
        }
        // 위치 권한이 없으면 위치 유형 서비스를 올릴 수 없다(안드로이드 14부터 예외로 앱이 종료됨)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AlightAlert.note.value = "위치 권한이 없어 하차 알림을 시작하지 못했습니다."
            AlightAlert.target.value = null
            stopSelf()
            return
        }
        val t = AlightTracker(target.name, target.lat, target.lng, target.subway)
        tracker = t
        try {
            val first = ongoingNotification("${t.label} 하차 알림", "위치를 확인하는 중…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ONGOING_ID, first)
        } catch (e: Exception) {
            Log.w(TAG, "포그라운드 서비스를 시작하지 못함", e)
            AlightAlert.note.value = "하차 알림을 시작하지 못했습니다. 앱을 화면에 띄운 상태에서 다시 시도하세요."
            AlightAlert.target.value = null
            stopSelf()
            return
        }
        voice = GuidanceVoice(this).also { it.enabled = AlightAlert.voiceOn.value }
        scope.launch { AlightAlert.voiceOn.collect { on -> voice?.enabled = on; if (!on) voice?.stopSpeaking() } }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tagohub:alight").apply { acquire(MAX_SESSION_MS) }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) AlightAlert.note.value = "위치(GPS)가 꺼져 있습니다. 켜야 하차 알림이 동작합니다."
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, listener, Looper.getMainLooper())
            // 건물·지하 근처에서 GPS 가 늦을 때를 위해 네트워크 위치도 받음 (오차가 크면 걸러짐)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_MS * 3, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "위치 권한 오류", e)
            stopSelf()
            return
        }
        // 위치 신호가 한참 없으면 알려줌 (지하철 터널 등)
        scope.launch {
            while (true) {
                delay(30_000L)
                val gap = System.currentTimeMillis() - lastFixAt
                if (lastFixAt != 0L && gap > 90_000L) AlightAlert.note.value = "위치 신호가 약합니다. 지하에서는 알림이 늦을 수 있어요."
            }
        }
        voice?.speak("${t.label}에서 내릴 때 알려드릴게요.")
    }

    private fun handleLocation(loc: Location) {
        val t = tracker ?: return
        lastFixAt = System.currentTimeMillis()
        if (AlightAlert.note.value?.startsWith("위치 신호") == true) AlightAlert.note.value = null
        val u = t.update(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
        AlightAlert.distance.value = u.distanceM
        updateOngoing(t.label, u.distanceM, u.stage)
        u.speak?.let { text ->
            voice?.speak(text)
            alertNotification(text)
            if (u.vibrate) vibrate(u.stage)
        }
        // 내리라고 알린 뒤 잠시 지나면 끝냄 (지나쳤으면 더 빨리)
        if (stopJob == null && (u.finished || t.arrivedAnnounced)) {
            stopJob = scope.launch {
                delay(if (u.finished) 30_000L else 120_000L)
                stopSelf()
            }
        }
    }

    private fun updateOngoing(label: String, distance: Double, stage: AlightStage) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 3000L) return
        lastNotifyAt = now
        val text = when (stage) {
            AlightStage.Passed -> "지나쳤습니다"
            AlightStage.Now -> "지금 내리세요"
            else -> "남은 거리 ${NavText.short(distance)}"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ONGOING_ID, ongoingNotification("$label 하차 알림", text))
    }

    private fun ongoingNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, AlightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "알림 끄기", stop)
            .build()
    }

    /** 화면 위에 잠깐 떠서 눈에도 띄는 알림 (소리·진동은 직접 냄) */
    private fun alertNotification(text: String) {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("하차 알림")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .setContentIntent(open)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n)
    }

    private fun vibrate(stage: AlightStage) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        // 곧: 짧게 두 번 / 지금·지나침: 길게 세 번
        val pattern = if (stage == AlightStage.Soon) longArrayOf(0, 300, 200, 300) else longArrayOf(0, 700, 250, 700, 250, 700)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(ONGOING_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "하차 알림 진행", NotificationManager.IMPORTANCE_LOW).apply {
                description = "하차 알림이 켜져 있는 동안 내릴 곳까지 남은 거리를 보여줍니다."
                setSound(null, null)
            })
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "곧 내릴 때와 지나쳤을 때 화면에 띄워 알려줍니다."
                setSound(null, null)       // 소리는 음성 안내가, 진동은 앱이 직접 냄
                enableVibration(false)
            })
        }
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(listener)
        voice?.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        AlightAlert.target.value = null
        AlightAlert.distance.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.eunho.tagohub.guidance.ALIGHT_START"
        const val ACTION_STOP = "com.eunho.tagohub.guidance.ALIGHT_STOP"
        private const val ONGOING_CHANNEL = "alight_ongoing"
        private const val ALERT_CHANNEL = "alight_alert"
        private const val ONGOING_ID = 4102
        private const val ALERT_ID = 4103
        private const val TAG = "TagoAlight"
        private const val UPDATE_MS = 2000L
        /** 안 끝나도 여기서 멈춤(배터리 보호): 3시간 */
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000
    }
}
