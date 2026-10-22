package com.eunho.tagohub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eunho.tagohub.MainActivity
import com.eunho.tagohub.R
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.FavoriteCodec
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.ui.bus.arrivalsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 홈 화면 위젯: 즐겨찾기 정류장(앞에서 3곳)의 가까운 버스 도착을 보여준다.
 * 자동으로 30분마다(안드로이드가 정한 최소 간격), 위젯을 누르면 바로 새로고침, 제목을 누르면 앱을 연다.
 */
class StopWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(ComponentName(context, StopWidgetProvider::class.java)))
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        // 먼저 "불러오는 중" 을 보여주고, 받은 뒤에 바꿈
        ids.forEach { manager.updateAppWidget(it, views(app, null)) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val rows = load(app)
                ids.forEach { manager.updateAppWidget(it, views(app, rows)) }
            } finally {
                pending.finish()
            }
        }
    }

    /** 즐겨찾기 정류장마다 (이름, 도착 줄) — 저장해 둔 것이 없으면 빈 목록 */
    private suspend fun load(context: Context): List<Pair<String, String>> {
        val favs = FavoriteCodec.decode(context.getSharedPreferences("bus", Context.MODE_PRIVATE).getString("favorites", null))
        val stops = WidgetText.pickStops(favs)
        // 교통안전공단 정류장(TS…)은 정류장 DB 가 있어야 도시를 알 수 있음
        if (stops.any { it.cityCode.startsWith("TS") }) runCatching { StopDb.get(context) }
        return coroutineScope {
            stops.map { f -> async { f.name to arrivalText(f) } }.awaitAll()
        }
    }

    private suspend fun arrivalText(f: Favorite): String =
        withTimeoutOrNull(12_000L) {
            when (val r = runCatching { arrivalsFor(Graph.repo, f.toStop()) }.getOrNull()) {
                is TagoResult.Success -> WidgetText.arrivalLines(r.data)
                else -> WidgetText.failed()
            }
        } ?: WidgetText.failed()

    companion object {
        const val ACTION_REFRESH = "com.eunho.tagohub.widget.REFRESH"

        private val nameIds = intArrayOf(R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3)
        private val lineIds = intArrayOf(R.id.widget_lines_1, R.id.widget_lines_2, R.id.widget_lines_3)

        /** [rows] 가 null 이면 불러오는 중, 비어 있으면 즐겨찾기 안내 */
        fun views(context: Context, rows: List<Pair<String, String>>?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_stops)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            v.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), flags))
            v.setOnClickPendingIntent(
                R.id.widget_body,
                PendingIntent.getBroadcast(context, 11, Intent(context, StopWidgetProvider::class.java).setAction(ACTION_REFRESH), flags),
            )
            v.setTextViewText(
                R.id.widget_updated,
                if (rows == null) "불러오는 중…" else SimpleDateFormat("HH:mm 기준 · 누르면 새로고침", Locale.KOREA).format(Date()),
            )
            v.setTextViewText(R.id.widget_empty, if (rows != null && rows.isEmpty()) "앱에서 정류장의 ★ 를 눌러 즐겨찾기에 추가하면 여기에 도착 정보가 보여요." else "")
            for (i in nameIds.indices) {
                val row = rows?.getOrNull(i)
                v.setTextViewText(nameIds[i], row?.first.orEmpty())
                v.setTextViewText(lineIds[i], row?.second.orEmpty())
            }
            return v
        }
    }
}
