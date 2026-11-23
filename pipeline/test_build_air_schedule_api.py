"""항공 시간표 API 변환 시험 (인터넷 없이): py pipeline/test_build_air_schedule_api.py"""
import datetime as dt
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_air_schedule_api as ba

OLD = "D\tKAL\tKE1201\tGMP\tCJU\t07:00\t08:10\t월화수목금토일\t2026-03-29\t2026-10-24\n" \
      "D\tKAL\tKE1203\tGMP\tCJU\t08:00\t09:15\t월화수목금토일\t2026-03-29\t2026-10-24\n" \
      "I\tAAR\tOZ1035\tHND\tGMP\t20:05\t22:25\t월화수목금토일\t2026-03-29\t2026-10-24\n"
AIRLINES = "AAR\t아시아나항공\nKAL\t대한항공\n"
AIRPORTS = "GMP\t김포\t한국\nCJU\t제주\t한국\nHND\t도쿄/하네다\t일본\n"


def item(**k):
    r = {"fid": "1", "fgenType": "I", "flightid": "KE1201", "st": "0705", "firstdate": "20261027", "lastdate": "20270327",
         "airline": "대한항공", "airlineCode": "KE", "depcitycode": "GMP", "depcity": "서울/김포", "arrvcitycode": "CJU", "arrvcity": "제주",
         **{f"yn{d}": "Y" if d in ("mon", "fri") else "N" for d in ("mon", "tue", "wed", "thu", "fri", "sat", "sun")}}
    r.update(k)
    return r


def test_build():
    today = dt.date(2026, 11, 23)
    lines, al, ap, stat = ba.build(
        [item(),                                                        # 같은 편명 비행시간 70분 → 08:15
         item(flightid="KE1999", st="1000"),                            # 편명은 처음이지만 같은 구간(김포→제주) 70분 → 11:10
         item(flightid="KE1", lastdate="20261024"),                     # 끝난 운항
         item(flightid="KE2", fgenType="D"),                            # 삭제된 항목
         item(flightid="WA101", airline="파라타항공", airlineCode="WA", depcitycode="CJU", depcity="제주", arrvcitycode="KUV", arrvcity="군산")],  # 비행시간 모름
        [item(flightid="OZ1035", airline="아시아나항공", airlineCode="OZ", st="2005", depcitycode="HND", depcity="도쿄/하네다", arrvcitycode="GMP",
              arrvcity="서울/김포", firstdate="20261025")],
        OLD, AIRLINES, AIRPORTS, today)
    assert "D\tKAL\tKE1201\tGMP\tCJU\t07:05\t08:15\t월금\t2026-10-27\t2027-03-27" in lines
    assert "D\tKAL\tKE1999\tGMP\tCJU\t10:00\t11:10\t월금\t2026-10-27\t2027-03-27" in lines
    assert "I\tAAR\tOZ1035\tHND\tGMP\t20:05\t22:25\t월금\t2026-10-25\t2027-03-27" in lines
    assert not any(f"\t{f}\t" in l for l in lines for f in ("KE1", "KE2", "WA101"))
    assert stat["D"] == 2 and stat["I"] == 1 and stat["D_no_duration"] == 1
    assert al == ["AAR\t아시아나항공", "KAL\t대한항공"]
    assert ap == AIRPORTS.strip().split("\n")                         # 예전 공항은 그대로, 순서도 그대로


def test_new_airline_and_airport():
    lines, al, ap, _ = ba.build([], [item(flightid="7C1", airline="제주항공", airlineCode="7C", st="0900", depcitycode="GMP", arrvcitycode="KIX",
                                          arrvcity="오사카/간사이")],
                                "I\tJJA\t7C1\tGMP\tKIX\t09:00\t10:50\t월\t2026-03-29\t2026-10-24\n", AIRLINES, AIRPORTS, dt.date(2026, 11, 23))
    assert lines == ["I\t7C\t7C1\tGMP\tKIX\t09:00\t10:50\t월금\t2026-10-27\t2027-03-27"]
    assert "7C\t제주항공" in al and ap[-1] == "KIX\t오사카/간사이\t"


def test_fetch_all_pages():
    calls = []

    def get(url):
        page = int(dict(x.split("=") for x in url.split("?")[1].split("&"))["pageNo"])
        calls.append(page)
        items = [{"n": page * 100 + i} for i in range(100 if page < 3 else 50)]
        return json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"totalCount": 250, "items": {"item": items}}}})
    assert len(ba.fetch_all("dom", "k", get)) == 250 and sorted(calls) == [1, 2, 3]


if __name__ == "__main__":
    for t in (test_build, test_new_airline_and_airport, test_fetch_all_pages):
        t(); print("PASS", t.__name__)
