package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader

data class UticSafetyPoint(
    val id: String,
    val category: String,
    val description: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
)

/** pipeline/build_overlays.py 가 만든 점 목록: [[분류, id, 설명, 주소, 위도, 경도], ...] */
object UticSafetySnapshot {
    fun parse(json: String): List<UticSafetyPoint> {
        val rows = com.eunho.tagohub.core.MiniJson.parse(json) as? List<*> ?: throw IOException("UTIC 안전정보 저장본 형식이 올바르지 않습니다.")
        return rows.mapNotNull { r ->
            val f = r as? List<*> ?: return@mapNotNull null
            if (f.size < 6) return@mapNotNull null
            val lat = f[4]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = f[5]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val category = f[0]?.toString().orEmpty()
            UticSafetyPoint(
                id = "$category:${f[1]}",
                category = category,
                description = f[2]?.toString().orEmpty(),
                address = f[3]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                lat = lat,
                lng = lng,
            )
        }
    }
}

object UticSafetyRowParser {
    fun parse(
        category: String,
        row: Map<String, String>,
    ): UticSafetyPoint? {
        if (row["B"] != "Y") return null
        val lng = row["M"]?.toDoubleOrNull() ?: return null
        val lat = row["N"]?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val id = row["A"]?.takeIf(String::isNotBlank) ?: return null
        return UticSafetyPoint(
            id = "$category:$id",
            category = category,
            description = row["O"]?.takeIf(String::isNotBlank)
                ?: row["P"]?.takeIf(String::isNotBlank)
                ?: category,
            address = row["G"]?.takeIf(String::isNotBlank) ?: row["D"]?.takeIf(String::isNotBlank),
            lat = lat,
            lng = lng,
        )
    }
}

class UticSafetyRepository(
    private val cacheDir: File,
    private val key: String = BuildConfig.UTIC_API_KEY.trim(),
) {
    suspend fun safetyPoints(): List<UticSafetyPoint> {
        // 집 PC 가 엑셀 묶음을 미리 풀어 올려 둔 점 목록이 있으면 그것 (16MB 를 받아 폰에서 푸는 일을 안 함)
        OverlaySnapshot.get(SNAPSHOT, maxAgeSec = SNAPSHOT_MAX_AGE_SEC) { UticSafetySnapshot.parse(it) }?.let { return it }
        if (key.isBlank()) throw IOException("local.properties에 UTIC_API_KEY를 설정하세요.")
        val archive = File(cacheDir, "utic-safety-data.zip")
        val cached = archive.isFile && System.currentTimeMillis() - archive.lastModified() < CACHE_TTL_MS
        if (!cached) {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) throw IOException("UTIC 캐시 폴더를 만들지 못했습니다.")
            val request = Request.Builder().url(UticFeeds.all.first { it.title == "교통안전" }.url(key)).build()
            UticHttp.download(request, archive)
        }
        return withContext(Dispatchers.IO) {
            try {
                parseArchive(archive)
            } catch (e: IOException) {
                archive.delete()
                throw e
            }
        }
    }

    internal fun parseArchive(archive: File): List<UticSafetyPoint> {
        val output = ArrayList<UticSafetyPoint>()
        val extracted = ArrayList<File>()
        try {
            ZipFile(archive).use { outer ->
                val sheets = outer.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".xlsx", ignoreCase = true) }
                    .toList()
                if (sheets.isEmpty()) throw IOException("UTIC 안전정보 파일에 엑셀 자료가 없습니다.")
                for (sheet in sheets) {
                    val xlsx = File(cacheDir, "utic-safety-${sheet.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    extracted += xlsx
                    outer.getInputStream(sheet).use { input -> xlsx.outputStream().use { input.copyTo(it) } }
                    parseWorkbook(xlsx, sheet.name.substringAfterLast('/').removeSuffix(".xlsx"), output)
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("UTIC 교통안전 XLSX를 읽지 못했습니다: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            extracted.forEach(File::delete)
        }
        return output
    }

    private fun parseWorkbook(file: File, category: String, output: MutableList<UticSafetyPoint>) {
        ZipFile(file).use { workbook ->
            val sharedStrings = workbook.getEntry("xl/sharedStrings.xml")?.let { entry ->
                workbook.getInputStream(entry).use(::readSharedStrings)
            }.orEmpty()
            val sheet = workbook.getEntry("xl/worksheets/sheet1.xml")
                ?: throw IOException("UTIC 안전정보 시트가 없습니다: $category")
            workbook.getInputStream(sheet).use { stream ->
                xmlReader().apply {
                    contentHandler = SafetySheetHandler(category, sharedStrings, output)
                    parse(InputSource(stream))
                }
            }
        }
    }

    private fun readSharedStrings(source: java.io.InputStream): List<String> {
        val values = ArrayList<String>()
        xmlReader().apply {
            contentHandler = SharedStringsHandler(values)
            parse(InputSource(source))
        }
        return values
    }

    private fun xmlReader(): XMLReader =
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newSAXParser().xmlReader.apply {
            entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        }

    private class SharedStringsHandler(private val values: MutableList<String>) : DefaultHandler() {
        private var inItem = false
        private var inText = false
        private val value = StringBuilder()

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            when (element(localName, qName)) {
                "si" -> {
                    inItem = true
                    value.setLength(0)
                }
                "t" -> if (inItem) inText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) value.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
            when (element(localName, qName)) {
                "t" -> inText = false
                "si" -> {
                    values += value.toString()
                    inItem = false
                }
            }
        }
    }

    private class SafetySheetHandler(
        private val category: String,
        private val sharedStrings: List<String>,
        private val output: MutableList<UticSafetyPoint>,
    ) : DefaultHandler() {
        private var rowNumber = 0
        private var cellReference: String? = null
        private var cellType: String? = null
        private var currentCell: StringBuilder? = null
        private var inValue = false
        private var row = LinkedHashMap<String, String>()

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            when (element(localName, qName)) {
                "row" -> {
                    rowNumber = attributes.getValue("r")?.toIntOrNull() ?: rowNumber + 1
                    row = LinkedHashMap()
                }
                "c" -> {
                    cellReference = attributes.getValue("r")
                    cellType = attributes.getValue("t")
                    currentCell = StringBuilder()
                }
                "v", "t" -> if (currentCell != null) inValue = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue) currentCell?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
            when (element(localName, qName)) {
                "v", "t" -> inValue = false
                "c" -> {
                    val reference = cellReference
                    val raw = currentCell?.toString().orEmpty()
                    if (reference != null) {
                        val value = if (cellType == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                        row[column(reference)] = value
                    }
                    cellReference = null
                    currentCell = null
                }
                "row" -> if (rowNumber > 1) {
                    UticSafetyRowParser.parse(category, row)?.let(output::add)
                }
            }
        }

        private fun column(reference: String): String =
            reference.takeWhile(Char::isLetter)
    }

    private companion object {
        const val SNAPSHOT = "ov_utic_safety.json"
        /** 안전정보는 자주 안 바뀌어서 PC 가 12시간마다 새로 만듦 → 3일까지 인정 */
        const val SNAPSHOT_MAX_AGE_SEC = 3 * 24 * 3600L
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000

        fun element(localName: String, qName: String) = localName.ifBlank { qName.substringAfter(':') }
    }
}
