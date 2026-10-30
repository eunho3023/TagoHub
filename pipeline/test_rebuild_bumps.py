"""과속방지턱만 새로 만들기 시험 (인터넷 없이): python pipeline/test_rebuild_bumps.py"""
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import rebuild_bumps as rb


def _write(path, text, enc):
    pathlib.Path(path).write_bytes(text.encode(enc))


def test_reads_both_header_styles_and_encodings():
    with tempfile.TemporaryDirectory() as d:
        national = pathlib.Path(d) / "national.csv"
        local = pathlib.Path(d) / "local.csv"
        _write(national, "도로명,WGS84위도,WGS84경도\n가파로,33.1,126.2\n좌표없음,,\n", "cp949")
        _write(local, "도로명,위도,경도\n계향로,35.8,127.6\n", "utf-8-sig")
        assert [(r[1], r[2]) for r in rb.read_bumps(national)] == [(33.1, 126.2)]
        got = rb.read_bumps(local)
        assert [(r[1], r[2], r[3]) for r in got] == [(35.8, 127.6, "계향로")]


def test_replace_keeps_other_kinds_and_drops_old_bumps():
    old = "bump,33.0,126.0,,옛길\nchild,37.5,127.0,,역삼초\nspeed,37.6,127.1,80,서초\n"
    new = "bump,35.80000,127.60000,,계향로\n"
    out = rb.replace_bumps(old, new)
    assert out.splitlines() == ["bump,35.80000,127.60000,,계향로", "child,37.5,127.0,,역삼초", "speed,37.6,127.1,80,서초"]


if __name__ == "__main__":
    test_reads_both_header_styles_and_encodings()
    test_replace_keeps_other_kinds_and_drops_old_bumps()
    print("ok")
