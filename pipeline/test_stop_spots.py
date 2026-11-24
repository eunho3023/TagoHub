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
    h, sp = run([("A", "가", 37.0, 127.0, "1", "11"), ("B", "나", 37.001, 127.0, "2", "11"), ("SEBZ", "폐지", 37.002, 127.0, "3", "11"),
                 ("CNB1", "천내2리", 36.3, 127.0, None, "34350")], {"R": ["A", "B"]})
    # 서울 정류장만 숨김 (다른 지역은 DB 노선 자료가 덜 차 TAGO 에 노선이 있는 정류장이 많았음)
    assert h == {"SEBZ"} and sp == {}


def test_keep_gyeonggi_and_twins_without_routes():
    # 노선 자료가 덜 찬 경기(GGB)·교통안전공단(TSB) 정류장, 번호가 같은 경기 정류장이 옆에 있는 서울 정류장은 노선이 없어도 남김
    stops = [("A", "가", 37.0, 127.0, "1", "11"), ("B", "나", 37.001, 127.0, "2", "11"),
             ("GGB1", "명동입구B", 37.0100, 127.0, "2143", "11"), ("SEB1", "명동입구B", 37.0101, 127.0, "02143", "11"),
             ("TSB1", "시골", 37.02, 127.0, "9", "TS1"), ("SEB2", "폐지", 37.03, 127.0, "02925", "11")]
    h, sp = run(stops, {"R": ["A", "B"]})
    assert h == {"SEB2"}
    assert sp.get("GGB1") == sp.get("SEB1")   # 번호 같은 둘은 한 점


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


def test_unknown_direction_same_name_close_merged():
    # 노선 자료 없는 교통안전공단 정류장 둘: 같은 이름 5m → 하나, 12m(길 건너일 수 있음) → 따로
    stops = [("A", "가", 37.0, 127.0, "1", "11"), ("B", "나", 37.001, 127.0, "2", "11"),
             ("TSB1", "원천", 37.01, 127.0, "541171", "TS1"), ("TSB2", "원천", 37.01 + 5 * D, 127.0, "541172", "TS1"),
             ("TSB3", "고천", 37.02, 127.0, "541620", "TS1"), ("TSB4", "고천", 37.02 + 12 * D, 127.0, "541692", "TS1")]
    h, sp = run(stops, {"R": ["A", "B"]})
    assert sp.get("TSB1") == sp.get("TSB2") and sp.get("TSB1")
    assert "TSB3" not in sp and "TSB4" not in sp


def test_slightly_skewed_same_name_close_merged():
    # 같은 이름 4m, 노선 방향이 90도쯤 어긋남(두 자료): 하나. 반대 방향(180도)·12m 는 따로
    stops = [("ASB1", "봉암", 37.0, 127.0, "1", "34040"), ("CNB1", "봉암", 37.0 + 4 * D, 127.0, "2", "34040"),
             ("P1", "앞", 36.999, 127.0, "", "34040"), ("P2", "뒤", 37.001, 127.0, "", "34040"),
             ("Q1", "옆", 37.0, 126.999, "", "34040"), ("Q2", "옆2", 37.0, 127.001, "", "34040"),
             ("X1", "건너", 37.05, 127.0, "", "34040"), ("X2", "건너", 37.05 + 6 * D, 127.0, "", "34040"),
             ("Y1", "y", 37.049, 127.0, "", "34040"), ("Y2", "y2", 37.051, 127.0, "", "34040")]
    # ASB1 은 남→북, CNB1 은 서→동 (90도), X1 은 남→북, X2 는 북→남 (반대)
    h, sp = run(stops, {"R1": ["P1", "ASB1", "P2"], "R2": ["Q1", "CNB1", "Q2"], "R3": ["Y1", "X1", "Y2"], "R4": ["Y2", "X2", "Y1"]})
    assert sp.get("ASB1") == sp.get("CNB1") and sp.get("ASB1")
    assert "X1" not in sp and "X2" not in sp


if __name__ == "__main__":
    for t in (test_no_route_hidden, test_keep_gyeonggi_and_twins_without_routes, test_other_city_same_dir_far_merged_opposite_not, test_same_city_crowded_merged_unless_same_route, test_same_number_leading_zero_merged, test_unknown_direction_same_name_close_merged, test_slightly_skewed_same_name_close_merged):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
