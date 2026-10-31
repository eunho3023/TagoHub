"""신호등 위치 파일 만들기 시험 (인터넷 없이): python pipeline/test_traffic_lights.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_traffic_lights as tl

ROWS = [
    ["", "", "", ""],
    ["시도명", "위도", "경도", "잔여시간표시기유무"],
    ["서울", "37.50001", "127.00001", "N"],
    ["서울", "37.50002", "127.00003", "Y"],   # 같은 자리(11m 안) → 하나로, 잔여시간표시기는 하나라도 있으면 있음
    ["부산", "35.1", "129.0", "N"],
    ["이상", "0", "0", "N"],                   # 한국 밖 좌표 → 뺌
    ["이상", "abc", "127.0", "Y"],             # 숫자 아님 → 뺌
]


def test_points_filters_bad_coordinates():
    pts = tl.points(ROWS)
    assert [(round(a, 1), round(b, 1)) for a, b, _ in pts] == [(37.5, 127.0), (37.5, 127.0), (35.1, 129.0)]


def test_merge_joins_nearby_lights_and_sorts_by_latitude():
    merged = tl.merge(tl.points(ROWS))
    assert [(la, ln, t) for la, ln, t in merged] == [(35.1, 129.0, False), (37.5, 127.0, True)]


def test_text_format():
    assert tl.to_text([(35.1, 129.0, False), (37.5, 127.0, True)]) == "35.1000,129.0000,0\n37.5000,127.0000,1\n"


if __name__ == "__main__":
    test_points_filters_bad_coordinates()
    test_merge_joins_nearby_lights_and_sorts_by_latitude()
    test_text_format()
    print("ok")
