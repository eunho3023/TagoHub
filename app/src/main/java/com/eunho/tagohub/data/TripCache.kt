package com.eunho.tagohub.data

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 마지막으로 조회한 열차 시간표를 폰에 저장해 두었다가, 같은 조회를 하면 받는 동안 먼저 보여준다
 * (열자마자 비어 있지 않게). 새로 받으면 바꿔 치우고, 못 받으면 저장본을 그대로 둔다.
 * 파일 하나 = 조회 하나, 오래된 것부터 [MAX_FILES] 개까지만 남긴다.
 */
object TripCache {
    private const val MAX_FILES = 40
    /** 저장본이 이보다 오래되면 쓰지 않음 (같은 날짜의 시간표는 잘 안 바뀌지만 며칠 지나면 새로 받는 게 낫다) */
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    @Volatile private var dir: File? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    fun init(root: File) {
        dir = File(root, "trip_cache").apply { mkdirs() }
    }

    fun key(dep: String, arr: String, date: LocalDate, grade: String?): String = "$dep-$arr-$date-${grade ?: "all"}"

    private fun file(key: String): File? = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".tsv") }

    fun get(key: String): List<Trip>? = runCatching {
        val f = file(key)?.takeIf { it.exists() } ?: return null
        if (clock() - f.lastModified() > MAX_AGE_MS) return null
        decode(f.readText(Charsets.UTF_8)).takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun put(key: String, trips: List<Trip>) {
        if (trips.isEmpty()) return
        val f = file(key) ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(encode(trips), Charsets.UTF_8)
            tmp.renameTo(f)
            f.setLastModified(clock())
            dir?.listFiles { x -> x.extension == "tsv" }?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
    }

    private fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ')

    /** 한 줄 = 열차 한 편: 종별, 번호, 출발역, 도착역, 출발, 도착, 요금(라벨:원,…), 출발역ID, 도착역ID */
    internal fun encode(trips: List<Trip>): String = trips.joinToString("\n") { t ->
        listOf(
            clean(t.kind), clean(t.number), clean(t.depName), clean(t.arrName), t.dep?.toString() ?: "", t.arr?.toString() ?: "",
            t.prices.joinToString(",") { (label, won) -> clean(label).replace(",", " ").replace(":", " ") + ":" + won },
            clean(t.depId), clean(t.arrId),
        ).joinToString("\t")
    }

    internal fun decode(text: String): List<Trip> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 9) return@mapNotNull null
        Trip(
            kind = f[0],
            number = f[1].ifEmpty { null },
            depName = f[2],
            arrName = f[3],
            dep = f[4].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            arr = f[5].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            prices = f[6].split(',').mapNotNull { p ->
                val label = p.substringBeforeLast(':', "")
                val won = p.substringAfterLast(':', "").toIntOrNull()
                if (label.isEmpty() || won == null) null else label to won
            },
            depId = f[7].ifEmpty { null },
            arrId = f[8].ifEmpty { null },
        )
    }.toList()
}
