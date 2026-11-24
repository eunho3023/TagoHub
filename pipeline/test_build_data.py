"""수집 프로그램 시험 (가짜 TAGO·경기 응답, 인터넷 없이): python -m pytest pipeline 또는 python pipeline/test_build_data.py"""
import re
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
            # 경기 GBIS v2 (msgHeader.resultCode 0) 와 교통안전공단 파일 API(코드 없음)도 정상으로 저장 — 예전엔 "None" 으로 보고 버렸음
            gbis = '{"response":{"comMsgHeader":"","msgHeader":{"resultCode":0,"resultMessage":"정상"},"msgBody":{"busRouteStationList":[]}}}'
            gbis_none = '{"response":{"comMsgHeader":"","msgHeader":{"resultCode":4,"resultMessage":"결과가 존재하지 않습니다."}}}'
            kotsa = '{"currentCount":1,"data":[{"노선 아이디":1}]}'
            for url, text, name, saved in ((bd.TAGO + "/x", bad, "bad.json", False), (bd.TAGO + "/x", good, "good.json", True),
                                           (bd.GBIS_STOPS, gbis, "gbis.json", True), (bd.GBIS_STOPS, gbis_none, "gbis4.json", False),
                                           (bd.KOTSA_TT, kotsa, "kotsa.json", True)):
                fx = bd.Fetcher.__new__(bd.Fetcher)
                fx.key, fx.s, fx.calls, fx.lock, fx.last = "k", S(text), 0, threading.Lock(), ""
                fx.sleep = lambda s: None
                assert fx.get(url, {}, name, 0) == text
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


def test_kotsa_twin_and_weekend():
    """공단 시간표를 같은 번호의 다른 앞글자 노선(영암 MPB338…)에도, 평일 운행 없는 노선은 토·일 첫차·막차라도"""
    db0 = sqlite3.connect(BASE)
    twin = next(r for (r,) in db0.execute("SELECT r.id FROM routes r LEFT JOIN route_info i ON i.id = r.id WHERE r.id NOT LIKE 'TSB%' AND i.first IS NULL AND i.last IS NULL")
                if re.fullmatch(r"[A-Z]+\d{9}", r))
    db0.close()
    num = re.sub(r"^[A-Z]+", "", twin)
    rows = [{"노선 아이디": int(num), "요일": "평일", "일일운행횟수": 12, "기점첫차출발시각": "06:10", "기점막차출발시각": "20:40", "최소배차간격": 60, "최대배차간격": 90},
            {"노선 아이디": 777000001, "요일": "토요일", "일일운행횟수": 2, "기점첫차출발시각": "09:00", "기점막차출발시각": "15:00", "최소배차간격": 0, "최대배차간격": 0}]
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "bus_stops.db"
        bd.write_db(BASE, out, {}, [], "202611240300", rows)
        db = sqlite3.connect(out)
        assert db.execute("SELECT first, last FROM route_info WHERE id = ?", (twin,)).fetchone() == ("06:10", "20:40")
        assert db.execute("SELECT trips FROM route_schedule WHERE id = ? AND day = 'weekday'", (twin,)).fetchone() == (12,)
        assert db.execute("SELECT first, last FROM route_info WHERE id = 'TSB777000001'").fetchone() == ("09:00", "15:00")
        db.close()


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


def test_unserved_trust_per_city():
    """TAGO 답을 믿을지는 지역 접두사|도시마다: 같은 충남이라도 청양(답이 맞음)은 지우고, 보령(서는 정류장도 0 이라고 함)은 그대로"""
    with tempfile.TemporaryDirectory() as d:
        path = pathlib.Path(d) / "b.db"
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE stops(id TEXT PRIMARY KEY, name TEXT, city TEXT)")
        db.execute("CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT)")
        used = [f"CNB1{i}" for i in range(6)] + [f"CNB2{i}" for i in range(6)]
        db.executemany("INSERT INTO stops VALUES(?, ?, ?)", [(s, s, "34350" if s.startswith("CNB1") else "34030") for s in used + ["CNB1_OLD", "CNB2_OLD"]])
        db.executemany("INSERT INTO route_stops VALUES('R', 1, ?)", [(s,) for s in used])
        db.commit(); db.close()

        class F:
            calls = 0
            def get(self, url, params, cache_file, max_age):
                sid = params["nodeid"]
                n = 0 if sid.endswith("_OLD") or sid.startswith("CNB2") else 3   # 보령은 서는 정류장도 0
                return json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"totalCount": n}}})
        counts, trusted = bd.fetch_unserved_check(F(), path)
        assert trusted == {"CNB|34350"} and counts == {"CNB1_OLD": 0, "CNB2_OLD": 0}
        db = sqlite3.connect(path)
        bd.prune_unserved(db, True, None, False, counts, trusted)
        left = {r[0] for r in db.execute("SELECT id FROM stops")}
        db.close()
        assert "CNB1_OLD" not in left and "CNB2_OLD" in left


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
    # 노선 종류: 누리집 노선 정보(line/노선ID)의 busTypeStr, 같은 번호는 한 번만 묻고, 못 받으면 비워 둠
    pages["searchBusRouteList.do"].append({"ROUTE_ID": 405410112, "ROUTE_NUM": "101", "ORGT_NM": "남원", "DST_NM": "공항"})
    pages["searchBusRouteStationList.do?route_id=405410112"] = pages["searchBusRouteStationList.do?route_id=405410111"]
    asked = []
    def get(p):
        if p.startswith("line/"):
            asked.append(p)
            return {"routeId": 405410111, "busTypeStr": "급행"}
        return pages.get(p)
    routes, ok = bd.fetch_jeju(get=get)
    assert ok and asked == ["line/405410111"] and {r["type"] for r in routes.values()} == {"급행"}
    db = _route_db()
    bd.write_jeju(db, routes, set())
    assert db.execute("SELECT DISTINCT rtype FROM routes").fetchall() == [("급행",)]
    assert bd.jeju_type("리무진") == "공항리무진" and bd.jeju_type("읍면지선") == "읍면지선" and bd.jeju_type("") is None


def test_gbis_stops_written():
    """경기: 회차점까지 상행 0, 그 뒤 하행 1 (앱 Gyeonggi.routeStops 와 같음)"""
    rows = [{"stationId": 2, "stationSeq": 2, "turnYn": "Y", "stationName": "회차", "x": 127.1, "y": 37.2},
            {"stationId": 1, "stationSeq": 1, "turnYn": "N", "stationName": "기점"},
            {"stationId": 3, "stationSeq": 3, "turnYn": "N", "stationName": "새곳", "x": 127.3, "y": 37.4}]
    db = _route_db()
    bd.write_gbis_stops(db, {"GGB9": rows}, {"GGB1", "GGB2"})
    assert db.execute("SELECT seq, stop_id, name, lat, updown FROM route_stops ORDER BY seq").fetchall() == [
        (1, "GGB1", "", None, "0"), (2, "GGB2", "", None, "0"), (3, "GGB3", "새곳", 37.4, "1")]


def test_gbis_stops_use_stored_after_limit():
    """경기 노선 정류장 순서: 하루 한도에 걸리면 남은 노선은 저장해 둔 것(30일 넘은 것도)으로"""
    with tempfile.TemporaryDirectory() as d:
        old = bd.CACHE
        bd.CACHE = pathlib.Path(d)
        try:
            (pathlib.Path(d) / "gbis").mkdir()
            row = '{"response":{"msgHeader":{"resultCode":0},"msgBody":{"busRouteStationList":[{"stationId":5,"stationSeq":1}]}}}'
            (pathlib.Path(d) / "gbis/stops_900000002.json").write_text(row, encoding="utf-8")

            class F:
                calls = 0
                def get(self, url, params, cache_file, max_age):
                    F.calls += 1
                    if F.calls > 1:
                        raise bd.Limit()
                    return row
            out = bd.fetch_gbis_stops(F(), BASE, [("900000001", "1", None, None), ("900000002", "2", None, None), ("900000003", "3", None, None)])
            assert set(out) == {"GGB900000001", "GGB900000002"}      # 1: 받음, 2: 한도 뒤 저장본, 3: 저장본 없음
            assert F.calls == 2                                       # 한도 뒤엔 더 묻지 않음
        finally:
            bd.CACHE = old


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



def test_incheon_info_and_tidy():
    """인천: 날짜로 바뀐 번호('05월 01일')·빈 종류·첫차를 TAGO 노선 정보로. 번호 끝 '번'(원주)·전부 시외버스인 도시(안동)도 정리"""
    assert bd.incheon_current("ICB165000006") == "ICB365000006" and bd.incheon_current("ICB265000006") == "ICB465000006"
    assert bd.incheon_current("ICB365000006") == "ICB365000006" and bd.incheon_current("JUB1") == "JUB1"
    db = _route_db()
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    add = lambda rid, no, city, rt, tp=None: db.execute("INSERT INTO routes(id,no,city,realtime,rtype) VALUES(?,?,?,?,?)", (rid, no, city, rt, tp))
    add("ICB165000006", "05월 01일", "23", "tago")
    add("ICB165000095", "539", "23", "tago")
    add("FWJ0112", "51번", "32020", "resolve")
    add("FWJ0001", "10", "32020", "resolve")
    for i in range(20):
        add(f"ADB{i}", str(300 + i), "37040", "tago", "시외버스")
        add(f"IKB{i}", str(i), "35020", "tago", "시외버스" if i == 0 else "일반버스")
    info = {"ICB165000006": {"routeno": "5-1", "routetp": "간선버스", "startvehicletime": "0530", "endvehicletime": "2300", "intervaltime": "12"},
            "ICB165000095": {"routeno": "", "routetp": "지선버스"}}
    assert bd.write_incheon_info(db, info) == 2
    assert db.execute("SELECT no, rtype FROM routes WHERE id LIKE 'ICB%' ORDER BY id").fetchall() == [("5-1", "간선버스"), ("539", "지선버스")]
    assert db.execute("SELECT first, last, interval FROM route_info WHERE id = 'ICB165000006'").fetchone() == ("05:30", "23:00", 12)
    assert bd.tidy_routes(db) == (1, 20)
    assert db.execute("SELECT no FROM routes WHERE id LIKE 'FWJ%' ORDER BY id").fetchall() == [("10",), ("51",)]
    assert db.execute("SELECT DISTINCT rtype FROM routes WHERE city = '37040'").fetchall() == [("일반버스",)]
    assert db.execute("SELECT rtype FROM routes WHERE id = 'IKB0'").fetchone() == ("시외버스",)      # 섞여 있는 도시는 그대로


def test_jeonju_urls_direct_first():
    """전주 API 는 이 PC 에서 바로 먼저 (인증키가 있을 때), 그다음 중계 서버. 키는 한 번만 인코딩"""
    u = bd.jeonju_urls("routes/305032824", key="a/b+c==")
    assert u[0] == "https://apis.data.go.kr/4641000/busstop/general_busstop_common?stopStandardid=305032824&stopStdid=305032824&serviceKey=a%2Fb%2Bc%3D%3D"
    assert u[1].endswith("/w/bus/jeonju/routes/305032824")
    assert bd.jeonju_urls("stops/305001764", key="a%2Fb")[0].endswith("brtStdid=305001764&serviceKey=a%2Fb")
    # 키가 없거나 모르는 모양이면 중계 서버만
    assert len(bd.jeonju_urls("routes/305032824", key="")) == 1
    assert len(bd.jeonju_urls("line/x", key="k")) == 1


def test_jeju_ssl_context_has_intermediate():
    """제주 서버가 안 보내는 중간 인증서(Sectigo DV R36)를 믿을 목록에 넣음 (러너 서비스 계정에서 바로 붙게)"""
    ctx = bd.jeju_ssl_context()
    names = [dict(x[0] for x in c["subject"]).get("commonName") for c in ctx.get_ca_certs()]
    assert "Sectigo Public Server Authentication CA DV R36" in names


def test_jeonju_time_budget():
    """전주: 가끔만 받히는(나머지는 실패) 때도 시간 한도가 지나면 남은 요청은 건너뜀"""
    import urllib.request
    clock = [0.0]
    calls = []
    class Ok:
        def __init__(self): self.b = b'<list class="object"></list>'
        def read(self): return self.b
        def __enter__(self): return self
        def __exit__(self, *a): return False
    def flaky(req, timeout=None):
        calls.append(1)
        clock[0] += 30                     # 요청마다 30초
        if len(calls) % 3 == 0:
            return Ok()                    # 세 번에 한 번은 받힘 → 잇달아 실패 규칙으로는 안 끝남
        raise OSError("502")
    old = urllib.request.urlopen, bd.time.sleep, bd.time.monotonic
    urllib.request.urlopen, bd.time.sleep, bd.time.monotonic = flaky, (lambda s: None), (lambda: clock[0])
    try:
        served, ok, _ = bd.fetch_jeonju_served()
    finally:
        urllib.request.urlopen, bd.time.sleep, bd.time.monotonic = old
    stops = bd.sqlite3.connect(bd.BASE_DB).execute("SELECT COUNT(*) FROM stops WHERE id LIKE 'JUB%'").fetchone()[0]
    if stops:
        assert not ok
        assert clock[0] <= bd.JEONJU_BUDGET_S + 30 * 6 * 2, clock[0]   # 한도를 넘긴 뒤로는 묻지 않음


def test_jeonju_gives_up_when_all_fail():
    """전주: 바로 묻기(하루 한도)·중계 서버(502)가 다 안 되면 몇 번 뒤 남은 요청은 건너뜀 (요청마다 1분 넘게 걸려 갱신이 몇 시간 늘어졌음)"""
    import urllib.request
    calls = []
    def fail(req, timeout=None):
        calls.append(req.full_url)
        raise OSError("down")
    old_open, old_sleep = urllib.request.urlopen, bd.time.sleep
    urllib.request.urlopen, bd.time.sleep = fail, (lambda s: None)
    try:
        served, ok, _ = bd.fetch_jeonju_served()
    finally:
        urllib.request.urlopen, bd.time.sleep = old_open, old_sleep
    stops = bd.sqlite3.connect(bd.BASE_DB).execute("SELECT COUNT(*) FROM stops WHERE id LIKE 'JUB%'").fetchone()[0]
    if stops:
        assert not ok and served == {}
        assert len(calls) <= bd.JEONJU_GIVE_UP * 3 * 2, len(calls)


def test_carry_jeonju_from_previous_db():
    """전주를 다 받지 못한 날: 지난번 DB 의 전주 노선을 그대로 넣음 (앱 기본 DB 에는 전주 노선이 없어 0개가 됐었음)"""
    import tempfile, os
    d = tempfile.mkdtemp()
    def make(path, with_jeonju):
        db = bd.sqlite3.connect(path)
        db.execute("CREATE TABLE routes(id TEXT PRIMARY KEY, no TEXT NOT NULL, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT NOT NULL, region TEXT, rtype TEXT, realtime TEXT)")
        db.execute("CREATE TABLE route_stops(route_id TEXT NOT NULL, seq INTEGER NOT NULL, stop_id TEXT, name TEXT NOT NULL, lat REAL, lng REAL, updown TEXT, PRIMARY KEY(route_id, seq))")
        db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
        db.execute("INSERT INTO routes VALUES('SEB1','1',NULL,NULL,NULL,NULL,'11',NULL,NULL,'tago')")
        if with_jeonju:
            db.execute("INSERT INTO routes VALUES('JUB305001764','5',NULL,NULL,NULL,NULL,'35010','전주',NULL,'jeonju')")
            db.execute("INSERT INTO route_stops VALUES('JUB305001764',1,'JUB306101023','백자삼거리',NULL,NULL,NULL)")
            db.execute("INSERT INTO route_info VALUES('JUB305001764','05:30','22:40',15,NULL,NULL)")
        db.commit()
        return db
    prev = os.path.join(d, "prev.db")
    make(prev, True).close()
    cur = make(os.path.join(d, "cur.db"), False)
    assert bd.carry_jeonju(cur, prev) == 1
    assert cur.execute("SELECT realtime FROM routes WHERE id='JUB305001764'").fetchone() == ("jeonju",)
    assert cur.execute("SELECT COUNT(*) FROM route_stops WHERE route_id LIKE 'JUB%'").fetchone() == (1,)
    assert cur.execute("SELECT first FROM route_info WHERE id='JUB305001764'").fetchone() == ("05:30",)
    assert cur.execute("SELECT COUNT(*) FROM routes WHERE id='SEB1'").fetchone() == (1,)   # 다른 노선은 그대로
    assert bd.carry_jeonju(cur, None) == 0                                                     # 지난번 DB 를 못 받으면 아무것도 안 함
    cur.close()


def test_fill_only_missing_routes():
    """원주·세종: 앱 DB 에 다른 자료로 있는 번호는 건너뛰고 TAGO 에만 있는 노선만 받음 (고장·공차는 노선 아님)"""
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d) / "base.db"
        db = sqlite3.connect(base)
        db.execute("CREATE TABLE routes(id TEXT, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
        for rid, no in (("FWJ0001", "51번"), ("FWJ0002", "2"), ("FSJ0001", "B5(아름동방면)"), ("FSJ0002", "35"), ("OLD1", "99")):
            db.execute("INSERT INTO routes VALUES(?,?,?,?,NULL,NULL,?,NULL,NULL,?)", (rid, no, "가", "나", "12" if rid.startswith("FSJ") else "32020",
                                                                                   "tago" if rid == "OLD1" else "resolve"))
        db.commit(); db.close()
        have = bd.existing_routes(base, ["32020", "12"])
    item = lambda no: {"routeno": no, "startnodenm": "x", "endnodenm": "y"}
    assert bd.already_have(item("51"), have["32020"])           # '51번' = '51'
    assert not bd.already_have(item("2(공영)"), have["32020"])  # 공영버스 2번은 시내 2번과 다른 노선
    assert not bd.already_have(item("99"), have["32020"])       # 지난번 TAGO 로 넣은 건 '있음'으로 안 봄
    assert bd.already_have(item("B5"), have["12"])              # 괄호 설명 뺀 번호
    assert bd.already_have(item("DRT35"), have["12"])           # DRT35 = 35
    assert not bd.already_have(item("DRT31"), have["12"])
    assert bd.NOT_ROUTE.match("고장") and bd.NOT_ROUTE.match("공차") and not bd.NOT_ROUTE.match("공영")

    class Fill(FakeFetcher):
        def get(self, url, params, cache_file, max_age):
            if url.endswith("getRouteNoList") and "routeNo" not in params:
                self.calls += 1
                self.urls.append(("getRouteNoList", dict(params)))
                return resp([{"routeid": "WJB1", "routeno": "51", "startnodenm": "a", "endnodenm": "b"},
                             {"routeid": "WJB2", "routeno": "5(공영)", "startnodenm": "a", "endnodenm": "b"},
                             {"routeid": "WJB3", "routeno": "고장", "startnodenm": "a", "endnodenm": "a"}], total=3)
            if url.endswith("getRouteAcctoThrghSttnList"):
                self.calls += 1
                self.urls.append(("getRouteAcctoThrghSttnList", dict(params)))
                return resp({"nodeid": "WJB_S", "nodenm": "정류장", "nodeord": 1, "updowncd": 0, "gpslati": 37.3, "gpslong": 127.9})
            return super().get(url, params, cache_file, max_age)

    fx = Fill()
    tago, complete = bd.fetch_tago(fx, [["32020", "강원특별자치도 원주시"]], have)
    assert complete and set(tago["32020"]["routes"]) == {"WJB2"}
    assert [p["routeId"] for op, p in fx.urls if op == "getRouteAcctoThrghSttnList"] == ["WJB2"]   # 건너뛴 노선은 정류장도 안 받음
    assert [c for c, _ in bd.FILL_CITIES] == ["32020", "12"]


def test_resolve_times():
    """포항·원주·세종: 다른 자료 노선의 첫차·막차를 TAGO 목록에서 번호로 (괄호 설명·DRT 무시, 기점·종점이 같은 것 먼저)"""
    tago = [{"routeno": "206(기본)", "startnodenm": "양덕행", "endnodenm": "위덕대", "startvehicletime": "0600", "endvehicletime": 2230},
            {"routeno": "206(기본-자명경유)", "startnodenm": "양덕", "endnodenm": "자명", "startvehicletime": "0640", "endvehicletime": "0010"},
            {"routeno": "31", "startnodenm": "가", "endnodenm": "가", "startvehicletime": "0000", "endvehicletime": "0000"},
            {"routeno": "DRT62", "startnodenm": "a", "endnodenm": "b", "startvehicletime": 700, "endvehicletime": 1900}]
    tago.append({"routeno": "구룡포", "startnodenm": "a", "endnodenm": "b", "startvehicletime": "0610", "endvehicletime": "2100"})
    rows = [("FPH1", "206", "양덕", "위덕대"), ("FPH2", "206", "딴곳", "딴곳"), ("FWJ1", "31번", "가", "가"), ("FSJ1", "62", "x", "y"), ("FX", "999", "", ""), ("FPH4", "구룡포2", "", "")]
    got = bd.resolve_times(rows, tago)
    assert got["FPH1"] == ("06:00", "22:30")          # 기점·종점이 같은 것
    assert got["FPH2"] == ("06:00", "00:10")          # 못 맞추면 가장 이른 첫차·가장 늦은 막차(자정 넘김)
    assert "FWJ1" not in got and "FX" not in got       # 0000·0000 은 빈 값, 없는 번호
    assert got["FSJ1"] == ("07:00", "19:00")
    assert got["FPH4"] == ("06:10", "21:00")         # '구룡포2' → TAGO '구룡포'
    with tempfile.TemporaryDirectory() as d:
        dbp = pathlib.Path(d) / "x.db"
        db = sqlite3.connect(dbp)
        db.execute("CREATE TABLE routes(id TEXT, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
        db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
        db.execute("INSERT INTO routes VALUES('FPH1','206','양덕','위덕대',NULL,NULL,'37010',NULL,NULL,'resolve')")
        db.execute("INSERT INTO routes VALUES('FPH3','206','양덕','위덕대',NULL,NULL,'37010',NULL,NULL,'resolve')")
        db.execute("INSERT INTO route_info VALUES('FPH3','05:00','23:00',10,NULL,NULL)")   # 이미 있으면 그대로
        db.commit(); db.close()
        assert bd.fill_resolve_times(dbp, {"37010": tago}) == 1
        db = sqlite3.connect(dbp)
        assert db.execute("SELECT first, last FROM route_info WHERE id='FPH1'").fetchone() == ("06:00", "22:30")
        assert db.execute("SELECT first, last FROM route_info WHERE id='FPH3'").fetchone() == ("05:00", "23:00")
        db.close()


def test_drop_empty_dups():
    """정류장 없는 노선은 같은 번호 노선이 따로 있을 때만 지우고 첫차·막차는 넘겨줌, 공단 정류장은 노선 정류장 바로 옆일 때만 지움"""
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE routes(id TEXT, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
    db.execute("CREATE TABLE route_stops(route_id TEXT, seq INTEGER, stop_id TEXT, name TEXT, lat REAL, lng REAL, updown TEXT)")
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    db.execute("CREATE TABLE route_schedule(id TEXT, day TEXT, trips INTEGER, first TEXT, last TEXT, end_first TEXT, end_last TEXT, min_int INTEGER, max_int INTEGER)")
    db.execute("CREATE TABLE stops(id TEXT, name TEXT, lat REAL, lng REAL, no TEXT, city TEXT)")
    for r in [("MPB1", "101", "36410"), ("TSB1", "101", "36410"), ("TSB2", "101", "36410"), ("TSB3", "999", "36410"), ("MPB2", "102", "36410"), ("TSB4", "102", "36410"),
              ("TSB7", "200", "37390"), ("TSB8", "200", "37390")]:   # 공단 노선끼리 같은 번호 → 둘 다 남김
        db.execute("INSERT INTO routes(id, no, city, realtime) VALUES(?,?,?,'tago')", r)
    # 이웃 도시(대구)의 '칠곡3[갈래]': 기점·종점 이름이 같으면 지움, 다르면 남김
    db.executemany("INSERT INTO routes(id, no, start_name, end_name, city, realtime) VALUES(?,?,?,?,?,'tago')", [
        ("DGB1", "칠곡3[동명-칠곡시장-동명]", "동명교통", "동명교통", "22"), ("TSB10", "칠곡3", "동명교통", "동명교통", "37390"),
        ("TSB11", "칠곡3", "왜관", "석적", "37390")])
    db.execute("INSERT INTO route_stops VALUES('DGB1',1,'DGB_A','',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_stops VALUES('MPB1',1,'MPB_A','',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_stops VALUES('MPB2',1,'MPB_A','',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_stops VALUES('TSB7',1,'TSB_X','',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_info VALUES('TSB1','07:00','18:00',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_info VALUES('TSB2','06:30','00:20',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_info VALUES('MPB2','05:00','22:00',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_info VALUES('TSB4','04:00','23:00',NULL,NULL,NULL)")
    db.execute("INSERT INTO route_schedule(id, day) VALUES('TSB1','weekday')")
    assert bd.drop_empty_routes(db, False) == 0
    assert bd.drop_empty_routes(db, True) == 4
    assert sorted(r[0] for r in db.execute("SELECT id FROM routes")) == ["DGB1", "MPB1", "MPB2", "TSB11", "TSB3", "TSB7", "TSB8"]   # 999 는 같은 번호가 없어 남김
    assert db.execute("SELECT first, last FROM route_info WHERE id='MPB1'").fetchone() == ("06:30", "00:20")   # 자정 넘은 막차
    assert db.execute("SELECT first, last FROM route_info WHERE id='MPB2'").fetchone() == ("05:00", "22:00")   # 있던 건 그대로
    assert db.execute("SELECT COUNT(*) FROM route_info WHERE id LIKE 'TSB%'").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM route_schedule").fetchone()[0] == 0
    m = 1 / 111_000
    db.executemany("INSERT INTO stops VALUES(?,?,?,?,NULL,?)", [
        ("MPB_A", "영암터미널", 34.8, 126.7, "36410"),
        ("TSB_1", "영암 터미널", 34.8 + 40 * m, 126.7, "TS338"),   # 이름 같고 40m → 지움
        ("TSB_2", "다른이름", 34.8 + 20 * m, 126.7, "TS338"),       # 이름 달라도 20m → 지움
        ("TSB_3", "다른이름", 34.8 + 45 * m, 126.7, "TS338"),       # 이름 다르고 45m → 남김
        ("TSB_4", "외딴곳", 34.9, 126.7, "TS338"),                   # 근처에 없음 → 남김
        ("GGB_1", "영암터미널", 34.8 + 10 * m, 126.7, "31010"),      # 공단 아님 → 남김
        ("TSB_U", "공단만", 35.5, 127.0, "TS999"),                   # 공단 자료뿐인 지역: 노선 서는 공단 정류장
        ("TSB_5", "공단만", 35.5 + 10 * m, 127.0, "TS999"),          # 그 옆 공단 정류장 → 남김 (공단끼리는 비교 안 함)
    ])
    db.execute("INSERT INTO route_stops VALUES('TSB9',1,'TSB_U','',NULL,NULL,NULL)")
    assert bd.drop_tsb_twin_stops(db, False) == 0
    assert bd.drop_tsb_twin_stops(db, True) == 2
    assert sorted(r[0] for r in db.execute("SELECT id FROM stops")) == ["GGB_1", "MPB_A", "TSB_3", "TSB_4", "TSB_5", "TSB_U"]
    # 공단 정류장 이름을 같은 자리(8m 안) 지자체 정류장 지금 이름으로 (광주 전남방직 → 발산뽕뽕다리)
    db.executemany("INSERT INTO stops VALUES(?,?,?,?,NULL,?)", [
        ("KJB3047", "발산뽕뽕다리", 35.160944, 126.893999, "24"),
        ("TSB178000127", "전남방직", 35.160953, 126.894007, "TS178"),   # 1m → 바꿈
        ("TSB_F", "멀리", 35.160944 + 12 * m, 126.893999, "TS178"),      # 12m (길 건너) → 그대로
    ])
    db.execute("INSERT INTO route_stops VALUES('TSB8',1,'TSB178000127','전남방직',NULL,NULL,NULL)")
    assert bd.rename_tsb_to_local(db) == 1
    assert db.execute("SELECT name FROM stops WHERE id='TSB178000127'").fetchone()[0] == "발산뽕뽕다리"
    assert db.execute("SELECT name FROM route_stops WHERE stop_id='TSB178000127'").fetchone()[0] == "발산뽕뽕다리"
    assert db.execute("SELECT name FROM stops WHERE id='TSB_F'").fetchone()[0] == "멀리"
    assert db.execute("SELECT name FROM stops WHERE id='TSB_3'").fetchone()[0] == "다른이름"           # 45m 떨어진 것도 그대로


def test_resolve_types():
    """다른 자료 노선의 빈 노선 종류를 TAGO 목록에서 번호로 (기점·종점 같은 것 먼저, 없으면 가장 많은 종류), 있던 종류는 그대로"""
    tago = [{"routeno": "206(기본)", "startnodenm": "양덕", "endnodenm": "위덕대", "routetp": "간선버스"},
            {"routeno": "206", "startnodenm": "가", "endnodenm": "나", "routetp": "지선버스"},
            {"routeno": "206", "startnodenm": "다", "endnodenm": "라", "routetp": "지선버스"},
            {"routeno": "31", "startnodenm": "a", "endnodenm": "b", "routetp": None},
            {"routeno": "구룡포", "startnodenm": "a", "endnodenm": "b", "routetp": "마을버스"}]
    rows = [("A", "206", "양덕", "위덕대"), ("B", "206", "x", "y"), ("C", "31", "a", "b"), ("G", "구룡포2", "", "")]
    assert bd.resolve_types(rows, tago) == {"A": "간선버스", "B": "지선버스", "G": "마을버스"}
    with tempfile.TemporaryDirectory() as d:
        dbp = pathlib.Path(d) / "x.db"
        db = sqlite3.connect(dbp)
        db.execute("CREATE TABLE routes(id TEXT, no TEXT, start_name TEXT, end_name TEXT, start_node TEXT, end_node TEXT, city TEXT, region TEXT, rtype TEXT, realtime TEXT)")
        db.execute("INSERT INTO routes VALUES('A','206','양덕','위덕대',NULL,NULL,'37010',NULL,NULL,'resolve')")
        db.execute("INSERT INTO routes VALUES('D','206','양덕','위덕대',NULL,NULL,'37010',NULL,'마을버스','resolve')")
        db.execute("INSERT INTO routes VALUES('E','206','양덕','위덕대',NULL,NULL,'37010',NULL,NULL,'tago')")
        db.commit(); db.close()
        assert bd.fill_resolve_types(dbp, {"37010": tago}) == 1
        db = sqlite3.connect(dbp)
        assert dict(db.execute("SELECT id, rtype FROM routes")) == {"A": "간선버스", "D": "마을버스", "E": None}
        db.close()


def test_local_times():
    """양산 출발시간 파일·제주 시간표 엑셀 → 첫차·막차(·배차), 이미 있는 시간은 그대로"""
    import io, zipfile
    rows = [{"노선아이디": "'0008009001", "순번": str(k), "기점출발시간": t} for k, ts in
            [(1, ["05:00", "05:00", "05:20"]), (2, ["05:15", "05:16", "05:40"]), (3, ["05:30", "05:32", "06:00"]), (4, ["22:30", "00:10"])] for t in ts]
    rows.append({"노선아이디": "'0002009001", "순번": "1", "기점출발시간": "07:10"})
    info = bd.yangsan_info(rows)
    assert info["YSB0008009001"] == ("05:00", "00:10", 15)      # 자정 넘은 막차, 배차는 순번마다 첫 줄 (05:00·05:15·05:30·22:30 → 가운데 15)
    assert info["YSB0002009001"] == ("07:10", "07:10", None)

    def xlsx(sheets):
        b = io.BytesIO()
        with zipfile.ZipFile(b, "w") as z:
            z.writestr("xl/workbook.xml", "<workbook><sheets>" + "".join(f'<sheet name="{n}" sheetId="{i}"/>' for i, (n, _) in enumerate(sheets, 1)) + "</sheets></workbook>")
            for i, (_, rs) in enumerate(sheets, 1):
                cells = "".join(f'<row r="{y}">' + "".join(f'<c r="{chr(65 + x)}{y}" t="inlineStr"><is><t>{v}</t></is></c>' for x, v in enumerate(r) if v != "") + "</row>" for y, r in enumerate(rs, 1))
                z.writestr(f"xl/worksheets/sheet{i}.xml", f"<worksheet><sheetData>{cells}</sheetData></worksheet>")
        return b.getvalue()
    one = xlsx([("302 (평일)A-B", [["", "302번"], ["", "구분", "A", "B", "비  고"], ["", "1.0", "X", "06:14"], ["", "2.0", "6:29 (출발)", "06:35"], ["", "3.0", "22:00", "22:20"]]),
                ("302 (휴일)A-B", [["", "302번"], ["", "구분", "A", "B"], ["", "1.0", "05:10", "05:20"], ["", "2.0", "23:00", "23:20"]])])
    two = xlsx([("43-1 A-B", [["", "43-1번, 43-2번"], ["", "구분", "노선번호", "A", "B"], ["", "1.0", "43-1번", "05:50", "06:00"], ["", "2.0", "43-2번", "X", "05:57"], ["", "3.0", "43-1번", "21:12", ""]])])
    sets = [bd.read_xlsx(one), bd.read_xlsx(two)]
    assert sets[0][0][1][2] == ["", "1.0", "X", "06:14"]
    times = bd.jeju_times(sets)
    assert times["302"] == ("06:14", "22:00", None)            # 평일 표만 (휴일 05:10·23:00 은 안 봄)
    assert times["43-1"] == ("05:50", "21:12", None) and times["43-2"] == ("05:57", "05:57", None)

    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE routes(id TEXT, no TEXT, city TEXT)")
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    db.executemany("INSERT INTO routes VALUES(?,?,?)", [("YSB0008009001", "8", "38100"), ("JEB1", "302", "39"), ("JEB2", "302", "39"), ("JEB3", "43-1", "39")])
    db.execute("INSERT INTO route_info VALUES('JEB2','07:00','20:00',NULL,NULL,NULL)")
    db.commit()
    assert bd.fill_route_info(db, info) == 1                     # YSB0002009001 은 DB 에 없음
    assert bd.fill_route_info(db, {"JEB1": times["302"], "JEB2": times["302"]}) == 1
    assert db.execute("SELECT first, last, interval FROM route_info WHERE id='YSB0008009001'").fetchone() == ("05:00", "00:10", 15)
    assert db.execute("SELECT first, last FROM route_info WHERE id='JEB2'").fetchone() == ("07:00", "20:00")


def test_city_times():
    """안동(목록)·거제(시간표 표)·나주(노선 정보) → 첫차·막차·배차"""
    an = bd.andong_info([{"routeId": 354300276, "holidayType": "1", "strtTm": "6:05,6:25,6:45"}, {"routeId": 354300276, "holidayType": "2", "strtTm": "7:00"},
                         {"routeId": 354300277, "holidayType": "2", "strtTm": "8:00,9:00"}, {"routeId": 1, "holidayType": "1", "strtTm": ""}])
    assert an == {"ADB354300276": ("06:05", "06:45", 20), "ADB354300277": ("08:00", "09:00", None)}   # 평일 표 먼저, 없으면 휴일
    page = """<b>시간표검색결과</b><table><tr><th>노선번호</th><th>기점</th></tr>
      <tr><td rowspan="3">10</td><td>고현</td><td>-</td><td>05:40</td><td>수월</td><td>능포</td></tr>
      <tr><td>고현</td><td>05:50</td><td>-</td><td>수월</td><td>능포</td></tr>
      <tr><td>고현</td><td> 22:30 </td><td>-</td><td>수월</td><td>능포</td></tr></table>"""
    assert bd.geoje_times(page) == ["05:50", "22:30"]                     # 평일 칸만
    assert bd.geoje_times(page.replace("05:50", "-").replace(" 22:30 ", "-")) == ["05:40"]   # 평일이 없으면 휴일
    nj = bd.naju_info([{"LINE_ID": 1030, "FIRST_TIME": "06:25", "LAST_TIME": "19:35", "RUN_INTERVAL": "65~125"}, {"LINE_ID": 1038, "FIRST_TIME": "07:12", "LAST_TIME": "20:02", "RUN_INTERVAL": "1~1"}])
    assert nj == {"NJB1030": ("06:25", "19:35", 95), "NJB1038": ("07:12", "20:02", None)}
    assert bd.span(["08:10", "20:30", "21:20"])[2] is None                # 배차 세 시간 넘으면 비움


def test_static_city_times():
    """옮겨 둔 경산 첫차·막차: 같은 번호 갈래 모두, 이미 있는 첫차·막차는 그대로, 실제 파일도 읽힘"""
    import sqlite3, tempfile, os
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE routes(id TEXT PRIMARY KEY, no TEXT)")
    db.execute("CREATE TABLE route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER)")
    db.executemany("INSERT INTO routes VALUES (?, ?)", [("GYB1", "남천1"), ("GYB2", "남천1"), ("GYB3", "100"), ("GYB4", "하양1"), ("ADB1", "100")])
    db.execute("INSERT INTO route_info VALUES ('GYB3', '06:00', '22:00', NULL, NULL, NULL)")
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
        f.write("노선번호,평일첫차,평일막차\n남천1,07:02,19:50\n100,06:55,22:35\n")
    info = bd.static_city_info(db, "GYB", f.name)
    os.unlink(f.name)
    assert info == {"GYB1": ("07:02", "19:50", None), "GYB2": ("07:02", "19:50", None), "GYB3": ("06:55", "22:35", None)}
    assert bd.fill_route_info(db, info) == 2
    assert db.execute("SELECT first, last FROM route_info WHERE id = 'GYB3'").fetchone() == ("06:00", "22:00")
    assert bd.static_city_info(db, "GYB", "없는 파일.csv") == {}
    real = bd.static_city_info(db, "GYB", bd.STATIC_TIMES / "gyeongsan.csv")
    assert real["GYB1"] == ("07:02", "19:50", None) and real["GYB3"] == ("06:55", "22:35", None)


if __name__ == "__main__":
    for t in (test_incomplete_stops_refetched, test_tsb_stops_written, test_kotsa_timetable_real, test_busy_server_waits_and_retries, test_only_normal_responses_cached, test_real_tago_route_list, test_key_error_stops_immediately, test_helpers, test_daily_limit_partial, test_keyword_fallback_when_full_list_empty, test_full_build, test_prune_unserved, test_unserved_trust_per_city, test_jeonju_served_walk, test_jeju_routes_written, test_gbis_stops_written, test_gbis_stops_use_stored_after_limit, test_files_written, test_tsb_info_written, test_jeonju_retry_failed, test_incheon_info_and_tidy, test_jeonju_urls_direct_first, test_jeju_ssl_context_has_intermediate, test_jeonju_gives_up_when_all_fail, test_jeonju_time_budget, test_carry_jeonju_from_previous_db, test_fill_only_missing_routes, test_resolve_times, test_drop_empty_dups, test_resolve_types, test_local_times, test_city_times, test_kotsa_twin_and_weekend, test_static_city_times):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
