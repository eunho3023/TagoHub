"""UTIC 교차로 위치 받기 시험 (인터넷·키 없이): python pipeline/test_utic_cross.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import fetch_utic_cross as uc


def test_integer_coordinates_become_degrees():
    # 실제 응답 모양: 경도 X 1266360530 → 126.636053, 위도 Y 374643680 → 37.464368 (인천 신광4거리)
    lat, lng = uc.to_degrees(1266360530, 374643680)
    assert abs(lat - 37.464368) < 1e-9 and abs(lng - 126.636053) < 1e-9


def test_missing_or_out_of_korea_coordinates_are_dropped():
    assert uc.to_degrees(None, 374643680) is None
    assert uc.to_degrees("abc", "1") is None
    assert uc.to_degrees(0, 0) is None


def test_rows_use_column_names_and_skip_blank_lines():
    rows = [
        ("REGION_CD", "INT_NO", "INT_NM", "X_COORD", "Y_COORD", "UPD_DTIME", "NODE_ID", None),
        ("L02", 1001, "신광4거리", 1266360530, 374643680, None, 1610000900, None),
        (None, None, None, None, None, None, None, None),
        ("L02", 1002, "좌표없음", None, None, None, 1, None),
    ]
    assert uc.points_from_rows(rows) == [(37.464368, 126.636053)]


if __name__ == "__main__":
    test_integer_coordinates_become_degrees()
    test_missing_or_out_of_korea_coordinates_are_dropped()
    test_rows_use_column_names_and_skip_blank_lines()
    print("ok")
