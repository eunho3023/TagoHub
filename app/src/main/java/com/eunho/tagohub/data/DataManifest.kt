package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson

/**
 * 서버(GitHub Release data-latest)의 정류장·노선 데이터 안내 파일 (pipeline/build_data.py 가 만듦).
 * {"version":"202610200300","file":"bus_stops.db.gz","sha256":"…","size":…,"dbSize":…,"complete":true}
 */
data class DataManifest(val version: String, val file: String, val sha256: String, val size: Long, val dbSize: Long, val complete: Boolean) {
    companion object {
        fun parse(text: String): DataManifest? {
            val m = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
            fun s(k: String) = m[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val version = s("version") ?: return null
            val file = s("file") ?: return null
            val sha = s("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } } ?: return null
            return DataManifest(version, file, sha, s("size")?.toLongOrNull() ?: 0, s("dbSize")?.toLongOrNull() ?: 0, s("complete") != "false")
        }

        /**
         * remote 가 current 보다 새 데이터인지. 버전 앞 8자리 = 날짜(앱 안 데이터 '20261013c', 서버 '202610200300').
         * 날짜가 더 늦으면 새것. 같은 날이면 둘 다 서버 버전(숫자 12자리)일 때만 시각으로 비교 (앱 안 데이터는 그날 손으로 넣은 게 우선).
         */
        fun isNewer(remote: String?, current: String?): Boolean {
            if (remote.isNullOrBlank()) return false
            if (current.isNullOrBlank()) return true
            val rd = remote.take(8).toLongOrNull() ?: return false
            val cd = current.take(8).toLongOrNull() ?: return true
            if (rd != cd) return rd > cd
            val rn = remote.toLongOrNull()
            val cn = current.toLongOrNull()
            return rn != null && cn != null && rn > cn
        }

        /** 안내 파일 주소 옆의 데이터 파일 주소 */
        fun fileUrl(manifestUrl: String, file: String) = manifestUrl.substringBeforeLast('/') + "/" + file
    }
}
