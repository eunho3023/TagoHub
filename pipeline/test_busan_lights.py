"""부산 신호등 파일 읽기 시험 (인터넷 없이): python pipeline/test_busan_lights.py"""
import pathlib
import struct
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import busan_lights_csv as bl


def make_dbf(fields, rows) -> bytes:
    """시험용 작은 .dbf 를 만듦: fields = [(이름, 길이)]"""
    rec_len = 1 + sum(ln for _, ln in fields)
    header_len = 32 + 32 * len(fields) + 1
    head = bytearray(32)
    head[0] = 0x03
    head[4:8] = struct.pack("<I", len(rows))
    head[8:10] = struct.pack("<H", header_len)
    head[10:12] = struct.pack("<H", rec_len)
    desc = b""
    for name, ln in fields:
        d = bytearray(32)
        d[0:len(name)] = name.encode("ascii")
        d[11] = ord("C")
        d[16] = ln
        desc += bytes(d)
    body = b""
    for row in rows:
        rec = b" "
        for (name, ln), v in zip(fields, row):
            rec += v.encode("cp949").ljust(ln, b" ")[:ln]
        body += rec
    return bytes(head) + desc + b"\x0d" + body


FIELDS = [("ins_place", 20), ("lng", 20), ("lat", 20)]


def test_reads_korean_text_and_coordinates():
    rows = bl.dbf_rows(make_dbf(FIELDS, [("벡스코역앞", "129.1352778", "35.1713754"), ("해강초등학교", "129.1423743", "35.1614738")]))
    assert [r["ins_place"] for r in rows] == ["벡스코역앞", "해강초등학교"]
    assert bl.points(rows) == [(35.1713754, 129.1352778), (35.1614738, 129.1423743)]


def test_skips_bad_or_out_of_korea_coordinates():
    rows = bl.dbf_rows(make_dbf(FIELDS, [("a", "", ""), ("b", "abc", "35.1"), ("c", "0", "0"), ("d", "129.0", "35.0")]))
    assert bl.points(rows) == [(35.0, 129.0)]


def test_deleted_rows_are_ignored():
    data = bytearray(make_dbf(FIELDS, [("a", "129.0", "35.0"), ("b", "129.1", "35.1")]))
    header_len = struct.unpack("<H", data[8:10])[0]
    data[header_len] = ord("*")                       # 첫 줄 지움 표시
    assert bl.points(bl.dbf_rows(bytes(data))) == [(35.1, 129.1)]


if __name__ == "__main__":
    test_reads_korean_text_and_coordinates()
    test_skips_bad_or_out_of_korea_coordinates()
    test_deleted_rows_are_ignored()
    print("ok")
