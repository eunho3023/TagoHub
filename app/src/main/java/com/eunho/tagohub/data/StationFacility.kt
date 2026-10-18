package com.eunho.tagohub.data

data class StationFacility(
    val operator: String,
    val line: String,
    val station: String,
    val category: String,
    val detail: String,
)

class StationFacilityIndex private constructor(
    private val byStation: Map<String, List<StationFacility>>,
) {
    fun forStation(group: StationGroup): List<StationFacility> {
        val names = group.stations.map { it.norm }.toSet() + StationName.norm(group.name)
        return names.flatMap { byStation[it].orEmpty() }
            .distinct()
            .filter { facility ->
                group.stations.any { station ->
                    sameLine(facility.line, station.line) &&
                        sameOperator(facility.operator, station.operator)
                }
            }
    }

    companion object {
        fun parseTsv(text: String): StationFacilityIndex {
            val records = text.lineSequence()
                .drop(1)
                .mapNotNull { row ->
                    val columns = row.split('\t')
                    if (columns.size < 5) return@mapNotNull null
                    val record = StationFacility(
                        operator = columns[0].trim(),
                        line = columns[1].trim(),
                        station = columns[2].trim(),
                        category = columns[3].trim(),
                        detail = columns[4].trim(),
                    )
                    if (record.station.isBlank() || record.line.isBlank() || record.detail.isBlank()) {
                        null
                    } else {
                        StationName.norm(record.station) to record
                    }
                }
                .toList()
            return StationFacilityIndex(records.groupBy({ it.first }, { it.second }))
        }

        private fun sameOperator(a: String, b: String): Boolean = operatorKey(a) == operatorKey(b)

        private fun sameLine(a: String, b: String): Boolean {
            val punctuation = Regex("""[^\p{L}\p{N}]""")
            return StationName.sameLine(a.replace(punctuation, ""), b.replace(punctuation, ""))
        }

        private fun operatorKey(value: String): String {
            val key = value.replace(" ", "").replace("(주)", "")
            return when {
                key.contains("코레일") || key.contains("한국철도") -> "한국철도공사"
                key.contains("서울메트로") || key.contains("서울도시철도") || key.contains("서울교통") ->
                    "서울교통공사"
                key.contains("부산교통") -> "부산교통공사"
                key.contains("대구도시철도") || key.contains("대구교통") -> "대구도시철도공사"
                key.contains("대전도시철도") || key.contains("대전교통") -> "대전교통공사"
                key.contains("광주도시철도") || key.contains("광주교통") -> "광주교통공사"
                key.contains("인천교통") -> "인천교통공사"
                else -> key
            }
        }
    }
}
