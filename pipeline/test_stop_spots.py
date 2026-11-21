"""정류장 정리 시험 (인터넷 없이): python pipeline/test_stop_spots.py"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import stop_spots as ss

D = 1 / 111_000  # 1m 쯤 (위도)


def run(stops, routes):
    """routes: {노선: [정류장 id 순서]} (좌표는 정류장 것)"""
    at = {s[0]: s for s in stops}
    rows = [(r, i, sid, at[sid][2], at[sid][3]) for r, l in routes.items() for i, sid in enumerate(l)]
    served, by = {}, {}
    for r, l in routes.items():
        for sid in l:
            by.setdefault(sid, set()).add(r)
    served = {k: len(v) for k, v in by.items()}
    return ss.spots(stops, served, ss.headings(rows), by)


def test_no_route_hidden():
    h, sp = run([("A", "가", 37.0, 127.0, "1", "11"), ("B", "나", 37.001, 127.0, "2", "11"), ("Z", "폐지", 37.002, 127.0, "3", "11")],
                {"R": ["A", "B"]})
    assert h == {"Z"} and sp == {}


def test_other_city_same_dir_far_merged_opposite_not():
    # 북쪽으로 가는 노선: 시군 11 의 "시장" 과 시군 22 의 "시장" 이 같은 방면 100m 떨어짐 → 하나로
    # 남쪽으로 가는 시군 22 의 "시장" (길 건너, 20m) → 따로
    lat0 = 37.0
    stops = [("P", "앞", lat0 - 500 * D, 127.0, None, "11"), ("A", "시장", lat0, 127.0, "1", "11"), ("N", "뒤", lat0 + 500 * D, 127.0, None, "11"),
             ("P2", "앞", lat0 - 500 * D, 127.0001, None, "22"), ("B", "시장", lat0 + 100 * D, 127.0001, "9", "22"), ("N2", "뒤", lat0 + 500 * D, 127.0001, None, "22"),
             ("C", "시장", lat0 + 10 * D, 127.0002, "8", "22"), ("P3", "앞", lat0 - 500 * D, 127.0002, None, "22")]
    h, sp = run(stops, {"R1": ["P", "A", "N"], "R2": ["P2", "B", "N2"], "R3": ["N2", "C", "P3"], "R4": ["P", "A", "N"]})
    assert sp.get("A") == sp.get("B") == "A"   # 노선 많은 A 가 대표
    assert "C" not in sp


def test_same_city_crowded_merged_unless_same_route():
    lat0 = 37.0
    stops = [("P", "앞", lat0 - 400 * D, 127.0, None, "11"), ("A", "역", lat0, 127.0, "1", "11"), ("B", "역", lat0 + 15 * D, 127.0, "2", "11"),
             ("N", "뒤", lat0 + 400 * D, 127.0, None, "11")]
    h, sp = run(stops, {"R1": ["P", "A", "N"], "R2": ["P", "B", "N"]})
    assert sp.get("A") == sp.get("B")
    # 한 노선이 A·B 에 차례로 서면 다른 정류장
    h, sp = run(stops, {"R1": ["P", "A", "B", "N"]})
    assert "A" not in sp and "B" not in sp


def test_same_number_leading_zero_merged():
    # 서울 SEB "02001" 과 경기 버스 GGB "2001" (이름 다름, 같은 시군 11, 20m) → 하나. 번호가 다르면 따로
    lat0 = 37.0
    stops = [("P", "앞", lat0 - 400 * D, 127.0, None, "11"), ("S", "명동성당.남대문세무서", lat0, 127.0, "02001", "11"),
             ("G", "남대문세무서(중)", lat0 + 20 * D, 127.0, "2001", "11"), ("X", "다른곳", lat0 + 10 * D, 127.0, "02009", "11"),
             ("N", "뒤", lat0 + 400 * D, 127.0, None, "11")]
    h, sp = run(stops, {"R1": ["P", "S", "N"], "R2": ["P", "S", "N"], "R3": ["P", "G", "N"], "R4": ["P", "X", "N"]})
    assert sp.get("S") == sp.get("G") == "S"
    assert "X" not in sp


if __name__ == "__main__":
    for t in (test_no_route_hidden, test_other_city_same_dir_far_merged_opposite_not, test_same_city_crowded_merged_unless_same_route, test_same_number_leading_zero_merged):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
