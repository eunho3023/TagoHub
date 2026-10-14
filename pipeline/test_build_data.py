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


def test_helpers():
    assert bd.clock("0530") == "05:30" and bd.clock(530) == "05:30" and bd.clock(2310) == "23:10" and bd.clock(None) is None
    assert bd.region_name("경상남도 김해시") == "김해" and bd.region_name("부산광역시") == "부산" and bd.region_name("충청북도 청주시") == "청주"
    assert bd.items('{"response":{"body":{"items":""}}}') == []
    try:
        bd.items('{"response":{"header":{"resultCode":"22","resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}}')
        raise AssertionError("한도인데 통과")
    except bd.Limit:
        pass


if __name__ == "__main__":
    for t in (test_kotsa_timetable_real, test_busy_server_waits_and_retries, test_only_normal_responses_cached, test_real_tago_route_list, test_key_error_stops_immediately, test_helpers, test_daily_limit_partial, test_keyword_fallback_when_full_list_empty, test_full_build):
        t(); print("PASS", t.__name__)
    print("ALL PASSED")
