"""안전구간(단속카메라·어린이 보호구역) 점 목록 만들기 시험 (인터넷 없이): python pipeline/test_zones.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_overlays as bo


def test_camera_kinds_and_limits():
    items = [
        {"regltSe": "과속", "latitude": "37.5", "longitude": "127.0", "lmttVe": "80", "itlpc": "서초대로 1"},
        {"regltSe": "신호위반", "latitude": "37.51", "longitude": "127.01", "lmttVe": ""},
        {"regltSe": "구간단속", "latitude": "37.52", "longitude": "127.02", "lmttVe": "100"},
        {"regltSe": "버스전용차로", "latitude": "37.53", "longitude": "127.03"},
        {"regltSe": "과속", "latitude": "0", "longitude": "0"},           # 좌표 이상 → 뺌
        {"regltSe": "과속", "latitude": "abc", "longitude": "127.0"},     # 숫자 아님 → 뺌
    ]
    rows = bo.zone_rows(items, [])
    assert [r[0] for r in rows] == ["speed", "signal", "section", "cam"]
    assert rows[0] == ["speed", "서초대로 1", 37.5, 127.0, 80]
    assert rows[1][4] is None            # 제한속도 없음
    assert rows[2][4] == 100


def test_child_zones_skip_cancelled():
    child = [
        {"trgetFcltyNm": "역삼초등학교", "latitude": "37.50", "longitude": "127.03", "chldPrtnZonYn": "Y"},
        {"trgetFcltyNm": "해제된곳", "latitude": "37.51", "longitude": "127.04", "chldPrtnZonYn": "N"},
        {"trgetFcltyNm": "표시없음", "latitude": "37.52", "longitude": "127.05"},
    ]
    rows = bo.zone_rows([], child)
    assert [r[1] for r in rows] == ["역삼초등학교", "표시없음"]
    assert all(r[0] == "child" and r[4] is None for r in rows)


if __name__ == "__main__":
    test_camera_kinds_and_limits()
    test_child_zones_skip_cancelled()
    print("OK")
