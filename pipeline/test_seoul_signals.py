"""서울 신호 교차로 표 만들기 시험 (인터넷 없이): python pipeline/test_seoul_signals.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_seoul_signals as bs


def row(i, name, lat, lng):
    return {"ITST_ID": i, "ITST_NM": name, "MAP_CTPT_INT_LAT": lat, "MAP_CTPT_INT_LOT": lng}


def test_keeps_valid_rows_sorted_by_numeric_id():
    items, dropped = bs.clean([row("101", "수서역", "37.4873221", "127.101742"), row("1", "이촌역앞", "37.5231971", "126.9713254"), row("20", "a", "37.5", "127.0")])
    assert [i[0] for i in items] == ["1", "20", "101"] and dropped == 0


def test_drops_bad_coordinates_duplicates_and_empty_ids():
    items, dropped = bs.clean([
        row("1", "ok", "37.5", "127.0"),
        row("1", "dup", "37.51", "127.01"),          # 같은 ID
        row("2", "문서샘플", "37.968756", "127.547359"),   # 서울 밖
        row("3", "빈좌표", "", ""),
        row("", "id없음", "37.5", "127.0"),
    ])
    assert [i[0] for i in items] == ["1"] and dropped == 4


def test_blank_name_becomes_default_and_tabs_are_removed():
    items, _ = bs.clean([row("1", "", "37.5", "127.0"), row("2", "가\t나  다", "37.5", "127.0")])
    assert items[0][1] == "교차로" and items[1][1] == "가 나 다"


def test_text_format():
    assert bs.to_text([("1", "이촌역앞", 37.5231971, 126.9713254)]) == "1\t이촌역앞\t37.523197\t126.971325\n"


if __name__ == "__main__":
    test_keeps_valid_rows_sorted_by_numeric_id()
    test_drops_bad_coordinates_duplicates_and_empty_ids()
    test_blank_name_becomes_default_and_tabs_are_removed()
    test_text_format()
    print("ok")
