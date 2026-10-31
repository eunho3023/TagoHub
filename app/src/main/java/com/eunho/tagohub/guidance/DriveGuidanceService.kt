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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eunho.tagohub.MainActivity
import com.eunho.tagohub.R
import com.eunho.tagohub.data.KakaoDriveRoute
import com.eunho.tagohub.data.KakaoExtra
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.NavEngine
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.data.RouteSimulator
import com.eunho.tagohub.data.ExMapLayers
import com.eunho.tagohub.data.ItsRoadFeature
import com.eunho.tagohub.data.RestAhead
import com.eunho.tagohub.data.RouteCompare
import com.eunho.tagohub.data.RestTracker
import com.eunho.tagohub.data.RoadHazards
import com.eunho.tagohub.data.RoadAlertTracker
import com.eunho.tagohub.data.RoadAlerts
import com.eunho.tagohub.core.HeadingMath
import com.eunho.tagohub.data.RouteZone
import com.eunho.tagohub.data.ZoneGroup
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.SafetyZones
import com.eunho.tagohub.data.ZoneTracker
import com.eunho.tagohub.data.matchRoute
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 경로 안내 포그라운드 서비스: 화면이 꺼져도 GPS 를 받아 안내한다.
 * 위치 → [NavEngine] → (음성 · 알림 · 화면용 상태). 경로를 벗어나면 현재 위치에서 새 경로를 받아 이어간다.
 * 상시 위치 권한은 쓰지 않는다: 사용자가 앱을 보는 중에 안내를 시작하면 "앱 사용 중" 권한만으로 서비스가 위치를 받는다.
 */
class DriveGuidanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voice: GuidanceVoice? = null
    private var engine: NavEngine? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopJob: Job? = null
    private var simJob: Job? = null
    private var currentNav: NavRoute? = null
    private var routeVersion = 0
    private var begun = false

    private var lastNotifyAt = 0L
    private var lastTitle = ""
    private var lastRerouteAt = 0L
    private var rerouting = false
    private var rerouteCount = 0
    private var noted = false

    private val listener = LocationListener { loc -> handleLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!begun) begin()
        // 시스템이 서비스를 죽였을 때 경로·목적지 없이 되살아나지 않도록 자동 재시작 안 함
        return START_NOT_STICKY
    }

    private fun begin() {
        begun = true
        createChannel()
        val route = DriveGuidance.route.value
        if (route == null) {
            stopSelf()
            return
        }
        // 위치 권한이 없으면 위치 유형 서비스를 올릴 수 없다(안드로이드 14부터 예외로 앱이 종료됨). 올리기 전에 확인.
        // 서비스를 올리지 않고 바로 끝내는 것은 허용됨.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DriveGuidance.note.value = "위치 권한이 없어 안내를 시작하지 못했습니다."
            stopSelf()
            return
        }
        // 서비스를 시작한 직후 몇 초 안에 알림을 띄워야 함
        val first = buildNotification("경로 안내를 시작합니다", "${NavText.short(route.distanceMeters.toDouble())} · 약 ${(route.durationSeconds + 59) / 60}분")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        } catch (e: Exception) {
            // 앱이 화면에 보이지 않을 때 시작하는 등 시스템이 거부한 경우
            Log.w(TAG, "포그라운드 서비스를 시작하지 못함", e)
            DriveGuidance.note.value = "안내를 시작하지 못했습니다. 앱을 화면에 띄운 상태에서 다시 시도하세요."
            stopSelf()
            return
        }

        install(route)
        voice = GuidanceVoice(this).also { it.enabled = DriveGuidance.voiceOn.value }
        scope.launch {
            DriveGuidance.voiceOn.collect { on ->
                voice?.enabled = on
                if (!on) voice?.stopSpeaking()
            }
        }
        scope.launch { DriveGuidance.voiceVolume.collect { v -> voice?.volume = v } }
        DriveGuidance.suggestion.value = null
        DriveGuidance.accepted.value = null
        scope.launch {
            // 제안을 받아들이면 그 경로로 바꿈
            DriveGuidance.accepted.collect { s ->
                if (s != null) {
                    DriveGuidance.accepted.value = null
                    install(s.route)
                    voice?.speak("새 경로로 안내합니다.")
                }
            }
        }
        scope.launch { suggestLoop() }
        scope.launch { DriveGuidance.zoneGroups.collect { rebuildTracker() } }
        scope.launch { roadLinks = com.eunho.tagohub.data.RoadLinkIndex.get(applicationContext) }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tagohub:guidance").apply { acquire(MAX_SESSION_MS) }

        if (DriveGuidance.simulating.value) {
            // 모의주행: GPS 를 켜지 않고 경로를 따라 가상 위치를 만든다. 이후 음성·알림·재탐색은 실제 안내와 똑같이 동작
            startSimulation()
            voice?.speak("모의주행을 시작합니다. ${NavText.distance(route.distanceMeters.toDouble())}, 약 ${(route.durationSeconds + 59) / 60}분 걸립니다.")
        } else {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            try {
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    DriveGuidance.note.value = "위치(GPS)가 꺼져 있습니다. 위치를 켜면 안내가 시작됩니다."
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "위치 권한 오류", e)
                stopSelf()
                return
            }
            voice?.speak("경로 안내를 시작합니다. ${NavText.distance(route.distanceMeters.toDouble())}, 약 ${(route.durationSeconds + 59) / 60}분 걸립니다.")
        }
        DriveGuidance.active.value = true
    }

    private fun install(route: KakaoDriveRoute) {
        val nav = NavRoute.from(route)
        currentNav = nav
        routeVersion++
        engine = NavEngine(nav)
        DriveGuidance.route.value = route
        // 이 경로 위의 단속카메라·어린이 보호구역 (목록은 한 번만 읽고, 경로가 바뀌면 다시 맞춤)
        loadRest(nav)
        loadAlerts(nav)
        val loaded = zoneData
        if (loaded != null) { matched = matchRoute(nav, loaded); rebuildTracker() }
        else scope.launch(Dispatchers.Default) {
            val zones = SafetyZones.load(applicationContext)
            zoneData = zones
            currentNav?.let { matched = matchRoute(it, zones); rebuildTracker() }
        }
    }

    /** 경로 위 휴게소를 한 번 받아(한국도로공사, 10분 캐시) 이 경로에 맞춤. 키가 없거나 실패하면 이 안내만 없음 */
    private fun loadRest(nav: NavRoute) {
        scope.launch(Dispatchers.Default) {
            val features = restFeatures ?: runCatching { ExMapLayers.features(ExMapLayers.REST) }.getOrNull()?.also { restFeatures = it }.orEmpty()
            if (currentNav === nav) restTracker = RestTracker(RestAhead.match(nav, features))
        }
    }

    /**
     * 경로 위 상습 결빙구간·도로 위험 예보·재난·주의운전·교통안전 지점·톨게이트를 경로에 맞춤 ([RoadHazards], 지도 레이어 대신 안내로).
     * 받지 못한 종류는 빠질 뿐 안내는 그대로 된다.
     */
    private fun loadAlerts(nav: NavRoute) {
        scope.launch(Dispatchers.Default) {
            val hazards = RoadHazards.load(applicationContext)
            if (currentNav === nav) alertTracker = RoadAlertTracker(RoadAlerts.match(nav, hazards))
        }
    }

    /** 경로 위 안전 지점 중 설정에서 켜 둔 종류만 알림 대상으로 */
    private fun rebuildTracker() {
        val on = DriveGuidance.zoneGroups.value
        tracker = ZoneTracker(matched.filter { it.zone.kind.group in on })
    }

    /** 모의주행 루프: [SIM_TICK_MS] 마다 가상 위치를 만들어 [handleLocation] 에 넣음 */
    private fun startSimulation() {
        val nav = currentNav ?: return
        val sim = RouteSimulator(nav)
        var seenVersion = routeVersion
        var seenDeviate = DriveGuidance.deviate.value
        var deviateUntil = 0L
        simJob = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(SIM_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = now - last
                last = now
                // 재탐색으로 새 경로가 설치되면 거기서 이어서 달림(이탈 시험도 끝냄)
                if (seenVersion != routeVersion) {
                    seenVersion = routeVersion
                    currentNav?.let { sim.replaceRoute(it) }
                    deviateUntil = 0L
                }
                val trigger = DriveGuidance.deviate.value
                if (trigger != seenDeviate) {
                    seenDeviate = trigger
                    deviateUntil = now + DEVIATE_MS
                }
                val fix = sim.step(dt, DriveGuidance.simFactor.value, if (now < deviateUntil) DEVIATE_OFFSET_M else 0.0)
                handleLocation(Location("sim").apply {
                    latitude = fix.lat
                    longitude = fix.lng
                    speed = fix.speedMps.toFloat()
                    accuracy = 5f
                    time = System.currentTimeMillis()
                })
            }
        }
    }

    @Volatile private var zoneData: List<SafetyZone>? = null
    @Volatile private var tracker: ZoneTracker? = null
    @Volatile private var matched: List<RouteZone> = emptyList()
    @Volatile private var restFeatures: List<ItsRoadFeature>? = null
    @Volatile private var restTracker: RestTracker? = null
    @Volatile private var alertTracker: RoadAlertTracker? = null
    @Volatile private var roadLinks: com.eunho.tagohub.data.RoadLinkIndex? = null
    private val speedWatch = com.eunho.tagohub.data.SpeedLimitWatch()
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLng = 0.0
    private var lastSuggestAt = 0L

    /**
     * 안내 중 가끔 지금 위치에서 새 경로를 받아 보고, 실제로 다른 길이면서 충분히 빠르면 제안한다.
     * 한 번 제안한 뒤에는 10분 쉼. 받아들이지 않으면 1분 뒤 사라짐.
     */
    private suspend fun suggestLoop() {
        delay(SUGGEST_FIRST_MS)
        while (true) {
            runCatching { trySuggest() }.onFailure { Log.w(TAG, "더 빠른 경로 확인 실패: ${it.message}") }
            delay(SUGGEST_EVERY_MS)
        }
    }

    private suspend fun trySuggest() {
        val nav = currentNav ?: return
        val st = DriveGuidance.info.value?.state ?: return
        val now = SystemClock.elapsedRealtime()
        if (st.offRoute || st.arrived || rerouting || DriveGuidance.suggestion.value != null) return
        if (st.remainingMeters < RouteCompare.MIN_REMAINING_M) return
        if (lastSuggestAt != 0L && now - lastSuggestAt < SUGGEST_COOLDOWN_MS) return
        val dest = DriveGuidance.destination ?: return
        val (_, fresh) = KakaoExtra.drive(LatLng(lastLat, lastLng), dest, DriveGuidance.option, DriveGuidance.vias, DriveGuidance.info.value?.bearing)
        fresh ?: return
        val div = withContext(Dispatchers.Default) { RouteCompare.divergence(nav, fresh.points) }
        val saved = RouteCompare.savedSeconds(nav.remainingSeconds(st.progressMeters), fresh.durationSeconds, div) ?: return
        lastSuggestAt = now
        DriveGuidance.suggestion.value = DriveGuidance.Suggestion(fresh, saved)
        voice?.speak("더 빠른 경로가 있습니다. 약 ${(saved + 59) / 60}분 단축됩니다. 화면에서 선택하세요.")
        scope.launch {
            delay(SUGGEST_SHOW_MS)
            if (DriveGuidance.suggestion.value?.route === fresh) DriveGuidance.suggestion.value = null
        }
    }

    private fun handleLocation(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        DriveGuidance.vias = com.eunho.tagohub.data.ViaTracker.remaining(DriveGuidance.vias, loc.latitude, loc.longitude)
        val eng = engine ?: return
        // 오차가 너무 큰 위치는 안내에 쓰지 않음 (터널 입구 등)
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val st = eng.update(
            loc.latitude,
            loc.longitude,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0,
        )
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val zone = if (st.offRoute || st.arrived) null else tracker?.update(st.progressMeters, speedKmh)
        val restOn = ZoneGroup.Rest in DriveGuidance.zoneGroups.value
        val rest = if (st.offRoute || st.arrived || !restOn) null else restTracker?.update(st.progressMeters, st.remainingMeters)
        // 모의주행은 배속만큼 빨리 달리는 것이므로 화면 속도도 그만큼 (1배속 100 → 5배속 500 …)
        val shownKmh = if (DriveGuidance.simulating.value) speedKmh * DriveGuidance.simFactor.value else speedKmh
        val facilityOn = ZoneGroup.Facility in DriveGuidance.zoneGroups.value
        val alert = if (st.offRoute || st.arrived || !facilityOn) null else alertTracker?.update(st.progressMeters)
        val bearing = if (HeadingMath.gpsUsable(loc.hasBearing(), if (loc.hasSpeed()) loc.speed else 0f) || (loc.hasBearing() && loc.speed >= 1f)) loc.bearing.toDouble() else null
        // 지금 달리는 도로의 제한속도·차로 수 (표준 노드·링크). 단속카메라 제한속도가 따로 있으면 화면은 그것을 먼저 보여 줌
        val road = roadLinks?.find(loc.latitude, loc.longitude, bearing)
        DriveGuidance.info.value = DriveGuidance.Info(st, loc.latitude, loc.longitude, shownKmh.roundToInt(), zone?.takeIf { it.next != null }, rest, alert, bearing, road?.speedKmh, road?.lanes ?: 0)
        val v = voice
        // 회전 안내와 안전 지점 안내가 동시에 나오면 한 번에 이어서 말함 (따로 말하면 앞 말이 끊김)
        val over = if (st.offRoute || st.arrived || DriveGuidance.simulating.value || zone?.overspeed == true) null
        else speedWatch.update(speedKmh.roundToInt(), road?.speedKmh, System.currentTimeMillis())
        val lines = st.speak + zone?.speak.orEmpty() + listOfNotNull(rest?.speak) + listOfNotNull(alert?.speak) + listOfNotNull(over)
        // 과속 경고음: 단속카메라 제한속도 +3·+5km/h 초과, 어린이 보호구역 제한속도 초과 즉시 (새 경고가 나온 순간 한 번)
        if (zone?.beep == true) playOverspeedBeep()
        // 안내마다 따로 차례로 말함(한 번에 이어 붙이면 길어서 중간에 끊기고, 새 안내가 오면 앞 말이 잘렸음). 경로 이탈만 바로 끊고 말함
        lines.forEach { v?.speak(it, interrupt = st.offRoute) }
        if (!st.offRoute) rerouteCount = 0   // 다시 경로 위로 돌아오면 재탐색 횟수 제한을 처음부터 (계속 이탈해도 언젠가 막히지 않게)
        if (v != null && v.ready && !v.koreanAvailable && !noted) {
            noted = true
            DriveGuidance.note.value = "이 기기에 한국어 음성이 없어 소리 없이 안내합니다. 설정 > 음성 출력에서 한국어 음성을 설치하세요."
        }
        val prefix = if (DriveGuidance.simulating.value) "[모의주행] " else ""
        notify(prefix + GuidanceText.title(st), GuidanceText.detail(st, System.currentTimeMillis()))
        if (st.offRoute) maybeReroute(loc)
        if (st.arrived && stopJob == null) {
            stopJob = scope.launch {
                delay(ARRIVAL_STOP_DELAY_MS) // 도착 안내를 끝까지 들려준 뒤 종료
                stopSelf()
            }
        }
    }

    /** 짧은 경고음 두 번. 안내 음성보다 먼저 들리게 음성과 별개로 울림 */
    private fun playOverspeedBeep() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 450)
            scope.launch { delay(900); runCatching { tone.release() } }
        }
    }

    /** 경로를 벗어나면 현재 위치에서 목적지까지 새 경로를 받음. 너무 잦거나 많이 하지 않음 */
    private fun maybeReroute(loc: Location) {
        val dest = DriveGuidance.destination ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteAt < REROUTE_MIN_GAP_MS || rerouteCount >= MAX_REROUTES) return
        rerouting = true
        lastRerouteAt = now
        rerouteCount++
        scope.launch {
            // 달리고 있으면 가는 방향으로 출발하는 경로를 받음 (반대 방향으로 유턴·회전하라는 안내 방지)
            val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= HEADING_MIN_MPS) loc.bearing.toDouble() else null
            var (status, route) = KakaoExtra.drive(LatLng(loc.latitude, loc.longitude), dest, DriveGuidance.option, DriveGuidance.vias, heading)
            // 방향을 줘서 못 찾으면(도로 방향과 맞지 않는 곳 등) 방향 없이 한 번 더
            if (route == null && heading != null) {
                val retry = KakaoExtra.drive(LatLng(loc.latitude, loc.longitude), dest, DriveGuidance.option, DriveGuidance.vias)
                status = retry.first
                route = retry.second
            }
            rerouting = false
            if (route != null) {
                install(route)
                voice?.speak("새 경로로 안내합니다.", interrupt = true)
            } else {
                Log.w(TAG, "재탐색 실패: $status")
                // 실패한 시도는 횟수에 넣지 않음 (신호가 잠깐 안 좋아서 실패했다고 재탐색 기회가 줄지 않게)
                rerouteCount = (rerouteCount - 1).coerceAtLeast(0)
            }
        }
    }

    private fun notify(title: String, text: String) {
        val now = SystemClock.elapsedRealtime()
        if (title == lastTitle && now - lastNotifyAt < 5000L) return
        if (now - lastNotifyAt < 1000L) return
        lastNotifyAt = now
        lastTitle = title
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DriveGuidanceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "안내 종료", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // 소리는 음성 안내가 내므로 알림 자체는 조용하게
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "경로 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "경로 안내 중에 다음 안내와 남은 거리를 보여줍니다."
                setSound(null, null)
            })
        }
    }

    /** 최근 앱 목록에서 앱을 밀어 끄면 안내도 함께 끝냄 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(listener)
        voice?.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        DriveGuidance.active.value = false
        DriveGuidance.simulating.value = false
        DriveGuidance.info.value = null
        DriveGuidance.route.value = null
        DriveGuidance.suggestion.value = null
        DriveGuidance.accepted.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.eunho.tagohub.guidance.START"
        const val ACTION_STOP = "com.eunho.tagohub.guidance.STOP"
        private const val CHANNEL_ID = "drive_guidance"
        private const val NOTIFICATION_ID = 4101
        private const val TAG = "TagoGuidance"
        private const val MAX_ACCURACY_M = 100f
        private const val REROUTE_MIN_GAP_MS = 12_000L
        private const val MAX_REROUTES = 20

        /** 이 속도(m/s) 이상일 때만 GPS 진행 방향을 믿고 재탐색에 넘김 */
        private const val HEADING_MIN_MPS = 2f
        private const val ARRIVAL_STOP_DELAY_MS = 8_000L
        private const val SIM_TICK_MS = 200L
        private const val SUGGEST_FIRST_MS = 90_000L
        private const val SUGGEST_EVERY_MS = 150_000L
        private const val SUGGEST_COOLDOWN_MS = 10 * 60_000L
        private const val SUGGEST_SHOW_MS = 60_000L
        private const val DEVIATE_MS = 6_000L
        private const val DEVIATE_OFFSET_M = 300.0
        /** 안내가 끝나지 않아도 여기서 멈춤(배터리 보호): 6시간 */
        private const val MAX_SESSION_MS = 6L * 60 * 60 * 1000
    }
}
