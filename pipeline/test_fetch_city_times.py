import unittest

from fetch_city_times import clock_list, parse_cheonan_html


class CheonanTimesTest(unittest.TestCase):
    def test_clock_list(self):
        self.assertEqual(clock_list([" 06:00", "0615", "25:99", "06:00"]), [360, 375])

    def test_parse_sections(self):
        html = "<dl><dt>평일 출발시간</dt><dd>06:00</dd><dd>06:20</dd></dl><dl><dt>주말 출발시간</dt><dd>07:00</dd></dl><dl><dt>공휴일 출발시간</dt></dl>"
        t = parse_cheonan_html(html)
        self.assertEqual((t["weekday"], t["sat"], t["holi"]), ([360, 380], [420], [420]))
        self.assertIsNone(parse_cheonan_html("<html></html>"))


if __name__ == "__main__":
    unittest.main()
