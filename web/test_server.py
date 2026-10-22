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


class Routing(unittest.TestCase):
    def test_unknown_api_path_is_a_404_style_error(self):
        with self.assertRaises(s.ApiError) as ctx:
            s.route("/api/nope", {})
        self.assertEqual(ctx.exception.status, 404)

    def test_missing_coordinates_are_a_400(self):
        with self.assertRaises(s.ApiError) as ctx:
            s.route("/api/stops/near", {})
        self.assertEqual(ctx.exception.status, 400)


if __name__ == "__main__":
    unittest.main()
