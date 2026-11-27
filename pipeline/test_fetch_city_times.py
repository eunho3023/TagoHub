import unittest

from fetch_city_times import clock_list, parse_cheonan_html, parse_jecheon


class CheonanTimesTest(unittest.TestCase):
    def test_clock_list(self):
        self.assertEqual(clock_list([" 06:00", "0615", "25:99", "06:00"]), [360, 375])

    def test_parse_sections(self):
        html = "<dl><dt>평일 출발시간</dt><dd>06:00</dd><dd>06:20</dd></dl><dl><dt>주말 출발시간</dt><dd>07:00</dd></dl><dl><dt>공휴일 출발시간</dt></dl>"
        t = parse_cheonan_html(html)
        self.assertEqual((t["weekday"], t["sat"], t["holi"]), ([360, 380], [420], [420]))
        self.assertIsNone(parse_cheonan_html("<html></html>"))


class JecheonTimesTest(unittest.TestCase):
    def test_parse(self):
        rows = [{"DEPARTURE_TIME": "07:25", "BUS_DAY_TYPE": "WEEKDAY"}, {"DEPARTURE_TIME": "13:10", "BUS_DAY_TYPE": "WEEKDAY"},
                {"DEPARTURE_TIME": "09:00", "BUS_DAY_TYPE": "HOLIDAY"}, {"DEPARTURE_TIME": "08:00", "BUS_DAY_TYPE": "VAC_WEEKDAY"},
                {"DEPARTURE_TIME": "18:40"}]
        t = parse_jecheon(rows)
        self.assertEqual((t["weekday"], t["sat"], t["holi"], t["exact"]), ([445, 790, 1120], [540], [540], True))
        # 평일만 다니는 노선: 휴일 칸은 비어 있음 (운행 없음)
        self.assertEqual(parse_jecheon([{"DEPARTURE_TIME": "06:50", "BUS_DAY_TYPE": "WEEKDAY"}])["holi"], [])
        # 방학 시각만 있으면 그것
        self.assertEqual(parse_jecheon([{"DEPARTURE_TIME": "06:50", "BUS_DAY_TYPE": "VAC_WEEKDAY"}])["weekday"], [410])
        self.assertIsNone(parse_jecheon([]))


if __name__ == "__main__":
    unittest.main()
