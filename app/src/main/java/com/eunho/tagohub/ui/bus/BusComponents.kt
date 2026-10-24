package com.eunho.tagohub.ui.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.ui.common.LoadView
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.PlacePickerSheet
import com.eunho.tagohub.ui.common.ScreenTitle
import com.eunho.tagohub.ui.common.SearchBox
import com.eunho.tagohub.ui.common.SectionHeader
import com.eunho.tagohub.ui.common.Tnum
import com.eunho.tagohub.ui.theme.BoardColor
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import kotlin.coroutines.resume

private fun LocalTime.hhmm() = TagoTime.clockText(hour * 60 + minute)

@Composable
internal fun RouteListRow(r: BusRoute, region: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(r.routeNo, busRouteColor(r.routeType), Modifier.widthIn(min = 48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(r.startName, r.endName).joinToString(" ↔ ").ifEmpty { r.routeNo },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(region, r.routeType).joinToString("   ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StopListRow(s: BusStop, distance: String?, region: String? = null, numbers: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val sub = listOfNotNull(region, (numbers ?: s.nodeNo)?.let { "정류소번호 $it" }).joinToString("   ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = ModeColor.Bus) }
    }
}

@Composable
/** elapsedSec: 도착정보를 받은 뒤 지난 초. 다음 갱신 전까지 매초 줄어든다 */
/** dirLabel: 정류장 방면 (회차 노선에만 붙임 — 편도 노선은 기점-종점으로 충분) */
internal fun StopRouteItem(row0: StopRouteRow, elapsedSec: Int = 0, dirLabel: String? = null, onClick: () -> Unit) {
    // 정류장을 이미 지나간 버스는 바로 지움 (다음 새로고침을 기다리지 않고)
    val row = row0.copy(arrivals = row0.arrivals.filterNot { com.eunho.tagohub.data.PassedBus.passed(it, elapsedSec) })
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val soon = Color(0xFFD7262E)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽: 노선 종류 배지 + 큰 노선번호, 아래에 방면(없으면 기점 → 종점)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.routeType?.let { t ->
                    Pill(t.removeSuffix("버스"), busRouteColor(t))
                    Spacer(Modifier.width(8.dp))
                }
                Text(row.routeNo, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val a0 = row.arrivals.firstOrNull()
            val roundTrip = com.eunho.tagohub.data.RouteDirections.isRoundTrip(row.startName, row.endName)
            // 방면(지역 API, 없으면 순환 노선의 방향) · 현재 정류장(지역 API)
            val dir = a0?.towards?.let { "$it 방면" } ?: if (roundTrip) dirLabel?.let { "$it 방면" } else null
            val ends = listOfNotNull(row.startName, row.endName).joinToString(" → ")
            val sub = listOfNotNull(row.region, dir ?: ends.ifEmpty { null }, a0?.atStop?.let { "현재 $it" })
                .joinToString("   ").ifEmpty { null }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        // 오른쪽: 빠른 순으로 두 대를 빨간 글씨 두 줄로 — "4분 53초 (5개 전, 저상)"
        Column(horizontalAlignment = Alignment.End) {
            val first = row.arrivals.getOrNull(0)
            val second = row.arrivals.getOrNull(1)
            if (first == null) {
                Text(if (row.ended) "운행종료" else "도착 정보 없음", style = MaterialTheme.typography.bodyLarge, color = variant)
            } else {
                Text(arrivalLine(first, elapsedSec), style = MaterialTheme.typography.bodyLarge.merge(Tnum), color = soon, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(
                    second?.let { arrivalLine(it, elapsedSec) } ?: "정보없음",
                    style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                    color = if (second != null) soon else variant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** 도착 문구: 항상 초까지 + 남은 정류장 + 저상 (TAGO 버스도착정보 필드 그대로) */
internal fun arrivalLine(a: BusArrival, elapsedSec: Int): String = com.eunho.tagohub.data.ArrivalText.line(a, elapsedSec)

@Composable
internal fun RouteInfoCard(r: BusRoute) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (r.startName != null || r.endName != null) {
                Text("${r.startName ?: "?"} ↔ ${r.endName ?: "?"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            val parts = listOfNotNull(
                r.firstTime?.let { "첫차 $it" },
                r.lastTime?.let { "막차 $it" },
                r.interval?.let { "배차 ${it}분" },
                r.intervalSat?.let { "토 ${it}분" },
                r.intervalSun?.let { "일 ${it}분" },
            )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString("   "), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 노선 타임라인 왼쪽 여백 너비 (차량번호·회차 알약이 들어감) */
private val TIMELINE_GUTTER = 96.dp

@Composable
internal fun TurnMarker(color: Color) {
    // 회차 표시는 노선 띠 왼쪽 여백에 알약으로 (정류장 도착 앱처럼)
    Row(Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(TIMELINE_GUTTER), contentAlignment = Alignment.CenterEnd) {
            Pill("회차", color, filled = false)
        }
        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(24.dp)
                    .background(color)
            )
        }
    }
}

@Composable
internal fun TimelineRow(
    s: RouteStop,
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
    buses: List<BusLocation>,
    focused: Boolean,
    /** 이 정류장 첫차·막차 예상 시각 ("07:24 - 23:12") */
    times: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .background(if (focused) color.copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 12.dp, end = 16.dp),
    ) {
        // 왼쪽 여백: 이 정류소에 있는 버스의 차량번호 알약 (노선 띠 바로 옆)
        Box(Modifier.width(TIMELINE_GUTTER).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            buses.firstOrNull()?.let { b ->
                Text(
                    (b.vehicleNo ?: "운행 중") + if (buses.size > 1) " 외 ${buses.size - 1}" else "",
                    Modifier
                        .padding(end = 6.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .width(34.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            // 노선 띠(노선도처럼 굵게): 첫/끝 정류소에서는 반쪽만
            Column(Modifier.fillMaxHeight()) {
                Box(
                    Modifier
                        .width(8.dp)
                        .weight(1f)
                        .background(if (isFirst) Color.Transparent else color)
                )
                Box(
                    Modifier
                        .width(8.dp)
                        .weight(1f)
                        .background(if (isLast) Color.Transparent else color)
                )
            }
            if (buses.isNotEmpty()) {
                // 버스가 있는 정류소: 앰버 원에 버스 아이콘
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BoardColor.AmberFill)
                        .border(3.dp, color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DirectionsBus, contentDescription = "버스", tint = BoardColor.OnAmber, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, color, CircleShape)
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 11.dp)
        ) {
            Text(
                s.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (focused || buses.isNotEmpty()) FontWeight.Black else FontWeight.Medium,
            )
            val sub = listOfNotNull(times, s.nodeNo).joinToString("   ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall.merge(Tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (buses.any { it.lowFloor == true }) Pill("저상", color, filled = false)
        }
    }
}

// ───────────────────────── 위치 ─────────────────────────

internal fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** 휴대폰이 이미 알고 있는 마지막 위치 (기다림 없이 바로) */
@SuppressLint("MissingPermission")
internal fun lastKnownLocation(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return lm.getProviders(true)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
}

/** 내 위치 계속 받기 (GPS·네트워크, 2초 또는 3m 마다). 돌려주는 함수를 부르면 멈춤 */
@SuppressLint("MissingPermission")
internal fun startLocationUpdates(ctx: Context, onLocation: (Location) -> Unit): () -> Unit {
    if (!hasLocationPermission(ctx)) return {}
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = android.location.LocationListener { onLocation(it) }
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        .forEach { p -> runCatching { lm.requestLocationUpdates(p, 2000L, 3f, listener, android.os.Looper.getMainLooper()) } }
    return { runCatching { lm.removeUpdates(listener) } }
}

/** Play 서비스 없이 LocationManager 만 사용. 2분 이내 캐시 → 없으면 1회 측위(최대 10초) */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val last = lm.getProviders(true)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 120_000) return last
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last

    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> return last
    }
    val fresh = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }
    }
    return fresh ?: last
}
