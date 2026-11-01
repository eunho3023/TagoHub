"""한국지역정보개발원 교차로 위치 받기 시험 (인터넷·키 없이): python pipeline/test_klid_lights.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import fetch_klid_lights as kl


def test_items_may_be_a_list_a_single_object_or_missing():
    assert kl.items_of({"body": {"items": {"item": [{"a": 1}, {"a": 2}]}}}) == [{"a": 1}, {"a": 2}]
    assert kl.items_of({"body": {"items": {"item": {"a": 1}}}}) == [{"a": 1}]
    assert kl.items_of({"body": {}}) == [] and kl.items_of({}) == []


def test_total_count():
    assert kl.total_of({"body": {"totalCount": "1058"}}) == 1058
    assert kl.total_of({}) == 0


def test_point_keeps_korean_coordinates_only():
    assert kl.point({"mapCtptIntLat": "33.4996", "mapCtptIntLot": "126.5312"}) == (33.4996, 126.5312)
    assert kl.point({"mapCtptIntLat": "", "mapCtptIntLot": "126.5"}) is None
    assert kl.point({"mapCtptIntLat": "0", "mapCtptIntLot": "0"}) is None
    assert kl.point({}) is None


if __name__ == "__main__":
    test_items_may_be_a_list_a_single_object_or_missing()
    test_total_count()
    test_point_keeps_korean_coordinates_only()
    print("ok")
