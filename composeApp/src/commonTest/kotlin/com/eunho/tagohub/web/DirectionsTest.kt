package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionsTest {
    @Test fun parsesPlaces() {
        val p = DirectionsRepo.parsePlaces(
            """{"documents":[{"place_name":"대전역","road_address_name":"대전 동구 중앙로 215","x":"127.434","y":"36.332","category_name":"교통,수송 > 기차역"},{"place_name":"x"}]}""",
        )
        assertEquals(1, p.size)
        assertEquals("기차역", p[0].category)
        assertEquals(36.332, p[0].lat)
    }

    @Test fun parsesTransit() {
        val (status, routes) = DirectionsRepo.parseTransit(
            """{"status":"OK","routes":[{"properties":{"totalDistance":5200,"totalTime":1800,"transfers":1,"fare":{"value":1500}},
               "steps":[{"properties":{"type":"WALKING","distance":200,"time":180},"path":{"points":[[127.1,36.1],[127.2,36.2]]}},
                        {"properties":{"type":"BUS","distance":4000,"time":1200,"stops":[{"name":"A"},{"name":"B"},{"name":"C"}],"vehicles":[{"name":"102"},{"name":"105"}]}}]}]}""",
        )
        assertEquals("OK", status)
        val r = routes.single()
        assertEquals(1500, r.fare)
        assertEquals(36.1 to 127.1, r.steps[0].points[0])
        assertEquals("걷기 3분", DirectionsRepo.label(r.steps[0]))
        assertEquals("버스 102", DirectionsRepo.label(r.steps[1]))
        assertEquals("도착지 근처에 대중교통이 없어요", DirectionsRepo.message("ENDNODES_NULL"))
        assertEquals("NO_RESULTS", DirectionsRepo.parseTransit("""{"status":"NO_RESULTS"}""").first)
    }
}
