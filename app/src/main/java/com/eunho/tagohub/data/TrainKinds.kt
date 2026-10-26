package com.eunho.tagohub.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 열차번호 → 열차 이름(KTX·ITX-새마을·무궁화호 …) 기억.
 * 앱에 든 한국철도공사 시간표 파일에는 열차 종류가 비어 있어서, 국토교통부(TAGO) 조회로 받은 이름을 번호별로 기억해 두었다가
 * 시간표로만 채운 열차(당일 등)에 붙인다. 같은 번호의 열차는 매일 같은 종류라서 한 번 알면 계속 쓸 수 있다.
 */
object TrainKinds {
    /** 이름을 모를 때 보여 주는 말 */
    const val UNKNOWN = "열차"

    private val map = ConcurrentHashMap<String, String>()
    private var file: File? = null

    /** 앱 시작 때: 저장해 둔 것을 읽음 */
    fun init(dir: File) {
        file = File(dir, "train_kinds.tsv")
        runCatching {
            file?.takeIf { it.exists() }?.forEachLine { line ->
                val f = line.split('\t')
                if (f.size >= 2 && f[0].isNotBlank() && f[1].isNotBlank()) map[f[0]] = f[1]
            }
        }
    }

    fun get(number: String?): String? = number?.let { map[it] }

    /** 조회 결과에서 번호↔이름을 배움. 바뀐 게 있으면 저장 */
    fun learn(trips: List<Trip>) {
        var changed = false
        for (t in trips) {
            val no = t.number ?: continue
            val kind = t.kind.trim()
            if (kind.isEmpty() || kind == UNKNOWN) continue
            if (map.put(no, kind) != kind) changed = true
        }
        if (changed) save()
    }

    internal fun clear() = map.clear()

    private fun save() {
        val f = file ?: return
        runCatching { f.writeText(map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" } + "\n") }
    }
}
