"""웹 자료 조각 만들기 시험 (인터넷 없이): python pipeline/test_build_web_data.py"""
import json, pathlib, sqlite3, sys, tempfile
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_web_data as bw


def test_bucket_and_tile_match_app():
    # 앱 공용 코드 WebData 와 같은 값이어야 함 (shared/src/commonTest 의 WebDataTest)
    assert bw.bucket("BSB5200111000") == 992 and bw.bucket("JJB306000104") == 871 and bw.bucket("정류장") == 521
    assert bw.tile(35.8235, 127.1478) == "358_1271"


def test_build_small_db():
    with tempfile.TemporaryDirectory() as d:
        d = pathlib.Path(d)
        db = sqlite3.connect(d / "s.db")
        db.executescript("""
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT); INSERT INTO meta VALUES('version','202611040000');
        CREATE TABLE cities(code TEXT PRIMARY KEY, name TEXT); INSERT INTO cities VALUES('35010','전주');
        CREATE TABLE city_alias(code TEXT PRIMARY KEY, tago TEXT); INSERT INTO city_alias VALUES('TS305','35010');
        CREATE TABLE stops(id TEXT PRIMARY KEY, name TEXT, lat REAL, lng REAL, no TEXT, city TEXT);
        INSERT INTO stops VALUES('S1','홍산로전북도교육청.동암고',35.8235,127.1478,'30490','35010'),('S2','우전초교',35.8241,127.1490,'30485','35010'),('S3','폐지정류장',35.8242,127.1491,'30486','35010');
        CREATE TABLE routes(id TEXT PRIMARY KEY, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT);
        INSERT INTO routes VALUES('R1','104','송천동','평화동','S1','S2','35010','전주','일반','tago');
        CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT, name TEXT, lat REAL, lng REAL, updown TEXT);
        INSERT INTO route_stops VALUES('R1',1,'S1','',NULL,NULL,'0'),('R1',2,'S2','',NULL,NULL,'0'),('R1',3,NULL,'새 정류장',35.83,127.15,'1');
        CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER);
        INSERT INTO route_info VALUES('R1','06:00','23:00',15,20,25);
        """)
        db.commit(); db.close()
        assets = d / "assets"; assets.mkdir()
        (assets / "train_timetable.tsv").write_text("a\tb", encoding="utf-8")
        (assets / "bus_stops.db").write_bytes(b"x")
        out = d / "out"
        m = bw.build(d / "s.db", out, assets)
        assert m["tiles"] == 1 and m["dbVersion"] == "202611040000" and m["files"] == 1
        t = json.loads((out / "tiles/358_1271.json").read_text(encoding="utf-8"))
        assert ["S1", "홍산로전북도교육청.동암고", 35.8235, 127.1478, "30490", "35010", ["R1"]] in t
        # 낱말마다: "홍산로전북도교육청" 과 "동암고" 둘 다로 찾음
        assert any(r[0] == "S1" for r in json.loads((out / f"names/{ord('동'):x}.json").read_text(encoding="utf-8")))
        r = json.loads((out / f"routes/{bw.bucket('R1')}.json").read_text(encoding="utf-8"))["R1"]
        assert r["no"] == "104" and r["info"] == ["06:00", "23:00", 15, 20, 25]
        assert r["stops"] == [[1, "S1", "홍산로전북도교육청.동암고", 35.8235, 127.1478, "0"], [2, "S2", "우전초교", 35.8241, 127.149, "0"],
                              [3, None, "새 정류장", 35.83, 127.15, "1"]]
        # 노선이 안 서는 S3 은 검색에서 빠지고(같은 지역 S1·S2 는 노선 있음) 칸 파일에는 남음
        assert not (out / f"names/{ord('폐'):x}.json").exists()
        assert any(r[0] == "S3" for r in t) and m["hiddenStops"] == 1
        assert json.loads((out / f"routesum/{bw.bucket('R1')}.json").read_text(encoding="utf-8"))["R1"] == ["104", "송천동", "평화동", "일반", "06:00", "23:00", 15, 20, 25]
        c = json.loads((out / "cities.json").read_text(encoding="utf-8"))
        assert c["names"] == {"35010": "전주"}
        assert json.loads((out / "routes_by_no.json").read_text(encoding="utf-8")) == {"35010": {"104": ["R1"]}}
        assert c["alias"] == {"TS305": "35010"}
        assert not (out / "files/bus_stops.db").exists()


if __name__ == "__main__":
    for t in (test_bucket_and_tile_match_app, test_build_small_db):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
