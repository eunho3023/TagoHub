"""수집 프로그램 시험 (가짜 TAGO·경기 응답, 인터넷 없이): python -m pytest pipeline 또는 python pipeline/test_build_data.py"""
import gzip, hashlib, json, pathlib, sqlite3, sys, tempfile
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import build_data as bd

BASE = bd.BASE_DB
db0 = sqlite3.connect(BASE)
BUSAN = [r[0] for r in db0.execute("SELECT id FROM stops WHERE city = '21' LIMIT 3")]
db0.close()


def resp(items, total=None):
    body = {"items": {"item": items} if items else ""}
    if total is not None:
        body["totalCount"] = total
    return json.dumps({"response": {"header": {"resultCode": "00"}, "body": body}}, ensure_ascii=False)


class FakeFetcher(bd.Fetcher):
    def __init__(self, limit_after=None, full_list=True):
        self.calls = 0
        self.limit_after = limit_after
        self.full_list = full_list   # 노선번호 없이 부르면 전체 목록을 주는지 (False 면 빈 응답 → 검색어로)
        self.urls = []

    def get(self, url, params, cache_file, max_age):
        self.calls += 1
        if self.limit_after and self.calls > self.limit_after:
            raise bd.Limit()
        self.urls.append((url.rsplit("/", 1)[-1], dict(params)))
        if url.endswith("getRouteNoList") and "routeNo" not in params:
            if not self.full_list:
                return resp([])
            # 전체 목록이 두 쪽으로 나뉘어 옴 (totalCount 2)
            if params["pageNo"] == 1:
                return resp({"routeid": "BSB5200111000", "routeno": "1001", "routetp": "급행버스", "startnodenm": "중앙동", "endnodenm": "해운대"}, total=2)
            return resp({"routeid": "BSB5291105000", "routeno": "5", "routetp": "일반버스", "startnodenm": "영도", "endnodenm": "서면"}, total=2)
        if url.endswith("getRouteNoList"):
            if params["routeNo"] == "1":   # 항목 하나 = 객체
                return resp({"routeid": "BSB5200111000", "routeno": "1001", "routetp": "급행버스", "startnodenm": "중앙동", "endnodenm": "해운대"})
            if params["routeNo"] == "5":   # 여러 개 = 배열
                return resp([{"routeid": "BSB5200111000", "routeno": "1001"}, {"routeid": "BSB5291105000", "routeno": "5", "routetp": "일반버스",
                             "startnodenm": "영도", "endnodenm": "서면"}])
            return resp([])
        if url.endswith("getRouteAcctoThrghSttnList"):
            if params["routeId"] == "BSB5200111000":
                if params["pageNo"] == 1:   # 정류장이 두 쪽으로 나뉘어 옴 (totalCount 3)
                    return resp([{"nodeid": BUSAN[1], "nodenm": "둘", "nodeord": 2, "updowncd": 0, "gpslati": 35.1, "gpslong": 129.0},
                                 {"nodeid": BUSAN[0], "nodenm": "하나", "nodeord": 1, "updowncd": 0, "gpslati": 35.1, "gpslong": 129.0}], total=3)
                return resp({"nodeid": "BSB_NEW", "nodenm": "새 정류장", "nodeord": 3, "updowncd": 1, "gpslati": 35.2, "gpslong": 129.1}, total=3)
            return resp({"nodeid": BUSAN[2], "nodenm": "셋", "nodeord": 1, "updowncd": 0})
        if url.endswith("getRouteInfoIem"):
            return resp({"routeid": params["routeId"], "startvehicletime": "0530", "endvehicletime": 2310, "intervaltime": 12, "intervalsattime": 15, "intervalsuntime": 20})
        if url == bd.GBIS:
            return json.dumps({"response": {"msgBody": {"busRouteList": [{"routeId": 216000070, "routeName": 1200, "startStationName": "원시역1번출구",
                                                                        "endStationName": "수원역10번출구.역전시장"}]}}}) if params["keyword"] == "1" else "{}"
        raise AssertionError(url)


def test_keyword_fallback_when_full_list_empty():
    fx = FakeFetcher(full_list=False)
    tago, complete = bd.fetch_tago(fx, [["21", "부산광역시"]])
    assert complete and set(tago["21"]["routes"]) == {"BSB5200111000", "BSB5291105000"}
    kw = [p for op, p in fx.urls if op == "getRouteNoList" and "routeNo" in p]
    assert len(kw) == len(bd.KEYWORDS)                              # 전체 목록이 비면 검색어 15개로
    assert tago["21"]["routes"]["BSB5200111000"]["route"]["startnodenm"] == "중앙동"   # 여러 검색어 결과를 합쳐도 기점 유지


def test_full_build():
    fx = FakeFetcher()
    tago, complete = bd.fetch_tago(fx, [["21", "부산광역시"]])
    assert complete and set(tago["21"]["routes"]) == {"BSB5200111000", "BSB5291105000"}
    lists = [p for op, p in fx.urls if op == "getRouteNoList"]
    assert len(lists) == 2 and all("routeNo" not in p for p in lists)   # 노선번호 없이 전체 목록 두 쪽, 검색어는 안 씀
    assert len(tago["21"]["routes"]["BSB5200111000"]["stops"]) == 3     # 정류장 두 쪽 모두
    gbis = bd.fetch_gbis(fx)
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "bus_stops.db"
        added, stops = bd.write_db(BASE, out, tago, gbis, "202610200300")
        assert (added, stops) == (2, 4)
        db = sqlite3.connect(out)
        r = db.execute("SELECT no, start_name, end_name, city, region, rtype, realtime, start_node, end_node FROM routes WHERE id='BSB5200111000'").fetchone()
        assert r == ("1001", "중앙동", "해운대", "21", "부산", "급행버스", "tago", BUSAN[0], "BSB_NEW"), r
        rs = db.execute("SELECT seq, stop_id, name, lat, updown FROM route_stops WHERE route_id='BSB5200111000' ORDER BY seq").fetchall()
        assert rs[0] == (1, BUSAN[0], "", None, "0")            # 앱 정류장이면 이름·좌표는 정류장 표에서
        assert rs[2] == (3, "BSB_NEW", "새 정류장", 35.2, "1")     # 앱에 없는 정류장은 이름·좌표 그대로
        # 앱에 있는 정류장은 이름을 노선 자료의 지금 이름으로 (정류장 표는 예전 자료라 바뀐 이름이 그대로였음)
        assert db.execute("SELECT name FROM stops WHERE id=?", (BUSAN[0],)).fetchone()[0] == "하나"
        assert db.execute("SELECT * FROM route_info WHERE id='BSB5200111000'").fetchone() == ("BSB5200111000", "05:30", "23:10", 12, 15, 20)
        assert db.execute("SELECT no, start_name FROM gbis_routes WHERE id='GGB216000070'").fetchone() == ("1200", "원시역1번출구")  # 707-1 → 1200
        assert db.execute("SELECT v FROM meta WHERE k='version'").fetchone()[0] == "202610200300"
        assert db.execute("SELECT COUNT(*) FROM timetable WHERE city='38030'").fetchone()[0] == 3585   # 손으로 넣은 진주 시간표 그대로
        db.close()
        m = bd.package(out, "202610200300", complete, added)
        assert m["file"] == "bus_stops.db.gz"                       # Release 에 같은 이름으로 덮어 올림
        assert (pathlib.Path(d) / "manifest.json").exists()
        gz = pathlib.Path(d) / m["file"]
        assert m["sha256"] == hashlib.sha256(gz.read_bytes()).hexdigest()
        assert gzip.decompress(gz.read_bytes())[:16] == b"SQLite format 3\x00"
        assert json.loads((pathlib.Path(d) / "manifest.json").read_text(encoding="utf-8"))["complete"] is True


def test_daily_limit_partial():
    fx = FakeFetcher(limit_after=3)     # 노선 목록(2쪽) + 정류장 1쪽 까지 받고 한도
    tago, complete = bd.fetch_tago(fx, [["21", "부산광역시"]])
    assert complete is False
    assert len(tago["21"]["routes"]) == 2


def test_key_error_stops_immediately():
    """실제로 겪은 응답: 403 + SERVICE_KEY_IS_NOT_REGISTERED_ERROR (코드 30) → 재시도 없이 바로 KeyError_"""
    body = '{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}'
    assert bd.key_error(body) == "SERVICE_KEY_IS_NOT_REGISTERED"

    class R:
        status_code = 403
        content = body.encode()

    class S:
        n = 0
        def get(self, *a, **k):
            S.n += 1
            return R()

    fx = bd.Fetcher.__new__(bd.Fetcher)
    import threading
    fx.key, fx.s, fx.calls, fx.lock = "k", S(), 0, threading.Lock()
    try:
        fx.get(bd.TAGO + "/getRouteNoList", {}, "tago/_t.json", 0)
        raise AssertionError("키 오류인데 통과")
    except bd.KeyError_:
        pass
    assert S.n == 1                                   # 세 번 다시 시도하지 않음


def test_real_tago_route_list():
    """실제 TAGO 응답 (대전 routeNo=5, 2026-10-14 브라우저): 포함 검색, routeno·막차는 숫자, 첫차는 글자"""
    text = (pathlib.Path(__file__).parent / "real_tago_routes_daejeon_5.json").read_text(encoding="utf-8")
    its = bd.items(text)
    assert [str(x["routeno"]) for x in its] == ["75", "105", "115", "315", "501", "511"]   # '5' 가 들어간 번호 모두
    assert its[0]["routeid"] == "DJB30300035" and its[0]["routetp"] == "외곽버스"
    assert bd.clock(its[0]["startvehicletime"]) == "07:20" and bd.clock(its[0]["endvehicletime"]) == "22:10"   # "0720" 글자 / 2210 숫자
    # 노선 정보(getRouteInfoIem)를 못 받아도 노선 목록의 첫차·막차로 채움
    tago = {"25": {"name": "대전광역시", "routes": {x["routeid"]: {"route": x, "stops": [], "info": None} for x in its}}}
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "bus_stops.db"
        bd.write_db(BASE, out, tago, [], "202610140300")
        db = sqlite3.connect(out)
        assert db.execute("SELECT no, start_name, rtype, realtime, region FROM routes WHERE id='DJB30300035'").fetchone() == ("75", "세종고속시외버스터미널", "외곽버스", "tago", "대전")
        assert db.execute("SELECT first, last FROM route_info WHERE id='DJB30300035'").fetchone() == ("07:20", "22:10")
        db.close()


def test_only_normal_responses_cached():
    """resultCode 00 이 아닌 응답(예: 03 NODATA_ERROR)은 저장하지 않음 → 다음 실행에서 다시 받음"""
    import threading
    bad = '{"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}'
    good = '{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":"","totalCount":0}}}'

    class R:
        def __init__(self, t): self.status_code, self.content = 200, t.encode()

    class S:
        def __init__(self, t): self.t = t
        def get(self, *a, **k): return R(self.t)

    with tempfile.TemporaryDirectory() as d:
        old = bd.CACHE
        bd.CACHE = pathlib.Path(d)
        try:
            for text, name, saved in ((bad, "bad.json", False), (good, "good.json", True)):
                fx = bd.Fetcher.__new__(bd.Fetcher)
                fx.key, fx.s, fx.calls, fx.lock, fx.last = "k", S(text), 0, threading.Lock(), ""
                assert fx.get(bd.TAGO + "/x", {}, name, 0) == text
                assert (pathlib.Path(d) / name).exists() is saved, name
                assert fx.last == text                          # 0개일 때 로그에 보여줄 마지막 응답
        finally:
            bd.CACHE = old


def test_busy_server_waits_and_retries():
    """실제로 받은 바쁨 응답 두 가지 뒤에 정상 응답 → 기다렸다 다시 받아 정상 응답을 돌려주고 저장"""
    import threading
    session_full = '{"response":{"header":{"resultCode":99,"resultMsg":"가용한 세션이 존재하지 않습니다. (30\\/30)"},"body":""}}'
    http_err = '{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"HTTP_ERROR","returnAuthMsg":"HTTP 에러","returnReasonCode":"04"}}}'
    ok = resp({"routeid": "X1", "routeno": "1"})
    assert bd.busy(session_full) and bd.busy(http_err) and not bd.busy(ok)
    seq = [session_full, http_err, ok]

    class R:
        def __init__(self, t): self.status_code, self.content = 200, t.encode()

    class S:
        n = 0
        def get(self, *a, **k):
            S.n += 1
            return R(seq[S.n - 1])

    waits = []
    fx = bd.Fetcher.__new__(bd.Fetcher)
    fx.key, fx.s, fx.calls, fx.lock, fx.pause_until, fx.busy_hits = "k", S(), 0, threading.Lock(), 0.0, 0
    fx.sleep = lambda sec: waits.append(round(sec))
    with tempfile.TemporaryDirectory() as d:
        old = bd.CACHE
        bd.CACHE = pathlib.Path(d)
        try:
            t = fx.get(bd.TAGO + "/getRouteNoList", {}, "tago/x.json", 0)
            assert t == ok and S.n == 3 and fx.busy_hits == 2
            assert (pathlib.Path(d) / "tago/x.json").read_text(encoding="utf-8") == ok      # 정상 응답만 저장
            assert any(w >= 4 for w in waits) and any(w >= 14 for w in waits)           # 5초, 15초 쉬고
        finally:
            bd.CACHE = old


def test_kotsa_timetable_real():
    """실제 응답 (교통안전공단 버스 노선 및 시간표, page 1): 태백 10·10-2·1·11번"""
    text = (pathlib.Path(__file__).parent / "real_kotsa_timetable_p1.json").read_text(encoding="utf-8")
    rows = bd.kotsa_rows(text)
    assert len(rows) == 10 and bd.kotsa_total(text) == 10563
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "bus_stops.db"
        bd.write_db(BASE, out, {}, [], "202610140300", rows)
        db = sqlite3.connect(out)
        assert db.execute("SELECT COUNT(*) FROM route_schedule").fetchone()[0] == 10
        # 태백 1번(254000003): 평일 46회 06:30~22:10, 배차 평일 10·토 10·공휴일 10분
        assert db.execute("SELECT trips, first, last, min_int FROM route_schedule WHERE id='TSB254000003' AND day='weekday'").fetchone() == (46, "06:30", "22:10", 10)
        assert db.execute("SELECT first, last, interval, int_sat, int_sun FROM route_info WHERE id='TSB254000003'").fetchone() == ("06:30", "22:10", 10, 10, 10)
        # 하루 1번 다니는 10번(254000001): 배차간격 0 → 비움
        assert db.execute("SELECT first, interval FROM route_info WHERE id='TSB254000001'").fetchone() == ("07:20", None)
        db.close()
    # 비교: 같은 노선의 TAGO 첫차·막차
    tago = {"TSB254000003": {"routeid": "TSB254000003", "startvehicletime": "0630", "endvehicletime": 2210},
            "TSB254000005": {"routeid": "TSB254000005", "startvehicletime": "0740", "endvehicletime": 2100},   # 막차 다름 (교통안전공단 21:10)
            "TSB999": {"routeid": "TSB999"}}
    r = bd.compare(rows, tago)
    assert r["sameRoutes"] == 2 and r["firstMatch"] == 100.0 and r["lastMatch"] == 50.0 and r["tagoOnly"] == 1 and r["kotsaOnly"] == 2


def test_tsb_stops_written():
    """교통안전공단 노선 정류장 순서를 받아 넣으면 그 노선 행은 그대로, 정류장 순서만 생김"""
    db0 = sqlite3.connect(BASE)
    rid, city = db0.execute("SELECT id, city FROM routes WHERE id LIKE 'TSB%' AND city NOT LIKE 'TS%' LIMIT 1").fetchone()
    sid = db0.execute("SELECT id FROM stops WHERE city = ? LIMIT 1", (city,)).fetchone()[0]
    db0.close()
    stops = [{"nodeid": sid, "nodenm": "가", "nodeord": 1, "updowncd": 0}, {"nodeid": "NEW1", "nodenm": "새 정류장", "nodeord": 2, "gpslati": 37.1, "gpslong": 128.9}]
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "bus_stops.db"
        bd.write_db(BASE, out, {}, [], "202610140300", (), {rid: stops})
        db = sqlite3.connect(out)
        assert db.execute("SELECT seq, stop_id, name FROM route_stops WHERE route_id = ? ORDER BY seq", (rid,)).fetchall() == [(1, sid, ""), (2, "NEW1", "새 정류장")]
        assert db.execute("SELECT COUNT(*) FROM routes WHERE id = ?", (rid,)).fetchone()[0] == 1
        db.close()


def test_incomplete_stops_refetched():
    """정류장 순서가 빠져 보이면(저장된 앞쪽만 있는 등) 저장본 없이 한 번 더 받아 더 많은 쪽을 씀"""
    def st(n):
        return {"nodeid": f"N{n}", "nodenm": str(n), "nodeord": n}

    class F(FakeFetcher):
        def get(self, url, params, cache_file, max_age):
            self.calls += 1
            self.urls.append((max_age, params["pageNo"]))
            if max_age > 0:   # 저장본: 1쪽만, totalCount 6
                return resp([st(1), st(2), st(3)], total=6) if params["pageNo"] == 1 else None
            return resp([st(n) for n in range(1, 7)], total=6)
    f = F()
    got = bd.route_stops(f, "21", "R1")
    assert [s["nodeord"] for s in got] == [1, 2, 3, 4, 5, 6]
    assert (0, 1) in f.urls
    assert bd.stops_incomplete([st(1), st(2), st(5), st(6)]) and not bd.stops_incomplete([st(1), st(2), st(3)])
    assert not bd.stops_incomplete([st(1), st(2), st(1001), st(1002)])   # 방향마다 따로 매긴 번호


def test_helpers():
    assert bd.clock("0530") == "05:30" and bd.clock(530) == "05:30" and bd.clock(2310) == "23:10" and bd.clock(None) is None
    assert bd.region_name("경상남도 김해시") == "김해" and bd.region_name("부산광역시") == "부산" and bd.region_name("충청북도 청주시") == "청주"
    assert bd.items('{"response":{"body":{"items":""}}}') == []
    try:
        bd.items('{"response":{"header":{"resultCode":"22","resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}}')
        raise AssertionError("한도인데 통과")
    except bd.Limit:
        pass


def test_prune_unserved():
    """노선 없는 정류장 지우기: 노선 자료가 거의 다 있는 지역은 표로, 일부만 있는 지역은 TAGO 경유노선 0개인 곳만, 전주는 API 로 모은 정류장 기준, 수집이 덜 끝나면 안 지움"""
    bsb = [f"BSB{i}" for i in range(20)]
    def make():
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE stops(id TEXT PRIMARY KEY, name TEXT)")
        db.execute("CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT)")
        ids = bsb + ["BSB_OLD", "GGB1", "GGB2", "GGB_OLD", "GGB_USED", "TSB_OLD", "GYB_OLD", "JUB1", "JUB2", "JUB3", "JUB4", "JUB_OLD"]
        db.executemany("INSERT INTO stops VALUES(?, ?)", [(i, i) for i in ids])
        db.executemany("INSERT INTO route_stops VALUES('R', 1, ?)", [(i,) for i in bsb + ["GGB1", "GGB2"]])
        return db
    left = lambda db: {r[0] for r in db.execute("SELECT id FROM stops")}
    db = make()
    assert bd.prune_unserved(db, complete=False) == 0 and "BSB_OLD" in left(db)        # 수집이 덜 끝나면 그대로
    served = {k: "" for k in ["JUB1", "JUB2", "JUB3", "JUB4"]}
    counts = {"GGB_OLD": 0, "GGB_USED": 3, "TSB_OLD": 2, "GYB_OLD": 0}
    # BSB 는 20/21 이 표에 있어 표로 지움, 경기는 TAGO 0개인 곳만, 교통안전공단은 TAGO 에 노선이 있어 그대로, 경산은 TAGO 답을 못 믿는 지역이라 그대로
    assert bd.prune_unserved(db, True, served, True, counts, {"GGB", "TSB"}) == 3
    assert left(db) == set(bsb) | {"GGB1", "GGB2", "GGB_USED", "TSB_OLD", "GYB_OLD", "JUB1", "JUB2", "JUB3", "JUB4"}
    db = make()
    bd.prune_unserved(db, True, served, False)                                          # 전주를 다 못 받았으면 전주는 그대로
    assert "JUB_OLD" in left(db) and "BSB_OLD" not in left(db) and "GGB_OLD" in left(db)


def test_jeonju_served_walk():
    """전주: 정류장 경유 노선 → 노선 정류장 목록으로 노선이 서는 정류장과 지금 이름을 모음 (중계 서버 응답 모양)"""
    route = lambda rid: (f'<list class="object"><brtStdId type="string">{rid}</brtStdId><brtId type="string">2</brtId><brtClass type="string">0</brtClass>'
                         f'<brtSname type="string">평화동종점</brtSname><brtEname type="string">평화동종점</brtEname><brtFirstTime type="string">540</brtFirstTime>'
                         f'<brtLastTime type="string">2230</brtLastTime><brtMininTerVal type="string">15</brtMininTerVal></list>')
    stop = lambda sid, nm, q: f'<list class="object"><brsSeqno type="string">{q}</brsSeqno><stopStandardid type="string">{sid}</stopStandardid><stopKname type="string">{nm}</stopKname></list>'
    pages = {"stops/R1": stop("306101023", "백자삼거리", 2) + stop("305100426", "풍남중", 1)}
    got = lambda path: pages.get(path, route("R1") if path.startswith("routes/") else "")
    served, ok, routes = bd.fetch_jeonju_served(get=got)
    assert ok and served["JUB306101023"] == "백자삼거리" and "JUB306101039" not in served
    assert routes["R1"]["stops"] == [(1, "305100426", "풍남중"), (2, "306101023", "백자삼거리")]
    # DB 에 노선·정류장 순서·첫차·막차 (앱이 전주 API 를 다시 부르지 않게)
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE routes(id TEXT PRIMARY KEY, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
    db.execute("CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT, name TEXT, lat REAL, lng REAL, updown TEXT, PRIMARY KEY(route_id, seq))")
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    assert bd.write_jeonju(db, routes, {"JUB305100426"}) == 1
    assert db.execute("SELECT no, rtype, realtime, start_node, end_node, city FROM routes WHERE id='JUBR1'").fetchone() == ("2", "순환", "jeonju", "JUB305100426", "JUB306101023", "35010")
    assert db.execute("SELECT seq, stop_id, name FROM route_stops ORDER BY seq").fetchall() == [(1, "JUB305100426", ""), (2, "JUB306101023", "백자삼거리")]
    assert db.execute("SELECT first, last, interval FROM route_info").fetchone() == ("05:40", "22:30", 15)
    assert bd.jeonju_type("0", "4000", "a", "b") == "간선" and bd.jeonju_type("0", "100", "a", "b") == "일반"
    assert bd.jeonju_type("0", "6001", "비전대학교", "비전대학교") == "간선" and bd.jeonju_type("1", "62", "흑석골종점", "원상림") == "일반"
    assert bd.jeonju_no("3", "1") == "3-1" and bd.jeonju_no("3", "2") == "3-2" and bd.jeonju_no("2", "0") == "2" and bd.jeonju_no("104", "") == "104"


def _route_db():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE routes(id TEXT PRIMARY KEY, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
    db.execute("CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT, name TEXT, lat REAL, lng REAL, updown TEXT, PRIMARY KEY(route_id, seq))")
    return db


def test_jeju_routes_written():
    """제주: 노선 목록 + 노선 정류장 목록 → DB (실시간은 제주 API)"""
    pages = {"searchBusRouteList.do": [{"ROUTE_ID": 405410111, "ROUTE_NUM": "101", "ORGT_NM": "공항", "DST_NM": "남원"}],
             "searchBusRouteStationList.do?route_id=405410111": [{"STATION_ID": 405000153, "STATION_NM": "터미널", "STATION_ORD": 2},
                                                                 {"STATION_ID": 405002101, "STATION_NM": "공항", "STATION_ORD": 1}]}
    routes, ok = bd.fetch_jeju(get=pages.get)
    assert ok and list(routes) == ["405410111"]
    db = _route_db()
    assert bd.write_jeju(db, routes, {"JEB405002101"}) == 1
    assert db.execute("SELECT no, start_node, end_node, city, realtime FROM routes").fetchone() == ("101", "JEB405002101", "JEB405000153", "39", "jeju")
    assert db.execute("SELECT seq, stop_id, name FROM route_stops ORDER BY seq").fetchall() == [(1, "JEB405002101", ""), (2, "JEB405000153", "터미널")]
    assert bd.fetch_jeju(get=lambda p: None) == ({}, False)       # 목록을 못 받으면 넣지 않음


def test_gbis_stops_written():
    """경기: 회차점까지 상행 0, 그 뒤 하행 1 (앱 Gyeonggi.routeStops 와 같음)"""
    rows = [{"stationId": 2, "stationSeq": 2, "turnYn": "Y", "stationName": "회차", "x": 127.1, "y": 37.2},
            {"stationId": 1, "stationSeq": 1, "turnYn": "N", "stationName": "기점"},
            {"stationId": 3, "stationSeq": 3, "turnYn": "N", "stationName": "새곳", "x": 127.3, "y": 37.4}]
    db = _route_db()
    bd.write_gbis_stops(db, {"GGB9": rows}, {"GGB1", "GGB2"})
    assert db.execute("SELECT seq, stop_id, name, lat, updown FROM route_stops ORDER BY seq").fetchall() == [
        (1, "GGB1", "", None, "0"), (2, "GGB2", "", None, "0"), (3, "GGB3", "새곳", 37.4, "1")]


def test_files_written():
    """앱 파일(지하철 시간표 등)을 DB files 표로 — 없거나 너무 작은 파일은 넣지 않음"""
    with tempfile.TemporaryDirectory() as d:
        (pathlib.Path(d) / "subway_schedule.tsv").write_text("S\tA\t역\t노선\tR\n" * 300, encoding="utf-8")
        (pathlib.Path(d) / "air_routes.json").write_text("{}", encoding="utf-8")
        db = sqlite3.connect(":memory:")
        assert bd.write_files(db, d) == 1
        assert db.execute("SELECT DISTINCT name FROM files").fetchall() == [("subway_schedule.tsv",)]
        bd.FILE_PART, part = 1000, bd.FILE_PART                       # 나눠 넣은 것을 이으면 원래 글자
        bd.write_files(db, d)
        bd.FILE_PART = part
        assert "".join(r[0] for r in db.execute("SELECT body FROM files ORDER BY part")) == (pathlib.Path(d) / "subway_schedule.tsv").read_text(encoding="utf-8")
        assert db.execute("SELECT COUNT(*) FROM files").fetchone()[0] > 1


def test_tsb_info_written():
    """교통안전공단 노선: TAGO 노선 정보의 첫차·막차·배차를 넣고, 이미 시간이 있는 노선은 그대로 (구례 7-4: 0610~1930, 290분)"""
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    db.execute("INSERT INTO route_info VALUES('TSB_OLD', '06:00', '20:00', NULL, NULL, NULL)")
    n = bd.write_tsb_info(db, {"TSB331000060": {"startvehicletime": "0610", "endvehicletime": 1930, "intervaltime": 290},
                               "TSB_OLD": {"startvehicletime": "0700", "endvehicletime": "2100"},
                               "TSB_NONE": {"startvehicletime": "", "endvehicletime": "", "intervaltime": 0}})
    assert n == 1
    assert db.execute("SELECT first, last, interval FROM route_info WHERE id='TSB331000060'").fetchone() == ("06:10", "19:30", 290)
    assert db.execute("SELECT first FROM route_info WHERE id='TSB_OLD'").fetchone() == ("06:00",)


def test_jeonju_retry_failed():
    """전주: 한 번 실패한 요청은 끝에 다시 받아 '다 받음' (예전엔 한 번 실패로 전주 정류장 지우기를 통째로 건너뜀)"""
    route = '<list class="object"><brtStdId type="string">R1</brtStdId><brtId type="string">5</brtId></list>'
    stop = '<list class="object"><stopStandardid type="string">306101023</stopStandardid><stopKname type="string">백자삼거리</stopKname></list>'
    tries = {}
    def got(path):
        tries[path] = tries.get(path, 0) + 1
        if path == "stops/R1" and tries[path] == 1:
            return None
        return stop if path.startswith("stops/") else route
    served, ok, routes = bd.fetch_jeonju_served(get=got)
    assert ok and "JUB306101023" in served and tries["stops/R1"] == 2
    served, ok, _ = bd.fetch_jeonju_served(get=lambda p: None if p.startswith("stops/") else route)
    assert not ok                                                    # 끝까지 못 받으면 '다 받음' 아님


if __name__ == "__main__":
    for t in (test_incomplete_stops_refetched, test_tsb_stops_written, test_kotsa_timetable_real, test_busy_server_waits_and_retries, test_only_normal_responses_cached, test_real_tago_route_list, test_key_error_stops_immediately, test_helpers, test_daily_limit_partial, test_keyword_fallback_when_full_list_empty, test_full_build, test_prune_unserved, test_jeonju_served_walk, test_jeju_routes_written, test_gbis_stops_written, test_files_written, test_tsb_info_written, test_jeonju_retry_failed):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
