package com.eunho.tagohub.core

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 어느 쪽을 향하는지(방위, 도). GPS 방향은 움직여야만 나오고, 서 있거나 천천히 가면 비어 있거나 흔들려서
 * 방위를 못 잡았음. 그래서 (1) 어느 정도 빨리 움직일 때는 GPS 방향, (2) 서 있거나 느리면 기기의 방향 센서(나침반)를 쓴다.
 * 순수한 계산은 여기 모아 시험할 수 있게 함.
 */
object HeadingMath {
    /** 이 속도(m/s, 약 9km/h) 이상일 때만 GPS 방향을 믿음 */
    const val GPS_MIN_SPEED_MPS = 2.5f

    /** GPS 방향이 이만큼(ms) 지나면 오래된 것으로 보고 나침반으로 */
    const val GPS_MAX_AGE_MS = 6_000L

    /** 0 이상 360 미만으로 */
    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** [from] 에서 [to] 로 가는 가장 짧은 각도 차 (-180 ~ 180, 시계 방향이 +) */
    fun delta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** 각도의 지수 이동 평균 (0°/360° 경계를 넘어도 맞게). [alpha] 가 클수록 새 값을 빨리 따라감 */
    fun smooth(previous: Double?, next: Double, alpha: Double = 0.25): Double =
        if (previous == null) normalize(next) else normalize(previous + alpha * delta(previous, next))

    /** GPS 가 알려 준 방향을 쓸 수 있는지: 방향이 있고 충분히 빨리 움직이는 중 */
    fun gpsUsable(hasBearing: Boolean, speedMps: Float): Boolean = hasBearing && speedMps >= GPS_MIN_SPEED_MPS

    /**
     * 지금 보여줄 방위: 최근의 GPS 방향이 있으면 그것, 없으면 나침반. 둘 다 없으면 null.
     * [gpsAgeMs] 는 GPS 방향을 받은 지 얼마나 됐는지.
     */
    fun choose(gps: Double?, gpsAgeMs: Long, compass: Double?): Double? =
        if (gps != null && gpsAgeMs <= GPS_MAX_AGE_MS) gps else compass

    /**
     * 회전 행렬(SensorManager.getRotationMatrixFromVector 결과, 기기→세계)에서 자기 방위(북 0 · 동 90)를 구한다.
     * 폰이 평평하게 놓여 있으면 윗면(Y축)이 가리키는 쪽, 거치대에 세워 놓았으면 뒷면(-Z축)이 향하는 쪽이다.
     * (세워 둔 폰에 윗면 기준을 쓰면 하늘을 가리켜 방향이 불안정해지므로 둘을 나눔)
     */
    fun headingFromRotation(r: FloatArray): Double {
        val flat = abs(r[8]) > 0.75f          // 화면이 위나 아래를 향함 = 평평
        val east: Float
        val north: Float
        if (flat) {
            east = r[1]
            north = r[4]
        } else {
            east = -r[2]
            north = -r[5]
        }
        return normalize(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
    }
}

/**
 * 기기 방향 센서(회전 벡터)로 방위를 계속 알려 준다. 자기 북쪽을 진짜 북쪽으로 바꾸려고 편각(한국은 약 -8°)을 더한다.
 * 너무 잦게 알리지 않도록 2° 이상 바뀌었고 100ms 지났을 때만 [onHeading] 을 부른다.
 */
class HeadingSensor(context: Context, private val onHeading: (Double) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    @Volatile private var declination = DEFAULT_DECLINATION_DEG
    private var smoothed: Double? = null
    private var lastSent: Double? = null
    private var lastSentAt = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
        smoothed = null
        lastSent = null
    }

    /** 지금 있는 곳의 편각으로 바꿈 (위치를 알게 되면) */
    fun setLocation(lat: Double, lng: Double) {
        declination = runCatching { GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() }.getOrDefault(declination)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val heading = HeadingMath.normalize(HeadingMath.headingFromRotation(rotation) + declination)
        smoothed = HeadingMath.smooth(smoothed, heading)
        val now = System.currentTimeMillis()
        val h = smoothed ?: return
        val last = lastSent
        if (last == null || (abs(HeadingMath.delta(last, h)) >= 2.0 && now - lastSentAt >= 100)) {
            lastSent = h
            lastSentAt = now
            onHeading(h)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** 대한민국 서울 부근의 자기 편각 (서쪽으로 약 8°) */
        const val DEFAULT_DECLINATION_DEG = -8.0
    }
}
