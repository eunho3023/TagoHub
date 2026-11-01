"""신호등 위치 파일 만들기 시험 (인터넷 없이): python pipeline/test_traffic_lights.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_traffic_lights as tl

ROWS = [
    ["", "", "", ""],
    ["시도명", "위도", "경도", "잔여시간표시기유무"],
    ["서울", "37.50001", "127.00001", "N"],
    ["서울", "37.50002", "127.00003", "Y"],
    ["부산", "35.1", "129.0", "N"],
    ["이상", "0", "0", "N"],                   # 한국 밖 좌표 → 뺌
    ["이상", "abc", "127.0", "Y"],             # 숫자 아님 → 뺌
]


def test_points_filters_bad_coordinates():
    pts = tl.points(ROWS)
    assert [(round(a, 1), round(b, 1)) for a, b, _ in pts] == [(37.5, 127.0), (37.5, 127.0), (35.1, 129.0)]


def test_nearby_lights_of_one_intersection_become_one_point():
    # 사거리 하나: 네 모퉁이의 신호등이 위도·경도로 ±0.0002(약 20m) 흩어져 있음
    crossing = [(37.5 + dy, 127.0 + dx, False) for dy in (-0.0002, 0.0002) for dx in (-0.0002, 0.0002)]
    crossing.append((37.5001, 127.0, True))
    merged = tl.merge(crossing)
    assert len(merged) == 1
    la, ln, timer = merged[0]
    assert abs(la - 37.5) < 0.0003 and abs(ln - 127.0) < 0.0003
    assert timer is True                       # 하나라도 잔여시간표시기가 있으면 있음


def test_merged_point_is_a_real_light_not_the_average():
    # ㄱ자로 놓인 세 신호등: 평균은 (37.50010, 127.00010)인데 그 자리엔 신호등이 없음 → 가장 가까운 실제 신호등을 씀
    lights = [(37.5, 127.0, False), (37.5, 127.0003, False), (37.5003, 127.0, False)]
    merged = tl.merge(lights)
    assert len(merged) == 1
    assert (merged[0][0], merged[0][1]) in {(la, ln) for la, ln, _ in lights}


def test_far_apart_intersections_stay_separate_and_sorted_by_latitude():
    pts = [(37.5, 127.0, False), (37.5, 127.002, True), (35.1, 129.0, False)]   # 0.002° ≈ 180m
    merged = tl.merge(pts)
    assert [(round(a, 3), round(b, 3), t) for a, b, t in merged] == [(35.1, 129.0, False), (37.5, 127.0, False), (37.5, 127.002, True)]


def test_merge_is_repeatable_regardless_of_input_order():
    pts = [(37.5 + i * 0.00005, 127.0, bool(i % 2)) for i in range(10)]
    assert tl.merge(pts) == tl.merge(list(reversed(pts)))


def test_text_format():
    assert tl.to_text([(35.1, 129.0, False), (37.5, 127.0, True)]) == "35.10000,129.00000,0\n37.50000,127.00000,1\n"


if __name__ == "__main__":
    test_points_filters_bad_coordinates()
    test_nearby_lights_of_one_intersection_become_one_point()
    test_merged_point_is_a_real_light_not_the_average()
    test_far_apart_intersections_stay_separate_and_sorted_by_latitude()
    test_merge_is_repeatable_regardless_of_input_order()
    test_text_format()
    print("ok")
