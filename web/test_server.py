"""웹 서버의 순수한 부분 시험 (인터넷·키 없이): python web/test_server.py"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server as s  # noqa: E402


class Numbers(unittest.TestCase):
    def test_to_int_and_float(self):
        self.assertEqual(s.to_int("12.0"), 12)
        self.assertEqual(s.to_int(" 7 "), 7)
        self.assertIsNone(s.to_int(None))
        self.assertIsNone(s.to_int("abc"))
        self.assertAlmostEqual(s.to_float("35.5"), 35.5)
        self.assertIsNone(s.to_float(""))

    def test_haversine_one_degree_of_latitude(self):
        self.assertAlmostEqual(s.haversine(35.0, 127.0, 36.0, 127.0), 111195, delta=200)


class Secrets(unittest.TestCase):
    def test_keys_never_appear_in_error_text(self):
        with mock.patch.dict(os.environ, {"TAGO_SERVICE_KEY": "SECRETKEY123+/=", "KAKAO_REST_KEY": "", "SEOUL_SUBWAY_KEY": ""}):
            text = "오류 SECRETKEY123+/= 그리고 SECRETKEY123%2B%2F%3D"
            self.assertNotIn("SECRETKEY123", s.redact(text))


class TagoParsing(unittest.TestCase):
    def test_single_item_dict_becomes_a_list_and_empty_items_become_empty(self):
        with mock.patch.object(s, "tago", return_value={"items": {"item": {"routeno": "5"}}}):
            self.assertEqual(s.tago_items("x"), [{"routeno": "5"}])
        with mock.patch.object(s, "tago", return_value={"items": ""}):
            self.assertEqual(s.tago_items("x"), [])
        with mock.patch.object(s, "tago", return_value={"items": {"item": [{"a": 1}, {"a": 2}]}}):
            self.assertEqual(len(s.tago_items("x")), 2)

    def test_arrivals_are_sorted_soonest_first_and_unknown_last(self):
        items = [
            {"routeid": "A", "routeno": "5", "routetp": "일반버스", "arrtime": "600", "arrprevstationcnt": "4"},
            {"routeid": "B", "routeno": "9", "routetp": "일반버스", "arrtime": "90", "arrprevstationcnt": "1"},
            {"routeid": "C", "routeno": "7", "routetp": "일반버스"},
        ]
        with mock.patch.object(s, "tago_cities", return_value={"35010"}), mock.patch.object(s, "tago_items", return_value=items):
            r = s.bus_arrivals("35010", "N1")
        self.assertEqual([a["routeNo"] for a in r["arrivals"]], ["9", "5", "7"])
        self.assertEqual(r["arrivals"][0]["prevStops"], 1)
        self.assertIsNone(r["arrivals"][2]["sec"])

    def test_unsupported_city_says_so_instead_of_failing(self):
        with mock.patch.object(s, "tago_cities", return_value={"35010"}):
            r = s.bus_arrivals("11", "N1")
        self.assertFalse(r["supported"])
        self.assertIn("지원", r["message"] + "지원")


class Subway(unittest.TestCase):
    def test_search_strips_the_station_suffix_and_matches_by_name(self):
        with mock.patch.object(s, "_stations", [{"name": "시청", "lines": ["1호선", "2호선"]}, {"name": "서울역", "lines": ["1호선"]}]):
            self.assertEqual([g["name"] for g in s.subway_search("시청역")], ["시청"])
            self.assertEqual(s.subway_search(""), [])


class Kakao(unittest.TestCase):
    def test_car_route_is_flattened_for_the_map(self):
        fake = {"routes": [{"result_code": 0, "summary": {"distance": 1000, "duration": 120, "fare": {"toll": 0, "taxi": 5000}},
                            "sections": [{"roads": [{"name": "a", "traffic_state": 4, "distance": 1000, "duration": 120, "vertexes": [127.0, 37.0, 127.1, 37.1]}],
                                          "guides": [{"name": "x", "guidance": "우회전", "distance": 500, "type": 2, "x": 127.0, "y": 37.0}]}]}]}
        with mock.patch.object(s, "kakao_rest", return_value=fake):
            r = s.car_route(37.0, 127.0, 37.1, 127.1)
        self.assertTrue(r["found"])
        self.assertEqual(r["points"], [[37.0, 127.0], [37.1, 127.1]])  # (경도,위도) → [위도,경도]
        self.assertEqual(r["summary"]["taxi"], 5000)
        self.assertEqual(r["guides"][0]["guidance"], "우회전")

    def test_car_route_failure_keeps_the_kakao_message(self):
        with mock.patch.object(s, "kakao_rest", return_value={"routes": [{"result_code": 105, "result_msg": "도로 유고"}]}):
            r = s.car_route(1, 2, 3, 4)
        self.assertFalse(r["found"])
        self.assertEqual(r["message"], "도로 유고")

    def test_option_parameters(self):
        self.assertEqual(s.OPTIONS["notoll"], {"priority": "RECOMMEND", "avoid": "toll"})
        self.assertEqual(s.OPTIONS["shortest"], {"priority": "DISTANCE"})


def trip(no, dep, arr, fare=1000, kind="KTX", dn="A", an="B"):
    return {"kind": kind, "no": no, "dep": dep, "arr": arr, "fare": fare, "depName": dn, "arrName": an, "depId": dn, "arrId": an}


class TrainTransfer(unittest.TestCase):
    def test_transfer_needs_the_minimum_wait_and_takes_the_earliest_arrival(self):
        first = [trip("1", "202610230800", "202610230900")]
        second = [trip("2", "202610230905", "202610231000"),   # 5분 뒤 → 못 탐
                  trip("3", "202610230910", "202610231100"),   # 10분 뒤 → 탐
                  trip("4", "202610230930", "202610231030")]   # 더 늦게 출발해도 더 빨리 도착
        r = s.combine_transfers(first, second, "대전")
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["legs"][1]["no"], "4")
        self.assertEqual(r[0]["wait"], 30)
        self.assertEqual(r[0]["fare"], 2000)
        self.assertEqual((r[0]["dep"], r[0]["arr"], r[0]["hub"]), ("202610230800", "202610231030", "대전"))

    def test_no_connection_means_no_itinerary(self):
        r = s.combine_transfers([trip("1", "202610230800", "202610231200")], [trip("2", "202610230900", "202610231000")], "대전")
        self.assertEqual(r, [])

    def test_transfers_skip_hubs_with_no_trains_and_dedupe_the_same_first_train(self):
        stations = [{"id": "S", "name": "강릉"}, {"id": "E", "name": "목포"}, {"id": "H1", "name": "서울"}, {"id": "H2", "name": "대전"}]
        legs = {("S", "H1"): [trip("1", "202610230800", "202610230900")], ("H1", "E"): [trip("2", "202610231000", "202610231300")],
                ("S", "H2"): [trip("1", "202610230800", "202610230830")], ("H2", "E"): [trip("3", "202610231000", "202610231200")]}
        with mock.patch.object(s, "train_stations", return_value=stations), \
                mock.patch.object(s, "train_trips_cached", side_effect=lambda a, b, d: legs.get((a, b), [])):
            r = s.train_transfers("S", "E", "2026-10-23")
        self.assertEqual(len(r), 1)                       # 같은 첫 열차는 하나만
        self.assertEqual(r[0]["hub"], "대전")             # 더 빨리 도착하는 환승역
        self.assertEqual(r[0]["legs"][1]["no"], "3")

    def test_minutes_parsing_rejects_garbage(self):
        self.assertIsNone(s.to_minutes(""))
        self.assertEqual(s.to_minutes("202610230910") - s.to_minutes("202610230800"), 70)


class TrainStops(unittest.TestCase):
    def test_stops_are_the_stations_the_same_train_reaches_before_the_destination_in_time_order(self):
        t = trip("101", "202610230800", "202610231000", dn="서울", an="부산")
        scan = {"101": [("202610230930", "동대구"), ("202610230845", "대전"), ("202610231000", "부산"), ("202610231100", "마산")],
                "202": [("202610230850", "수원")]}
        with mock.patch.object(s, "train_trips_cached", return_value=[t]), mock.patch.object(s, "stop_scan", return_value=scan):
            r = s.train_stops("D", "A", "2026-10-23", "101")
        self.assertEqual([x["name"] for x in r["stops"]], ["서울", "대전", "동대구", "부산"])   # 부산 이후(마산)·다른 열차(수원) 제외

    def test_unknown_train_number_explains_itself(self):
        with mock.patch.object(s, "train_trips_cached", return_value=[]):
            r = s.train_stops("D", "A", "2026-10-23", "999")
        self.assertEqual(r["stops"], [])
        self.assertTrue(r["message"])


class Routing(unittest.TestCase):
    def test_unknown_api_path_is_a_404_style_error(self):
        with self.assertRaises(s.ApiError) as ctx:
            s.route("/api/nope", {})
        self.assertEqual(ctx.exception.status, 404)

    def test_train_transfer_and_stop_routes_exist(self):
        with mock.patch.object(s, "train_transfers", return_value=[]), mock.patch.object(s, "train_stops", return_value={"stops": []}):
            self.assertEqual(s.route("/api/train/transfers", {"dep": ["a"], "arr": ["b"]}), {"itineraries": []})
            self.assertEqual(s.route("/api/train/stops", {"dep": ["a"], "arr": ["b"], "no": ["1"]}), {"stops": []})

    def test_missing_coordinates_are_a_400(self):
        with self.assertRaises(s.ApiError) as ctx:
            s.route("/api/stops/near", {})
        self.assertEqual(ctx.exception.status, 400)


if __name__ == "__main__":
    unittest.main()
