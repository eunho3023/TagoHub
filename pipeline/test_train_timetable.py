"""열차 시간표 만들기의 순수한 부분 시험 (인터넷·키 없이): python pipeline/test_train_timetable.py"""
import datetime as dt
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_train_timetable as b  # noqa: E402


def row(no, sn, stn, arr, dep):
    return {"trn_no": no, "trn_run_sn": str(sn), "stn_nm": stn, "trn_arvl_dt": arr, "trn_dptre_dt": dep}


DAY = dt.date(2026, 10, 15)


class PickDays(unittest.TestCase):
    def test_one_weekday_one_saturday_one_sunday_counting_back_from_yesterday(self):
        days = dict((tag, d) for d, tag in b.pick_days(dt.date(2026, 10, 23)))   # 오늘 금요일
        self.assertEqual(days["평일"], dt.date(2026, 10, 22))   # 어제 목요일
        self.assertEqual(days["토"], dt.date(2026, 10, 17))
        self.assertEqual(days["일"], dt.date(2026, 10, 18))

    def test_mondays_and_fridays_are_not_used_as_the_weekday_sample(self):
        # 오늘 화요일: 어제 월요일은 건너뛰고 지난 목요일
        days = dict((tag, d) for d, tag in b.pick_days(dt.date(2026, 10, 20)))
        self.assertEqual(days["평일"], dt.date(2026, 10, 15))


class BuildLines(unittest.TestCase):
    ROWS = [
        row("00001", 2, "광명", "2026-10-15 05:30:00.0", "2026-10-15 05:32:00.0"),
        row("00001", 1, "서울", None, "2026-10-15 05:14:00.0"),
        row("00001", 3, "부산", "2026-10-15 07:53:00.0", None),
    ]

    def test_stops_are_ordered_by_run_number_and_use_departure_except_the_terminal(self):
        self.assertEqual(b.train_stops(self.ROWS, DAY), {"1": [("서울", 314), ("광명", 332), ("부산", 473)]})

    def test_a_train_running_past_midnight_gets_minutes_above_1440(self):
        rows = [
            row("00099", 1, "부산", None, "2026-10-15 23:50:00.0"),
            row("00099", 2, "울산", "2026-10-16 00:40:00.0", "2026-10-16 00:42:00.0"),
            row("00099", 3, "포항", "2026-10-16 01:30:00.0", None),
        ]
        stops = b.train_stops(rows, DAY)["99"]
        self.assertEqual([m for _, m in stops], [23 * 60 + 50, 24 * 60 + 42, 25 * 60 + 30])

    def test_identical_patterns_across_days_collapse_into_one_line_but_real_differences_stay(self):
        sat = [dict(r, trn_dptre_dt=(r["trn_dptre_dt"] or "").replace("10-15", "10-17"), trn_arvl_dt=(r["trn_arvl_dt"] or "").replace("10-15", "10-17")) for r in self.ROWS]
        sat = [{k: (None if v == "" else v) for k, v in r.items()} for r in sat]
        lines = b.build_lines([(DAY, "평일", self.ROWS), (dt.date(2026, 10, 17), "토", sat)])
        self.assertEqual(len(lines), 1)                       # 같은 정차·같은 시각 → 한 줄
        self.assertTrue(lines[0].startswith("1\t\t실적평일\t서울@314;광명@332;부산@473"))
        later = [dict(r, trn_dptre_dt=r["trn_dptre_dt"].replace("05:14", "06:30") if r["trn_dptre_dt"] else None) for r in sat]
        two = b.build_lines([(DAY, "평일", self.ROWS), (dt.date(2026, 10, 17), "토", later)])
        self.assertEqual(len(two), 2)                         # 출발이 1시간 넘게 다르면 별개 편

    def test_fetch_day_reads_every_page_until_the_total(self):
        pages = {1: [{"trn_no": "1"}] * 2, 2: [{"trn_no": "2"}]}

        def fake_get(url):
            n = int(url.split("pageNo=")[1].split("&")[0])
            return json.dumps({"response": {"body": {"totalCount": 3, "items": {"item": pages.get(n, [])}}}})

        self.assertEqual(len(b.fetch_day("KEY", DAY, get=fake_get)), 3)

    def test_fetch_day_with_no_records_is_empty(self):
        empty = json.dumps({"response": {"body": {"totalCount": 0, "items": ""}}})
        self.assertEqual(b.fetch_day("KEY", DAY, get=lambda url: empty), [])


if __name__ == "__main__":
    unittest.main()
