"""서울 신호등 받기 시험 (인터넷·키 없이): python pipeline/test_seoul_lights.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import fetch_seoul_lights as sl


def test_origin_of_the_projection_maps_to_its_center():
    # 가짜 동쪽 200000, 가짜 북쪽 600000 = 기준 위도 38°, 중앙 경선 127°
    lat, lng = sl.to_wgs84(200000.0, 600000.0)
    assert abs(lat - 38.0) < 1e-7 and abs(lng - 127.0) < 1e-7


def test_one_kilometer_moves_about_the_expected_degrees():
    lat, lng = sl.to_wgs84(201000.0, 601000.0)
    assert abs((lat - 38.0) * 110_900 - 1000) < 15          # 위도 1도 ≈ 110.9km (38° 부근)
    assert abs((lng - 127.0) * 87_800 - 1000) < 15          # 경도 1도 ≈ 87.8km


def test_real_rows_land_in_seoul():
    for x, y in ((195373.36937, 555507.48487), (208367.12612, 548520.45635), (202042.632454, 546457.615107)):
        lat, lng = sl.to_wgs84(x, y)
        assert 37.4 < lat < 37.7 and 126.8 < lng < 127.2, (lat, lng)


def test_point_skips_missing_or_out_of_seoul_rows():
    assert sl.point({"XCRD": "195373.36937", "YCRD": "555507.48487"}) is not None
    assert sl.point({"XCRD": "", "YCRD": "555507"}) is None
    assert sl.point({}) is None
    assert sl.point({"XCRD": "500000", "YCRD": "300000"}) is None      # 서울 밖


def test_response_helpers():
    body = {sl.SERVICE: {"list_total_count": 5, "row": [{"XCRD": "1"}]}}
    assert sl.total_of(body) == 5 and len(sl.rows_of(body)) == 1
    assert sl.total_of({}) == 0 and sl.rows_of({}) == []


if __name__ == "__main__":
    test_origin_of_the_projection_maps_to_its_center()
    test_one_kilometer_moves_about_the_expected_degrees()
    test_real_rows_land_in_seoul()
    test_point_skips_missing_or_out_of_seoul_rows()
    test_response_helpers()
    print("ok")
