"""
TagoHub 정류장·노선 데이터 만들기 (GitHub Actions 에서 매주 실행).

앱 안 데이터(app/src/main/assets/bus_stops.db)를 바탕으로
  1) TAGO: 앱에 노선 데이터가 없는 지역(pipeline/tago_targets.json)의 노선 목록·노선별 정류장 순서·노선 정보(첫차·막차·배차)
  2) 경기: 노선 번호부(노선 검색 숫자 0~9)로 노선 번호·기점·종점을 최신으로
를 넣은 bus_stops.db 를 만들고, 압축(bus_stops.db.gz)과 안내 파일(manifest.json)을 pipeline/out 에 쓴다
(GitHub Actions 가 Release 'data-latest' 에 덮어 올림 → 앱은 그 manifest.json 을 확인).

- 인증키: 환경변수 TAGO_SERVICE_KEY (공공데이터포털 일반 인증키, 디코딩·인코딩 둘 다 됨)
- 받은 응답은 pipeline/cache 에 저장해 다시 쓰고(노선 목록 1일, 정류장 순서·노선 정보 7일), 하루 호출 한도(코드 22)에 걸리면
  거기까지로 데이터를 만들고 다음 실행에서 이어 받는다 (manifest 의 complete=false).
- 앱이 하던 TAGO 응답 해석과 같은 필드: routeid·routeno·routetp·startnodenm·endnodenm / nodeid·nodenm·nodeno·nodeord·updowncd·gpslati·gpslong /
  startvehicletime·endvehicletime·intervaltime·intervalsattime·intervalsuntime
"""
import datetime, gzip, hashlib, json, os, pathlib, re, shutil, sqlite3, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE_DB = ROOT / "app/src/main/assets/bus_stops.db"
HERE = pathlib.Path(__file__).resolve().parent
# 받은 응답 저장 폴더: 집 PC 러너는 작업 폴더가 실행마다 정리돼서 바깥 고정 폴더(TAGO_CACHE_DIR)를 씀
CACHE = pathlib.Path(os.environ["TAGO_CACHE_DIR"]) if os.environ.get("TAGO_CACHE_DIR") else HERE / "cache"
OUT = HERE / "out"                        # 만드는 중인 DB (올리지 않음)
TAGO = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService"
GBIS = "https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteListv2"
GBIS_STOPS = "https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteStationListv2"
# 노선 DB 에 같이 넣는 앱 파일 (pipeline/build_api_files.py·build_subway_schedule.py --out 이 만듦). 앱은 앱 안 파일보다 이것을 먼저 씀
FILES_DIR = OUT / "files"
FILES = ("subway_schedule.tsv", "ex_places.json", "air_routes.json", "ulsan_crossroads.json", "ferry_ports.json", "ferry_lines.json", "parking_live.json", "subway_fast_exit.json")
FILE_PART = 300_000   # 한글이 많아도 한 칸 1MB 안쪽
# 제주 버스정보 (키 없음)
JEJU = "https://bus.jeju.go.kr/api"
JEJU_WEB = "https://bus.jeju.go.kr"
# 한국교통안전공단_버스 노선 및 시간표 정보 (파일데이터 API, 2026-07-16 기준) — 노선 아이디 = TAGO 의 TSB+번호
KOTSA_TT = "https://api.odcloud.kr/api/15150451/v1/uddi:8711dcd0-1fb8-456f-95a4-95659499de14"
KOTSA_DAYS = {"평일": "weekday", "토요일": "sat", "공휴일": "sun", "일요일": "sun"}
KEYWORDS = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "마을", "급행", "순환", "좌석", "직행"]
DAY = 86400
# 동시에 받는 개수: TAGO 서버가 '가용한 세션이 존재하지 않습니다 (30/30)' 로 자주 바빠서 2개만
WORKERS = 2
# 서버가 바쁠 때(세션 30/30, HTTP_ERROR 04) 기다리는 시간(초) — 그동안 모든 요청이 같이 쉼
BACKOFF = [5, 15, 40, 90]


class Limit(Exception):
    """하루 호출 한도 (TAGO resultCode 22)"""


class KeyError_(Exception):
    """인증키 오류 — 다시 시도해도 소용없으니 바로 멈춤 (등록 안 된 키, 활용신청 안 된 서비스 등)"""


# 공공데이터포털 인증 오류 (returnReasonCode): 20 접근 거부, 30 등록되지 않은 서비스키, 31 기한 만료, 32 등록되지 않은 IP
KEY_ERRORS = ("SERVICE_KEY_IS_NOT_REGISTERED", "SERVICE_ACCESS_DENIED", "DEADLINE_HAS_EXPIRED", "UNREGISTERED_IP")


def busy(text):
    """TAGO·공공데이터포털이 잠깐 바쁜 응답 (실제로 받은 것: resultCode 99 '가용한 세션이 존재하지 않습니다. (30/30)',
    OpenAPI_ServiceResponse 'HTTP_ERROR' returnReasonCode 04) — 기다렸다 다시 시도하면 됨"""
    t = text or ""
    return "가용한 세션" in t or "HTTP_ERROR" in t or "SERVICE_TIME_OUT" in t or '"resultCode":99' in t.replace(" ", "") \
        or '"resultCode":"99"' in t.replace(" ", "")


def key_error(text):
    """인증키 오류면 사람이 읽을 이유, 아니면 None"""
    t = text or ""
    for code in KEY_ERRORS:
        if code in t:
            return code
    return None


# ───────────── 해석 (순수 함수 — 테스트 대상) ─────────────

def items(text):
    """TAGO JSON 의 response.body.items.item — 하나면 객체, 여러 개면 배열, 없으면 빈 문자열"""
    try:
        j = json.loads(text)
    except (TypeError, ValueError):
        return []
    resp = j.get("response", {}) if isinstance(j, dict) else {}
    code = str(resp.get("header", {}).get("resultCode", "00"))
    if code == "22":
        raise Limit()
    it = (resp.get("body", {}) or {}).get("items", {})
    it = it.get("item", []) if isinstance(it, dict) else []
    if isinstance(it, dict):
        it = [it]
    return [x for x in it if isinstance(x, dict)]


def total_count(text):
    """response.body.totalCount (없으면 None)"""
    try:
        return int((json.loads(text).get("response", {}).get("body", {}) or {}).get("totalCount"))
    except (TypeError, ValueError, AttributeError):
        return None


def result_code(text):
    """
    TAGO JSON 의 response.header.resultCode (JSON 이 아니거나 없으면 None).
    경기 GBIS v2 는 response.msgHeader.resultCode (0 = 정상) → "00" 으로 맞춤.
    예전엔 코드가 없을 때 str(None) = "None" 을 돌려줘, 경기 노선 정류장 순서·교통안전공단 시간표 응답을 '비정상'으로 보고
    저장하지 않았음 → 경기 노선 1,700여 개를 매주 처음부터 다시 받다 하루 한도(1,000번)에 걸려 끝내 다 못 받았음 (2026-11-23)
    """
    try:
        resp = json.loads(text).get("response", {}) or {}
        code = (resp.get("header") or {}).get("resultCode")
        if code is None:
            code = (resp.get("msgHeader") or {}).get("resultCode")
            if code is not None and str(code) in ("0", "00"):
                return "00"
        return None if code is None else str(code)
    except (TypeError, ValueError, AttributeError):
        return None


def is_limit(text):
    return "LIMITED_NUMBER_OF_SERVICE_REQUESTS" in (text or "") or '"resultCode":"22"' in (text or "").replace(" ", "")


def clock(v):
    """TAGO 시각 '0530' / 530 → '05:30'"""
    if v is None:
        return None
    s = str(v).strip().split(".")[0]
    if not s.isdigit():
        return None
    s = s.zfill(4)[-4:]
    return f"{s[:2]}:{s[2:]}"


def to_int(v):
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None


def to_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def region_name(name):
    """'경상남도 김해시' → '김해', '부산광역시' → '부산'"""
    last = name.split()[-1]
    for suf in ("광역시", "특별자치시", "특별시", "시", "군"):
        if last.endswith(suf) and len(last) > len(suf):
            return last[: -len(suf)]
    return last


# ───────────── 받기 ─────────────

class Fetcher:
    # 기본값 (시험에서 Fetcher.__new__ 로 만들어도 쓰이게)
    pause_until = 0.0
    busy_hits = 0
    last = ""
    sleep = staticmethod(time.sleep)

    def __init__(self, key, session=None):
        import requests  # 테스트에서는 Fetcher 를 안 써서 requests 없이도 import 가능
        self.key = urllib.parse.unquote(key.strip())  # 인코딩된 키면 풀어서 requests 가 다시 인코딩
        self.s = session or requests.Session()
        self.calls = 0
        self.lock = threading.Lock()
        self.pause_until = 0.0     # 서버가 바쁘면 이 시각까지 모든 요청이 쉼
        self.busy_hits = 0
        self.sleep = time.sleep    # 시험에서 바꿔 끼움
        self.last = ""

    def get(self, url, params, cache_file, max_age):
        f = CACHE / cache_file
        if f.exists() and time.time() - f.stat().st_mtime < max_age:
            return f.read_text(encoding="utf-8")
        tries = len(BACKOFF) + 1
        for attempt in range(tries):
            wait = self.pause_until - time.time()
            if wait > 0:
                self.sleep(wait)
            try:
                with self.lock:
                    self.calls += 1
                r = self.s.get(url, params={"serviceKey": self.key, **params}, timeout=(15, 60))  # 연결 15초, 응답 60초
                text = r.content.decode("utf-8", errors="replace")
                if is_limit(text):
                    raise Limit()
                if key_error(text):
                    raise KeyError_(key_error(text))
                if busy(text):
                    # 서버가 바쁨 → 모두 같이 잠깐 쉬고 다시 (저장하지 않음)
                    with self.lock:
                        self.busy_hits += 1
                        pause = BACKOFF[min(attempt, len(BACKOFF) - 1)]
                        self.pause_until = max(self.pause_until, time.time() + pause)
                    if attempt + 1 < tries:
                        print(f"  서버 바쁨({'세션 30/30' if '가용한 세션' in text else 'HTTP 오류'}) → {pause}초 쉬고 다시 ({attempt + 1}/{tries - 1})", flush=True)
                        continue
                    self.last = text
                    return None
                if r.status_code != 200:
                    raise RuntimeError(f"HTTP {r.status_code}: {text[:200]}")
                self.last = text
                # 정상 응답(TAGO resultCode 00, 또는 경기처럼 header 형식이 다른 것)만 저장 — 이상한 응답이 저장돼 다음 실행까지 0개가 되는 것 방지
                code = result_code(text)
                if code in (None, "00") and (code == "00" or url in (GBIS, KOTSA_TT)):
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_text(text, encoding="utf-8")
                elif code not in (None, "00"):
                    print(f"  비정상 응답 (resultCode {code}, 저장 안 함): {text[:200]}", flush=True)
                self.sleep(0.12)
                return text
            except (Limit, KeyError_):
                raise
            except Exception as e:  # 연결 오류도 같은 간격으로
                print(f"  재시도 {attempt + 1}: {e}", flush=True)
                self.sleep(BACKOFF[min(attempt, len(BACKOFF) - 1)])
        return None


def list_all(fx, url, params, cache_prefix, max_age, rows, with_total=False):
    """totalCount 를 보고 여러 쪽(pageNo)을 모두 받기 (최대 20쪽). with_total 이면 (목록, 첫 쪽의 totalCount)"""
    out = []
    first_total = None
    for page in range(1, 21):
        t = fx.get(url, {**params, "numOfRows": rows, "pageNo": page}, f"{cache_prefix}_p{page}.json", max_age)
        got = items(t)
        out += got
        total = total_count(t) if t else None
        if page == 1:
            first_total = total
        if not got or total is None or len(out) >= total:
            break
    return (out, first_total) if with_total else out


def stops_incomplete(stops, total=None):
    """정류장 순서가 빠져 보이는지: 받은 수가 totalCount 보다 적거나, 순번 사이 빈 번호가 2개 이상이고 전체의 10% 이상
    (앱 RouteStopsCheck.incomplete 와 같은 규칙. 30 넘게 뛰는 것은 방향마다 1001·2001 로 매긴 번호라 셈하지 않음)"""
    if total is not None and len(stops) < total:
        return True
    q = sorted(to_int(s.get("nodeord")) or 0 for s in stops)
    missing = sum(b - a - 1 for a, b in zip(q, q[1:]) if 1 <= b - a - 1 < 30)
    return missing >= 2 and missing >= len(q) * 0.1


def route_stops(fx, code, rid):
    """노선의 정류장 순서. 빠져 보이면(뒤쪽 쪽이 실패해 앞쪽만 저장돼 7일 동안 그대로 쓰던 것 등) 저장본 없이 한 번 더 받아 더 많은 쪽을 씀"""
    url = f"{TAGO}/getRouteAcctoThrghSttnList"
    params = {"_type": "json", "cityCode": code, "routeId": rid}
    prefix = f"tago/{code}/stops_{rid}"
    got, total = list_all(fx, url, params, prefix, 7 * DAY, 500, with_total=True)
    if got and stops_incomplete(got, total):
        again, _ = list_all(fx, url, params, prefix, 0, 500, with_total=True)
        if len(again) > len(got):
            got = again
    return got


def run_parallel(fx, jobs, fn, label):
    """WORKERS 개씩 동시에. 하루 한도(Limit)가 나면 남은 일은 건너뛰고 Limit 을 다시 던짐"""
    stop = threading.Event()

    def one(job):
        if stop.is_set():
            return
        try:
            fn(job)
        except Limit:
            stop.set()
            raise

    hit = False
    done = 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for f in as_completed([ex.submit(one, j) for j in jobs]):
            try:
                f.result()
            except Limit:
                hit = True
            done += 1
            if done % 200 == 0 or done == len(jobs):
                print(f"  {label} {done}/{len(jobs)} (호출 {fx.calls})", flush=True)
    if hit:
        raise Limit()


def fetch_tago(fx, targets):
    """{city: {"name": .., "routes": {routeid: {"route": item, "stops": [...], "info": item|None}}}} 와 완료 여부"""
    data, complete = {}, True
    try:
        for code, name in targets:
            routes = {}
            # 활용가이드상 routeNo 는 필수가 아님 → 도시 전체 노선을 먼저 (호출 1~2번). 비어 있으면 검색어로
            found = list_all(fx, f"{TAGO}/getRouteNoList", {"_type": "json", "cityCode": code}, f"tago/{code}/routes_all", DAY, 1000)
            batches = [found] if found else [
                items(fx.get(f"{TAGO}/getRouteNoList", {"_type": "json", "cityCode": code, "routeNo": kw, "numOfRows": 1000, "pageNo": 1},
                             f"tago/{code}/routes_{kw.encode().hex()}.json", DAY)) for kw in KEYWORDS]
            for batch in batches:
                for it in batch:
                    if it.get("routeid"):
                        rid = str(it["routeid"])
                        prev = routes.get(rid, {"route": {}, "stops": [], "info": None})
                        # 같은 노선이 여러 검색어에 나오면 비어 있지 않은 값만 합침 (먼저 받은 기점·종점이 지워지지 않게)
                        prev["route"] = {**prev["route"], **{k: v for k, v in it.items() if v not in (None, "")}}
                        routes[rid] = prev
            data[code] = {"name": name, "routes": routes}
            print(f"{name} ({code}): 노선 {len(routes)}개 (호출 {fx.calls})", flush=True)
            if not routes:
                # 왜 0개인지: 이 지역의 응답 (전에는 시작할 때 받은 인증키 확인 응답(대전)이 잘못 찍혔음)
                own = CACHE / f"tago/{code}/routes_all_p1.json"
                txt = own.read_text(encoding='utf-8') if own.exists() else (getattr(fx, 'last', '') or '')
                why = 'TAGO 에 이 지역 노선 데이터가 없음 (정상 응답, 0개)' if total_count(txt) == 0 else txt[:300]
                print(f"  ↳ 노선 0개 — {why}", flush=True)
        jobs = [(code, rid, r) for code, c in data.items() for rid, r in c["routes"].items()]

        def stops_of(job):
            code, rid, r = job
            r["stops"] = route_stops(fx, code, rid)

        def info_of(job):
            code, rid, r = job
            t = fx.get(f"{TAGO}/getRouteInfoIem", {"_type": "json", "cityCode": code, "routeId": rid}, f"tago/{code}/info_{rid}.json", 7 * DAY)
            info = items(t)
            r["info"] = info[0] if info else None

        run_parallel(fx, jobs, stops_of, "정류장 순서")
        gaps = sum(1 for _, _, r in jobs if r["stops"] and stops_incomplete(r["stops"]))
        print(f"  정류장 순서가 아직 빠져 보이는 노선 {gaps}개 (다시 받아도 TAGO 가 그렇게 줌)", flush=True)
        run_parallel(fx, jobs, info_of, "노선 정보")
    except Limit:
        print(f"하루 호출 한도에 도달 (호출 {fx.calls}) — 받은 데까지로 만들고 다음 실행에서 이어 받음", flush=True)
        complete = False
    return data, complete


def fetch_gbis(fx):
    """경기 노선 번호부: [(routeId, routeName, start, end)]"""
    out = {}
    try:
        for kw in "0123456789":
            t = fx.get(GBIS, {"format": "json", "keyword": kw}, f"gbis/routes_{kw}.json", DAY)
            try:
                rows = json.loads(t or "{}").get("response", {}).get("msgBody", {}).get("busRouteList", [])
            except ValueError:
                rows = []
            if isinstance(rows, dict):
                rows = [rows]
            for r in rows:
                rid = str(r.get("routeId", "")).split(".")[0]
                if rid:
                    out[rid] = (rid, str(r.get("routeName", "")).strip(), r.get("startStationName"), r.get("endStationName"))
    except Limit:
        print("경기 번호부: 호출 한도", flush=True)
    return list(out.values())


def fetch_gbis_stops(fx, base_db, gbis_routes):
    """경기: 정류장 순서가 DB 에 없는 노선의 노선 정류장 목록을 모두 받음 {GGB 노선 id: [행]} (받은 것은 30일 저장). 하루 한도면 받은 데까지"""
    db = sqlite3.connect(base_db)
    have = {r[0] for r in db.execute("SELECT DISTINCT route_id FROM route_stops WHERE route_id LIKE 'GGB%'")}
    db.close()
    out, new, limited, stale = {}, 0, False, 0
    for rid, *_ in gbis_routes:
        gid = "GGB" + rid
        if gid in have:
            continue
        cache = f"gbis/stops_{rid}.json"
        t = None
        if not limited:
            if not (CACHE / cache).exists():
                new += 1
            try:
                t = fx.get(GBIS_STOPS, {"format": "json", "routeId": rid}, cache, 30 * DAY)
            except Limit:
                limited = True
                print("경기 노선 정류장 순서: 호출 한도 — 남은 노선은 저장해 둔 것(30일 넘은 것도)으로", flush=True)
        if t is None and (CACHE / cache).exists():
            # 한도에 걸렸거나 못 받았으면 지난번에 받은 것 (노선 정류장은 거의 안 바뀜)
            t = (CACHE / cache).read_text(encoding="utf-8")
            stale += 1
        try:
            rows = json.loads(t or "{}").get("response", {}).get("msgBody", {}).get("busRouteStationList", [])
        except ValueError:
            rows = []
        if isinstance(rows, dict):
            rows = [rows]
        if rows:
            out[gid] = rows
    print(f"경기 노선 정류장 순서: {len(out)}개 노선 (새로 받음 {new}, 저장본 {stale}, 이미 있던 노선 {len(have)})", flush=True)
    return out


def write_gbis_stops(db, stops, known):
    """경기 노선 정류장 순서 (앱 Gyeonggi.routeStops 와 같음: 회차점까지 상행 0, 그 뒤 하행 1)"""
    for gid, rows in stops.items():
        rows = sorted((r for r in rows if r.get("stationId")), key=lambda r: to_int(r.get("stationSeq")) or 0)
        turn = next((i for i, r in enumerate(rows) if str(r.get("turnYn", "")).upper() == "Y"), -1)
        out = []
        for i, r in enumerate(rows):
            sid = "GGB" + str(r["stationId"]).split(".")[0]
            ud = None if turn < 0 else ("0" if i <= turn else "1")
            if sid in known:
                out.append((gid, to_int(r.get("stationSeq")) or i + 1, sid, "", None, None, ud))
            else:
                out.append((gid, to_int(r.get("stationSeq")) or i + 1, sid, str(r.get("stationName", "")).strip() or "정류장",
                            to_float(r.get("y")), to_float(r.get("x")), ud))
        db.execute("DELETE FROM route_stops WHERE route_id = ?", (gid,))
        db.executemany("INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)", out)
    if stops:
        print(f"경기 노선 {len(stops)}개의 정류장 순서를 DB 에 넣음", flush=True)


def write_files(db, folder):
    """
    앱 파일을 DB 의 files 표로 (없는 파일은 넣지 않음 → 앱은 앱 안 파일).
    안드로이드는 한 칸이 2MB 를 넘으면 못 읽어서(CursorWindow) 30만 글자씩 나눠 넣고 앱이 part 순서로 이어 붙임
    """
    db.execute("DROP TABLE IF EXISTS files")
    db.execute("CREATE TABLE files(name TEXT NOT NULL, part INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(name, part)) WITHOUT ROWID")
    n = 0
    for name in FILES:
        f = pathlib.Path(folder) / name
        if f.exists() and f.stat().st_size > 1000:
            text = f.read_text(encoding="utf-8")
            db.executemany("INSERT INTO files VALUES(?,?,?)", [(name, i // FILE_PART, text[i:i + FILE_PART]) for i in range(0, len(text), FILE_PART)])
            n += 1
    print(f"앱 파일 {n}개를 DB 에 넣음", flush=True)
    return n


def jeju_ssl_context():
    """
    제주 버스정보(bus.jeju.go.kr)는 중간 인증서를 보내지 않아서(Sectigo DV R36), 그 인증서를 미리 갖고 있지 않은 계정
    (데이터 갱신 러너 서비스 = LocalSystem)의 파이썬은 CERTIFICATE_VERIFY_FAILED 로 바로 못 붙음. 그러면 노선 990개를
    모두 중계 서버로 물었고, 중계 서버 → 제주가 자주 502 를 내던 2026-11-22 에는 몇 시간이 걸렸음.
    공개 중간 인증서(pipeline/certs, 2036-03 만료)를 같이 믿게 해서 바로 붙음. 인증서 확인은 그대로 함
    """
    import ssl
    ctx = ssl.create_default_context()
    try:
        import certifi
        ctx.load_verify_locations(cafile=certifi.where())
    except Exception:
        pass
    pem = pathlib.Path(__file__).resolve().parent / "certs" / "sectigo_dv_r36.pem"
    if pem.exists():
        ctx.load_verify_locations(cafile=str(pem))
    return ctx


def fetch_jeju(get=None):
    """
    제주: 노선 목록(한 번) + 노선마다 정류장 목록 → {노선 id: {"route": 노선 행, "stops": [행]}} 와 다 받았는지 (노선 약 990개, 2026-11)
    """
    import urllib.request

    err = [""]

    def via_proxy(path):
        """중계 서버로 (데이터 갱신 PC 서비스 계정에서 제주 서버에 바로 못 붙던 적이 있음: '제주 노선 목록을 받지 못함')"""
        if path == "searchBusRouteList.do":
            return f"{WEB_BASE}/w/bus/jeju/routes/all"
        m = re.match(r"searchBusRouteStationList\.do\?route_id=(\d{9})$", path)
        if m:
            return f"{WEB_BASE}/w/bus/jeju/stops/{m.group(1)}"
        m = re.match(r"line/(\d{9})$", path)
        return f"{WEB_BASE}/w/bus/jeju/line/{m.group(1)}" if m else None

    def direct_url(path):
        """제주 서버 주소와 보낼 내용. 노선 종류(급행·간선·지선…)는 제주 버스정보 누리집 노선 정보(POST)에만 있음 (API 노선 목록에는 없음)"""
        m = re.match(r"line/(\d{9})$", path)
        if m:
            return JEJU_WEB + "/data/search/getLineInfoByLineId", f"lineId={m.group(1)}".encode()
        return f"{JEJU}/{path}", None

    ctx = jeju_ssl_context()
    direct = [True]  # 제주 서버에 바로 붙기가 한 번 다 실패하면 남은 노선은 처음부터 중계 서버로 (노선마다 100초씩 기다리면 갱신이 시간 한도에 걸림)

    def fetch(path):
        own, body = direct_url(path)
        for url in (own if direct[0] else None, via_proxy(path)):
            if not url:
                continue
            post = body if url == own else None
            for wait in ((0, 3) if path.startswith("line/") else (0, 3, 10)):
                time.sleep(wait)
                try:
                    req = urllib.request.Request(url, data=post, headers={"User-Agent": "Mozilla/5.0 TagoHub-pipeline",
                                                                          **({"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"} if post else {})})
                    with urllib.request.urlopen(req, timeout=30, context=ctx if url == own else None) as r:
                        return json.loads(r.read().decode("utf-8"))
                except Exception as e:
                    err[0] = f"{type(e).__name__}: {str(e)[:120]}"
            if url == own and via_proxy(path):
                direct[0] = False
                print(f"제주 서버에 바로 못 붙음 ({err[0]}), 이제부터 중계 서버로", flush=True)
        return None
    get = get or fetch
    routes = get("searchBusRouteList.do")
    if not isinstance(routes, list) or not routes:
        print(f"제주 노선 목록을 받지 못함 ({err[0]})", flush=True)
        return {}, False
    out, ok = {}, True
    for r in routes:
        rid = str(r.get("ROUTE_ID", "")).split(".")[0]
        if not rid or rid in out:
            continue
        st = get(f"searchBusRouteStationList.do?route_id={rid}")
        if not isinstance(st, list):
            ok = False
            continue
        out[rid] = {"route": r, "stops": st}
    # 노선 종류: 같은 번호(101-11·101-12 …)는 종류가 같아서 번호마다 한 번만 (번호 약 250개). 못 받아도 노선은 그대로 넣음
    kinds, missed = {}, 0
    for rid, r in out.items():
        num = str(r["route"].get("ROUTE_NUM", "")).strip()
        if num not in kinds:
            info = get(f"line/{rid}")
            kinds[num] = jeju_type(info.get("busTypeStr")) if isinstance(info, dict) else None
            missed += kinds[num] is None
        r["type"] = kinds[num]
    print(f"제주 노선 {len(out)}개 (다 받음 {ok}, 종류 못 받은 번호 {missed}/{len(kinds)})", flush=True)
    return out, ok


def jeju_type(t):
    """제주 버스정보 노선 종류(busTypeStr) → 앱에 보일 이름. 급행·일반간선·간선·지선·읍면지선·심야·관광지순환·마을은 그대로, 리무진은 공항리무진"""
    t = str(t or "").strip()
    return "공항리무진" if t == "리무진" else (t or None)


def write_jeju(db, routes, known):
    """제주 노선·정류장 순서를 DB 에 (실시간은 앱이 제주 API 로: realtime = 'jeju'). 다 받았을 때만 부름"""
    db.execute("DELETE FROM route_stops WHERE route_id LIKE 'JEB%'")
    db.execute("DELETE FROM routes WHERE id LIKE 'JEB%'")
    n = 0
    for rid, r in routes.items():
        it = r["route"]
        st = sorted((s for s in r["stops"] if s.get("STATION_ID")), key=lambda s: to_int(s.get("STATION_ORD")) or 0)
        if not st:
            continue
        jid = "JEB" + rid
        rows = []
        for i, s in enumerate(st):
            sid = "JEB" + str(s["STATION_ID"]).split(".")[0]
            rows.append((jid, to_int(s.get("STATION_ORD")) or i + 1, sid, "" if sid in known else (str(s.get("STATION_NM", "")).strip() or "정류장"), None, None, None))
        db.executemany("INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)", rows)
        db.execute("INSERT OR REPLACE INTO routes(id,no,start_name,end_name,start_node,end_node,city,region,rtype,realtime) VALUES(?,?,?,?,?,?,?,?,?,?)",
                   (jid, str(it.get("ROUTE_NUM", "")).strip(), it.get("ORGT_NM"), it.get("DST_NM"), rows[0][2], rows[-1][2], "39", "제주", r.get("type"), "jeju"))
        n += 1
    print(f"제주 노선 {n}개를 DB 에 넣음", flush=True)
    return n


# ───────────── 쓰기 ─────────────

# ───────────── 교통안전공단 시간표 ─────────────

def kotsa_rows(text):
    """파일데이터 API 응답의 data (오류·빈 응답이면 [])"""
    try:
        d = json.loads(text or "{}")
    except ValueError:
        return []
    return [r for r in d.get("data", []) if isinstance(r, dict)] if isinstance(d, dict) else []


def kotsa_total(text):
    try:
        return int(json.loads(text).get("totalCount"))
    except (TypeError, ValueError, AttributeError):
        return None


def fetch_kotsa(fx):
    """전체 행 (1,000행씩, 약 11번)"""
    out = []
    try:
        for page in range(1, 40):
            t = fx.get(KOTSA_TT, {"page": page, "perPage": 1000, "returnType": "JSON"}, f"kotsa/tt_p{page}.json", 7 * DAY)
            got = kotsa_rows(t)
            out += got
            total = kotsa_total(t) if t else None
            if not got or total is None or len(out) >= total:
                break
    except (Limit, KeyError_) as e:
        print(f"교통안전공단 시간표: 받지 못함 ({e}) — 활용신청을 확인하세요", flush=True)
    print(f"교통안전공단 시간표: {len(out)}행", flush=True)
    return out


def kotsa_cities(base_db):
    """교통안전공단 노선(TSB)이 있는 TAGO 도시코드 — 비교용 TAGO 노선 목록을 받을 지역"""
    db = sqlite3.connect(base_db)
    cities = [r[0] for r in db.execute("SELECT DISTINCT city FROM routes WHERE id LIKE 'TSB%' AND city NOT LIKE 'TS%'")]
    db.close()
    return cities


def fetch_tago_lists(fx, cities):
    """비교용: 이 지역들의 TAGO 노선 목록 (첫차·막차가 같이 옴). {routeid: item}"""
    out = {}
    try:
        for code in cities:
            for it in list_all(fx, f"{TAGO}/getRouteNoList", {"_type": "json", "cityCode": code}, f"tago/{code}/routes_all", DAY, 1000):
                if it.get("routeid"):
                    out[str(it["routeid"])] = it
    except (Limit, KeyError_):
        print("비교용 TAGO 노선 목록: 한도 — 받은 데까지만 비교", flush=True)
    return out


def fetch_tsb_stops(fx, base_db):
    """교통안전공단 노선(TSB)의 정류장 순서 — 앱이 TAGO 에 매번 묻지 않게 (TAGO 도시코드로). {routeid: [stop items]}"""
    db = sqlite3.connect(base_db)
    jobs = [(city, rid, {"stops": []}) for rid, city in db.execute(
        "SELECT r.id, r.city FROM routes r WHERE r.id LIKE 'TSB%' AND r.city NOT LIKE 'TS%' "
        "AND NOT EXISTS (SELECT 1 FROM route_stops s WHERE s.route_id = r.id)")]
    db.close()

    def one(job):
        code, rid, r = job
        r["stops"] = route_stops(fx, code, rid)
    try:
        run_parallel(fx, jobs, one, "교통안전공단 노선 정류장 순서")
    except (Limit, KeyError_):
        print("교통안전공단 노선 정류장 순서: 한도 — 받은 데까지만", flush=True)
    return {rid: r["stops"] for _, rid, r in jobs if r["stops"]}


def fetch_tsb_info(fx, base_db):
    """
    교통안전공단 노선(TSB) 중 첫차·막차가 DB 에 없는 노선의 TAGO 노선 정보 {routeid: item} (2026-11: 7,239개 중 약 5,200개, 구례 67개 모두 없었음).
    교통안전공단 시간표 자료에 없는 지역(구례 등)도 TAGO 노선 정보에는 첫차·막차·배차가 있음. 받은 것은 7일 저장
    """
    db = sqlite3.connect(base_db)
    has_sched = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE name = 'route_schedule'")}
    sql = ("SELECT r.id, r.city FROM routes r WHERE r.id LIKE 'TSB%' AND r.city NOT LIKE 'TS%' "
           "AND NOT EXISTS (SELECT 1 FROM route_info i WHERE i.id = r.id AND (i.first IS NOT NULL OR i.last IS NOT NULL))")
    if has_sched:
        sql += " AND NOT EXISTS (SELECT 1 FROM route_schedule s WHERE s.id = r.id)"
    jobs = [(city, rid, {}) for rid, city in db.execute(sql)]
    db.close()

    def one(job):
        code, rid, r = job
        t = fx.get(f"{TAGO}/getRouteInfoIem", {"_type": "json", "cityCode": code, "routeId": rid}, f"tago/{code}/info_{rid}.json", 7 * DAY)
        got = items(t)
        if got:
            r.update(got[0])
    try:
        run_parallel(fx, jobs, one, "교통안전공단 노선 첫차·막차")
    except (Limit, KeyError_):
        print("교통안전공단 노선 첫차·막차: 한도 — 받은 데까지만", flush=True)
    out = {rid: r for _, rid, r in jobs if r}
    print(f"교통안전공단 노선 첫차·막차: {len(jobs)}개 중 {len(out)}개 받음", flush=True)
    return out


def incheon_current(rid):
    """인천 노선 ID: 앱 안 파일의 예전 ID(ICB1…) → TAGO 지금 ID(ICB3…) (앱 TagoIds.current 와 같음)"""
    if len(rid) < 5 or not rid.startswith("ICB"):
        return rid
    return {"1": "ICB3", "2": "ICB4"}.get(rid[3], rid[:4]) + rid[4:]


def fetch_incheon_info(fx, base_db):
    """
    인천 노선(앱 안 파일 '버스노선별 정류장 현황')의 TAGO 노선 정보 {예전 노선 ID: item}.
    파일은 엑셀을 거치며 번호가 날짜로 바뀐 것(5-1 → '05월 01일')이 있고 종류·첫차·막차가 비어 있어서 TAGO 로 채움 (노선 약 360개, 7일 저장).
    TAGO 는 지금 ID(ICB3…)로만 답함
    """
    db = sqlite3.connect(base_db)
    jobs = [(rid, {}) for (rid,) in db.execute("SELECT id FROM routes WHERE id LIKE 'ICB%'")]
    db.close()

    def one(job):
        rid, r = job
        cur = incheon_current(rid)
        got = items(fx.get(f"{TAGO}/getRouteInfoIem", {"_type": "json", "cityCode": "23", "routeId": cur}, f"tago/23/info_{cur}.json", 7 * DAY))
        if got:
            r.update(got[0])
    try:
        run_parallel(fx, jobs, one, "인천 노선 정보")
    except (Limit, KeyError_):
        print("인천 노선 정보: 한도 — 받은 데까지만", flush=True)
    out = {rid: r for rid, r in jobs if r}
    print(f"인천 노선 정보: {len(jobs)}개 중 {len(out)}개 받음", flush=True)
    return out


def write_incheon_info(db, info):
    """인천 노선 번호·종류를 TAGO 노선 정보로 (번호는 TAGO 가 줄 때만 바꿈), 첫차·막차·배차는 write_tsb_info 와 같이. 바꾼 노선 수"""
    n = 0
    for rid, it in info.items():
        no = str(it.get("routeno") or "").strip()
        tp = str(it.get("routetp") or "").strip() or None
        n += db.execute("UPDATE routes SET no = COALESCE(NULLIF(?, ''), no), rtype = COALESCE(?, rtype) WHERE id = ?", (no, tp, rid)).rowcount
    write_tsb_info(db, info)
    print(f"인천 노선 번호·종류를 TAGO 로: {n}개", flush=True)
    return n


def tidy_routes(db):
    """
    모든 노선에 공통으로: 번호 끝 '번' 지움(원주 '52-1번' → '52-1': 앱이 이 번호로 TAGO 노선을 찾는데 TAGO 는 '52-1' 이라 실시간이 안 붙었음),
    도시 노선이 전부 '시외버스'인 곳(TAGO 의 안동·예천: 시내버스인데 종류가 시외버스로 옴)은 '일반버스'로. 고친 수 (번호, 종류)
    """
    a = db.execute("UPDATE routes SET no = substr(no, 1, length(no) - 1) WHERE no GLOB '*[0-9]번'").rowcount
    wrong = [c for (c,) in db.execute("SELECT city FROM routes WHERE realtime = 'tago' GROUP BY city "
                                       "HAVING COUNT(*) >= 20 AND SUM(rtype = '시외버스') = COUNT(*)")]
    b = sum(db.execute("UPDATE routes SET rtype = '일반버스' WHERE city = ? AND realtime = 'tago'", (c,)).rowcount for c in wrong)
    print(f"노선 정리: 번호 끝 '번' {a}개, 시외버스로 잘못 온 시내버스 {b}개 (도시 {wrong})", flush=True)
    return a, b


def write_tsb_info(db, info):
    """TAGO 노선 정보의 첫차·막차·배차를 route_info 에 (이미 시간이 있는 노선은 그대로). 넣은 수"""
    n = 0
    for rid, it in info.items():
        first, last = clock(it.get("startvehicletime")), clock(it.get("endvehicletime"))
        gap = to_int(it.get("intervaltime")) or None
        if not (first or last or gap):
            continue
        old = db.execute("SELECT first, last FROM route_info WHERE id = ?", (rid,)).fetchone()
        if old and (old[0] or old[1]):
            continue
        db.execute("INSERT OR REPLACE INTO route_info VALUES(?,?,?,?,?,?)",
                   (rid, first, last, gap, to_int(it.get("intervalsattime")) or None, to_int(it.get("intervalsuntime")) or None))
        n += 1
    print(f"교통안전공단 노선 {n}개의 첫차·막차를 넣음", flush=True)
    return n


STTN = "https://apis.data.go.kr/1613000/BusSttnInfoInqireService"
# TAGO 정류장 경유노선으로 지울지 정할 때: 노선이 있는 걸로 아는 정류장을 이만큼 물어 보고, 이 비율 넘게 노선이 나와야 그 지역 답을 믿음
TAGO_CHECK_SAMPLE = 15
TAGO_CHECK_TRUST = 0.8
# 지역 API 로 노선을 모으는 곳(TAGO 가 정류장 경유노선을 주지 않음)
LOCAL_ROUTE_PREFIX = {"JUB", "JEB"}


def sttn_route_count(fx, city, sid):
    """TAGO 정류장 경유노선 수 (정상 응답이면 0 이상, 오류·못 받음이면 None). 30일 저장"""
    t = fx.get(f"{STTN}/getSttnThrghRouteList", {"_type": "json", "cityCode": city, "nodeid": sid, "numOfRows": 1, "pageNo": 1},
               f"tago/{city}/sttnroutes_{sid}.json", 30 * DAY)
    try:
        j = json.loads(t or "")
    except ValueError:
        return None
    resp = j.get("response", {}) if isinstance(j, dict) else {}
    code = str(resp.get("header", {}).get("resultCode", ""))
    if code == "22":
        raise Limit()
    if code != "00":
        return None
    total = (resp.get("body") or {}).get("totalCount")
    return to_int(total) if total is not None else None


def fetch_unserved_check(fx, base_db, extra_used=()):
    """
    노선 정류장 표에 없는 정류장마다 TAGO 에 경유노선을 물어 {정류장 id: 노선 수} 와 믿을 만한 묶음(지역 접두사|도시코드) 목록을 돌려줌.
    노선 자료가 일부만 있는 지역(경기·교통안전공단·경산·원주·세종 …)은 표에 없다고 지우면 쓰는 정류장까지 지워져서, TAGO 가 0 이라고 할 때만 지움.
    지역마다 노선이 있는 걸로 아는 정류장 몇 곳을 먼저 물어 TAGO 답을 믿을 수 있는지 봄 (TAGO 가 경유노선을 안 주는 지역은 빼려고).
    """
    db = sqlite3.connect(base_db)
    alias = {a: t for a, t in db.execute("SELECT code, tago FROM city_alias")} if db.execute(
        "SELECT 1 FROM sqlite_master WHERE name = 'city_alias'").fetchone() else {}
    # 이번에 새로 받은 노선 정류장(TAGO·교통안전공단·경기·제주)도 '노선 있음'으로 (물을 필요 없음)
    used = {r[0] for r in db.execute("SELECT DISTINCT stop_id FROM route_stops WHERE stop_id IS NOT NULL")} | set(extra_used)
    stops = [(sid, alias.get(city, city)) for sid, city in db.execute("SELECT id, city FROM stops")]
    db.close()
    stops = [(sid, city) for sid, city in stops if prefix(sid) not in LOCAL_ROUTE_PREFIX and city and not str(city).startswith("TS")]
    # 믿을 만한지는 지역 접두사 + 도시마다 봄: 같은 충남(CNB)이라도 청양·태안은 TAGO 답이 맞는데 다른 시군은 0 으로만 답해서,
    # 접두사 하나로 보면 충남 전체를 못 믿어 청양의 버스 안 서는 정류장 1,193곳을 못 지웠음 (2026-11-23)
    by_group = {}
    for sid, city in stops:
        by_group.setdefault(group_key(sid, city), []).append((sid, city))
    todo = {g for g, lst in by_group.items() if any(sid not in used for sid, _ in lst)}
    counts, sample_ok, sample_n = {}, {}, {}
    lock = threading.Lock()

    def one(job):
        kind, g, sid, city = job
        n = sttn_route_count(fx, city, sid)
        with lock:
            if kind == "sample":
                if n is not None:
                    sample_n[g] = sample_n.get(g, 0) + 1
                    sample_ok[g] = sample_ok.get(g, 0) + (n > 0)
            elif n is not None:
                counts[sid] = n
    samples = [("sample", g, sid, city) for g in sorted(todo) for sid, city in [x for x in by_group[g] if x[0] in used][:TAGO_CHECK_SAMPLE]]
    checks = [("check", g, sid, city) for g in sorted(todo) for sid, city in by_group[g] if sid not in used]
    try:
        run_parallel(fx, samples, one, "TAGO 경유노선 믿을 만한지")
        run_parallel(fx, checks, one, "노선 없는 정류장 TAGO 확인")
    except (Limit, KeyError_):
        print("노선 없는 정류장 TAGO 확인: 한도 — 받은 데까지만 (못 물어본 정류장은 지우지 않음)", flush=True)
    # 노선이 있는 걸로 아는 정류장이 하나도 없는 곳(칠곡처럼 노선 자료가 아예 없음)은 그곳 정류장 중 노선이 나온 비율로 봄
    for g in todo:
        if g not in sample_n:
            got = [counts[sid] for sid, _ in by_group[g] if sid in counts]
            if got:
                sample_n[g], sample_ok[g] = len(got), sum(1 for n in got if n > 0)
    trusted = {g for g in todo if sample_n.get(g, 0) >= 5 and sample_ok.get(g, 0) / sample_n[g] >= TAGO_CHECK_TRUST}
    zero = sum(1 for g in trusted for sid, _ in by_group[g] if counts.get(sid) == 0)
    print(f"노선 없는 정류장 TAGO 확인: {len(checks)}곳 중 {len(counts)}곳 답 받음, 믿을 만한 지역·도시 {len(trusted)}/{len(todo)}, 노선 0개 {zero}곳", flush=True)
    return counts, trusted


def group_key(sid, city):
    """TAGO 답을 믿을지 정하는 묶음: 지역 접두사|TAGO 도시코드 (예: CNB|34350)"""
    return f"{prefix(sid)}|{city}"


def compare(kotsa, tago_items):
    """같은 노선(TSB+번호 = TAGO routeid)의 평일 기점 첫차·막차가 일치하는 비율"""
    week = {"TSB" + str(r.get("노선 아이디")): r for r in kotsa if r.get("요일") == "평일"}
    both = [rid for rid in week if rid in tago_items]
    def same(a, b):
        return a is not None and b is not None and a == b
    first = sum(same(clock(tago_items[r].get("startvehicletime")), (week[r].get("기점첫차출발시각") or None)) for r in both)
    last = sum(same(clock(tago_items[r].get("endvehicletime")), (week[r].get("기점막차출발시각") or None)) for r in both)
    tago_has = sum(clock(tago_items[r].get("startvehicletime")) is not None for r in both)
    return {
        "sameRoutes": len(both),
        "kotsaOnly": len(set(week) - set(tago_items)),
        "tagoOnly": len([r for r in tago_items if r.startswith("TSB") and r not in week]),
        "firstMatch": round(first / len(both) * 100, 1) if both else None,
        "lastMatch": round(last / len(both) * 100, 1) if both else None,
        "tagoHasFirst": round(tago_has / len(both) * 100, 1) if both else None,
    }


# 노선 없는 정류장 지우기: 정류장 id 앞 영문자(지역)마다 노선 정류장 자료가 이만큼 넘게 있으면 그 지역 자료는 믿을 만하다고 보고,
# 노선이 하나도 안 서는 정류장(폐지·미사용)을 지움. 그보다 덜 덮인 지역은 정류장마다 TAGO 경유노선으로 확인([fetch_unserved_check]).
# (예전 0.5 → 노선 자료가 70% 남짓인 지역에서 쓰는 정류장까지 지워질 수 있었음)
PRUNE_MIN = 0.95
# 지우지 않는 지역: 경기(GGB)는 GBIS 노선 중 정류장 순서가 있는 것이 일부(약 2/3)뿐, 교통안전공단(TSB)은 정류장 순서가 거의 없음
PRUNE_KEEP = {"GGB", "TSB"}
# 노선 자료가 DB 에 없어 그 지역 API 로 노선이 서는 정류장을 모으는 곳 (중계 서버 /w/bus/jeonju/… 를 거쳐 하루 저장본을 씀, 인증키 필요 없음)
WEB_BASE = os.environ.get("TAGOHUB_WEB", "https://tabolkka.pages.dev").rstrip("/")


def prefix(sid):
    """정류장 id 앞의 영문자 (지역): DJB8001793 → DJB"""
    i = 0
    while i < len(sid) and sid[i].isalpha():
        i += 1
    return sid[:i]


def jeonju_urls(path, key=None):
    """
    전주시 버스 API 를 물을 주소들, 먼저 물을 것부터: 이 PC 에서 공공데이터포털에 바로(인증키가 있을 때), 그다음 중계 서버(하루 저장본).
    예전엔 중계 서버만 썼는데, Cloudflare 가 공공데이터포털에 못 붙는 동안(2026-11-22, 522) 호출마다 20초 넘게 걸려
    전주 노선 800번 남짓을 모으는 데 몇 시간이 걸렸음 (같은 때 이 PC 에서 바로 물으면 0.1초). path: "routes/<정류장 std>" · "stops/<노선 std>"
    """
    key = (os.environ.get("TAGO_SERVICE_KEY", "") if key is None else key).strip()
    kind, _, i = path.partition("/")
    out = []
    if key and i.isdigit():
        k = urllib.parse.quote(urllib.parse.unquote(key), safe="")
        if kind == "routes":
            out.append(f"https://apis.data.go.kr/4641000/busstop/general_busstop_common?stopStandardid={i}&stopStdid={i}&serviceKey={k}")
        elif kind == "stops":
            out.append(f"https://apis.data.go.kr/4641000/realtime/bus_location_bus_position_common?brtStdid={i}&serviceKey={k}")
    out.append(f"{WEB_BASE}/w/bus/jeonju/{path}")
    return out


# 전주 요청이 이만큼 잇달아 실패하면 남은 전주 요청은 건너뜀. 2026-11-22: 바로 묻기는 하루 한도 초과, 중계 서버는 502(20초)라
# 요청마다 1분 넘게 걸려 전주 800번에 갱신이 몇 시간 늘어졌음. 건너뛰면 다 받음 False 라 지난 전주 노선이 그대로 남음
JEONJU_GIVE_UP = 8
# 전주 수집에 쓰는 시간 한도(초). 중계 서버가 저장본만 가끔 답하면(나머지는 20초 뒤 502) "잇달아 실패" 가 자꾸 0 으로 돌아가
# 8번 규칙만으로는 안 끝났음 (2026-11-22, 1시간 넘게). 넘으면 남은 요청은 건너뜀 (다 받음 False → 지난 전주 노선 그대로)
JEONJU_BUDGET_S = 600


def fetch_jeonju_served(get=None):
    """
    전주: 노선마다 정류장 목록을 받아 지금 노선이 서는 정류장 {정류장 id: 지금 이름}, 다 받았는지, 노선 {노선 std: {"route": 노선 칸, "stops": [(순번, 정류장 std, 이름)]}}.
    노선·정류장 순서는 DB 에 넣어 앱이 전주 API 를 다시 부르지 않게.
    정류장 하나의 경유 노선 → 그 노선들의 정류장 목록 → 아직 안 나온 정류장의 경유 노선 … (노선 340개, 호출 약 800번, 2026-11 실측)
    """
    import re, urllib.request

    def fetch(path):
        # 잇달아 여러 번 못 받았으면 남은 요청은 건너뜀 (다 받음 False → DB 의 지난 전주 노선을 그대로 둠)
        if misses[0] >= JEONJU_GIVE_UP:
            return None
        if not started:
            started.append(time.monotonic())
        elif time.monotonic() - started[0] > JEONJU_BUDGET_S:
            if misses[0] < JEONJU_GIVE_UP:
                misses[0] = JEONJU_GIVE_UP
                print(f"전주: {JEONJU_BUDGET_S // 60}분이 넘어 남은 전주 요청은 건너뜀 (지난 전주 노선 그대로)", flush=True)
            return None
        for wait in (0, 3, 10):
            time.sleep(wait)
            # 이 PC 에서 전주시 API 에 바로 묻고, 안 되면 중계 서버로. 바로 묻기가 하루 한도에 걸렸으면 그날은 중계 서버만
            urls = jeonju_urls(path)
            if not direct_ok[0]:
                urls = urls[-1:]
            for url in urls:
                try:
                    # 이름표 없는 파이썬 기본 요청은 서버 앞단에서 403 으로 막힘
                    req = urllib.request.Request(url, headers={"User-Agent": "TagoHub-pipeline", "Accept": "application/xml"})
                    with urllib.request.urlopen(req, timeout=20) as r:
                        body = r.read().decode("utf-8")
                    # 한도 초과·키 오류 응답은 "노선 없음"이 아니라 실패 (예전엔 빈 목록으로 읽어 그 정류장을 노선 없는 곳으로 봤음)
                    if re.search(r"LIMITED_NUMBER_OF_SERVICE|SERVICE_KEY_IS_NOT|SERVICE_ACCESS_DENIED|returnAuthMsg|<code[^>]*>(?!000)\d+</code>", body):
                        limited[0] += "LIMITED" in body
                        if "LIMITED" in body and url.startswith("https://apis.data.go.kr/") and direct_ok[0]:
                            direct_ok[0] = False
                            print("전주: 이 PC 의 전주시 API 하루 한도 초과, 이제부터 중계 서버로만", flush=True)
                        continue
                    misses[0] = 0
                    return body
                except Exception:
                    pass
        misses[0] += 1
        if misses[0] == JEONJU_GIVE_UP:
            print(f"전주: {JEONJU_GIVE_UP}번 잇달아 받지 못해 남은 전주 요청은 건너뜀 (지난 전주 노선 그대로)", flush=True)
        return None
    limited, misses, direct_ok, started = [0], [0], [True], []
    get = get or fetch
    lists = lambda x: re.findall(r'<list class="object">(.*?)</list>', x or "", re.S)
    val = lambda m, k: (re.search(rf"<{k}[^>]*>([^<]*)<", m) or [None, None])[1]
    db = sqlite3.connect(BASE_DB)
    stops = [r[0] for r in db.execute("SELECT id FROM stops WHERE id LIKE 'JUB%'")]
    db.close()
    served, routes, data = {}, {}, {}
    fields = ("brtId", "brtClass", "brtSname", "brtEname", "brtFirstTime", "brtLastTime", "brtMininTerVal")
    failed_stops, failed_routes = [], []

    def walk_stop(sid):
        x = get("routes/" + sid[3:])
        if x is None:
            failed_stops.append(sid)
            return
        for m in lists(x):
            rid = val(m, "brtStdId") or val(m, "brtStdid")
            if rid and rid not in routes:
                routes[rid] = m
                walk_route(rid)

    def walk_route(rid):
        y = get("stops/" + rid)
        if y is None:
            failed_routes.append(rid)
            return
        seq = []
        for s in lists(y):
            std = val(s, "stopStandardid") or val(s, "bnodeId")
            if std:
                nm = (val(s, "stopKname") or "").strip()
                served["JUB" + std] = nm
                seq.append((to_int(val(s, "brsSeqno") or val(s, "brnSeqno")) or len(seq) + 1, std, nm))
        data[rid] = {"route": {k: (val(routes[rid], k) or "").strip() for k in fields}, "stops": sorted(seq)}

    for sid in stops:
        if sid not in served:
            walk_stop(sid)
    # 실패한 요청은 끝에 다시 (전주시 서버가 잠깐 늦을 때가 있어 한두 번 실패로 '다 받음'이 안 되면 정류장 지우기·노선 넣기를 통째로 건너뛰었음)
    for wait in (20, 60):
        if not failed_stops and not failed_routes:
            break
        print(f"전주: 실패한 요청 다시 (정류장 {len(failed_stops)}·노선 {len(failed_routes)}, {wait}초 뒤)", flush=True)
        time.sleep(wait if get is fetch else 0)
        again_stops, again_routes = failed_stops[:], failed_routes[:]
        failed_stops.clear(); failed_routes.clear()
        for rid in again_routes:
            walk_route(rid)
        for sid in again_stops:
            if sid not in served:
                walk_stop(sid)
    ok = not failed_stops and not failed_routes
    print(f"전주 노선이 서는 정류장: {len(served)}곳 (노선 {len(routes)}개, 다 받음 {ok}, 한도 초과 응답 {limited[0]}번)", flush=True)
    return served, ok, data


def write_db(base_db, out_db, tago, gbis_routes, version, kotsa=(), tsb_stops=None, complete=False, served_api=None, tago_cities=(),
             gbis_stops=None, jeju=None, tsb_info=None, unserved_check=None, incheon_info=None, prev_db=None):
    shutil.copyfile(base_db, out_db)
    db = sqlite3.connect(out_db)
    db.execute("CREATE TABLE IF NOT EXISTS route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER) WITHOUT ROWID")
    known = {r[0] for r in db.execute("SELECT id FROM stops")}
    added_routes = added_stops = 0
    # 정류장 이름 최신화: 노선 정류장 응답(nodenm)이 지금 이름. 정류장 표는 예전 자료라 바뀐 이름이 그대로였음
    # (예: 부산 동부산관광단지 → 오시리아관광단지, ○○동주민센터 → ○○동행정복지센터; 2026-11 실측 3,106곳)
    renames = {}
    def rename(sid, s):
        nm = str(s.get("nodenm", "") or "").strip()
        if sid in known and nm:
            renames[sid] = nm
    for code, c in tago.items():
        if not c["routes"]:
            continue
        old = [r[0] for r in db.execute("SELECT id FROM routes WHERE city = ? AND realtime = 'tago' AND id NOT LIKE 'TSB%'", (code,))]
        for rid in old:
            db.execute("DELETE FROM route_stops WHERE route_id = ?", (rid,))
            db.execute("DELETE FROM route_info WHERE id = ?", (rid,))
            db.execute("DELETE FROM routes WHERE id = ?", (rid,))
        region = region_name(c["name"])
        for rid, r in c["routes"].items():
            it = r["route"]
            stops = sorted(r["stops"], key=lambda s: to_int(s.get("nodeord")) or 0)
            rows = []
            for s in stops:
                sid = str(s.get("nodeid", "")).strip() or None
                if sid in known:
                    rename(sid, s)
                    rows.append((rid, to_int(s.get("nodeord")) or 0, sid, "", None, None, s.get("updowncd") and str(s.get("updowncd"))))
                else:
                    rows.append((rid, to_int(s.get("nodeord")) or 0, sid, str(s.get("nodenm", "")).strip() or "정류장",
                                 to_float(s.get("gpslati")), to_float(s.get("gpslong")), s.get("updowncd") and str(s.get("updowncd"))))
            db.executemany("INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)", rows)
            first = rows[0][2] if rows else None
            last = rows[-1][2] if rows else None
            db.execute("INSERT OR REPLACE INTO routes(id,no,start_name,end_name,start_node,end_node,city,region,rtype,realtime) VALUES(?,?,?,?,?,?,?,?,?,?)",
                       (rid, str(it.get("routeno", "")).strip(), it.get("startnodenm"), it.get("endnodenm"), first, last, code, region,
                        it.get("routetp"), "tago"))
            # 첫차·막차: 노선 정보(getRouteInfoIem)가 있으면 그것, 없으면 노선 목록에 같이 오는 값 (실제 응답에 있음)
            info = r.get("info") or {}
            t_first = clock(info.get("startvehicletime")) or clock(it.get("startvehicletime"))
            t_last = clock(info.get("endvehicletime")) or clock(it.get("endvehicletime"))
            if t_first or t_last or info:
                db.execute("INSERT OR REPLACE INTO route_info VALUES(?,?,?,?,?,?)",
                           (rid, t_first, t_last, to_int(info.get("intervaltime")), to_int(info.get("intervalsattime")), to_int(info.get("intervalsuntime"))))
            added_routes += 1
            added_stops += len(rows)
    # 교통안전공단 노선 정류장 순서 (노선 행은 그대로, 정류장 순서만 채움)
    for rid, items_ in (tsb_stops or {}).items():
        rows = []
        for s_ in sorted(items_, key=lambda x: to_int(x.get("nodeord")) or 0):
            sid = str(s_.get("nodeid", "")).strip() or None
            ud = s_.get("updowncd") and str(s_.get("updowncd"))
            if sid in known:
                rename(sid, s_)
                rows.append((rid, to_int(s_.get("nodeord")) or 0, sid, "", None, None, ud))
            else:
                rows.append((rid, to_int(s_.get("nodeord")) or 0, sid, str(s_.get("nodenm", "")).strip() or "정류장",
                             to_float(s_.get("gpslati")), to_float(s_.get("gpslong")), ud))
        db.executemany("INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)", rows)
    # 교통안전공단 요일별 시간표 + 배차간격 (TAGO 노선 정보에 배차간격이 비어 있어서)
    db.execute("""CREATE TABLE IF NOT EXISTS route_schedule(id TEXT, day TEXT, trips INTEGER, first TEXT, last TEXT,
                  end_first TEXT, end_last TEXT, min_int INTEGER, max_int INTEGER, PRIMARY KEY(id, day)) WITHOUT ROWID""")
    by_route = {}
    for r in kotsa:
        day = KOTSA_DAYS.get(str(r.get("요일", "")).strip())
        if not day or r.get("노선 아이디") is None:
            continue
        rid = "TSB" + str(r["노선 아이디"]).split(".")[0]
        row = (rid, day, to_int(r.get("일일운행횟수")), r.get("기점첫차출발시각") or None, r.get("기점막차출발시각") or None,
               r.get("종점첫차출발시각") or None, r.get("종점막차출발시각") or None, to_int(r.get("최소배차간격")), to_int(r.get("최대배차간격")))
        db.execute("INSERT OR REPLACE INTO route_schedule VALUES(?,?,?,?,?,?,?,?,?)", row)
        by_route.setdefault(rid, {})[day] = row
    for rid, days in by_route.items():
        wk = days.get("weekday")
        def gap(d):
            v = days.get(d, (None,) * 9)[7]
            return v if v and v > 0 else None
        old = db.execute("SELECT first, last FROM route_info WHERE id = ?", (rid,)).fetchone()
        first = (old[0] if old and old[0] else None) or (wk[3] if wk else None)
        last = (old[1] if old and old[1] else None) or (wk[4] if wk else None)
        db.execute("INSERT OR REPLACE INTO route_info VALUES(?,?,?,?,?,?)", (rid, first, last, gap("weekday"), gap("sat"), gap("sun")))
    write_tsb_info(db, tsb_info or {})
    write_incheon_info(db, incheon_info or {})
    tidy_routes(db)
    # 노선 자료가 DB 에 없는 지역(전주): 그 지역 API 로 모은 노선이 서는 정류장의 이름도 지금 이름으로
    api_served, api_ok, api_routes = (tuple(served_api) + ({},))[:3] if served_api else ({}, False, {})
    if api_ok and api_routes:
        added_routes += write_jeonju(db, api_routes, known)
    else:
        carry_jeonju(db, prev_db)
    jeju_routes, jeju_ok = jeju or ({}, False)
    if jeju_ok and jeju_routes:
        added_routes += write_jeju(db, jeju_routes, known)
    write_gbis_stops(db, gbis_stops or {}, known)
    for sid, nm in api_served.items():
        if sid in known and nm:
            renames[sid] = nm
    db.executemany("UPDATE stops SET name = ? WHERE id = ? AND name <> ?", [(nm, sid, nm) for sid, nm in renames.items()])
    if renames:
        print(f"정류장 이름 최신화: 노선 자료에 있는 {len(renames)}곳의 이름을 지금 이름으로", flush=True)
    t_counts, t_trusted = unserved_check or ({}, set())
    prune_unserved(db, complete, api_served, api_ok, t_counts, t_trusted)
    if tago_cities:
        db.execute("CREATE TABLE IF NOT EXISTS tago_cities(code TEXT PRIMARY KEY, name TEXT NOT NULL) WITHOUT ROWID")
        db.execute("DELETE FROM tago_cities")
        db.executemany("INSERT OR REPLACE INTO tago_cities VALUES(?,?)", list(tago_cities))
    write_files(db, FILES_DIR)
    for rid, no, s, e in gbis_routes:
        db.execute("INSERT OR REPLACE INTO gbis_routes(id,no,start_name,end_name) VALUES(?,?,?,?)", ("GGB" + rid, no, s, e))
    # 노선 없는 정류장 숨기기 + 같은 방면에 쪼개진 정류장 한 점으로 (stop_hidden·stop_spot 표, 앱·웹이 읽음)
    import stop_spots
    h, n, g = stop_spots.apply(db)
    print(f"정류장 정리: 노선 없는 정류장 {h}곳 숨김, {n}곳을 {g}개 점으로 묶음", flush=True)
    db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('version', ?)", (version,))
    db.commit()
    db.execute("VACUUM")
    db.close()
    return added_routes, added_stops


def jeonju_type(cls, no, start, end):
    """전주 노선 종류 (앱 JeonjuMappers.routeType 과 같음): 간선(4자리 번호, 6001·6002 처럼 기점=종점이어도) / 순환(기점=종점) / 일반.
    cls(brtClass)는 종류가 아니라 가지 번호(3-1 의 1)라 쓰지 않음"""
    if re.match(r"^\d{4}", (no or "").strip()):
        return "간선"
    return "순환" if start and start.strip() == (end or "").strip() else "일반"


def jeonju_no(no, cls):
    """화면에 보일 전주 노선 번호 (앱 JeonjuMappers.routeNo 와 같음): brtId 에 가지 번호를 붙임 (3 + brtClass 1 → 3-1, 0·빈 값은 그대로)"""
    no, cls = (no or "").strip(), (cls or "").strip()
    return no if not no or not cls or cls == "0" or "-" in no else f"{no}-{cls}"


# 지난번에 올린 정류장 DB (전주처럼 이번에 다 못 받은 지역은 여기서 그대로 가져옴)
PREV_DB_URL = "https://github.com/eunho3023/TagoHub/releases/download/data-latest/bus_stops.db.gz"


def fetch_prev_db(dest):
    """지난번에 올린 bus_stops.db 를 받아 [dest] 에 풂. 못 받으면 None"""
    import urllib.request
    try:
        req = urllib.request.Request(PREV_DB_URL, headers={"User-Agent": "TagoHub-pipeline"})
        with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
            f.write(gzip.decompress(r.read()))
        return dest
    except Exception as e:
        print(f"지난번 정류장 DB 를 받지 못함: {type(e).__name__}: {str(e)[:120]}", flush=True)
        return None


def carry_jeonju(db, prev_db):
    """
    전주를 이번에 다 받지 못했을 때 지난번 DB 의 전주 노선·정류장 순서·첫차막차를 그대로 넣음.
    예전엔 앱 기본 DB(전주 노선 없음)에서 시작해서, 전주를 건너뛴 날(2026-11-22, 전주시 API 하루 한도) 전주 노선이 317개 → 0개가 됐음
    """
    if not prev_db:
        return 0
    db.commit()   # 하던 쓰기를 끝내야 ATTACH 가 됨
    db.execute("ATTACH DATABASE ? AS prev", (str(prev_db),))
    try:
        n = db.execute("SELECT COUNT(*) FROM prev.routes WHERE id LIKE 'JUB%'").fetchone()[0]
        if n:
            db.execute("DELETE FROM route_stops WHERE route_id LIKE 'JUB%'")
            db.execute("DELETE FROM route_info WHERE id LIKE 'JUB%'")
            db.execute("DELETE FROM routes WHERE id LIKE 'JUB%'")
            db.execute("INSERT INTO routes SELECT * FROM prev.routes WHERE id LIKE 'JUB%'")
            db.execute("INSERT INTO route_stops SELECT * FROM prev.route_stops WHERE route_id LIKE 'JUB%'")
            db.execute("INSERT OR REPLACE INTO route_info SELECT * FROM prev.route_info WHERE id LIKE 'JUB%'")
        print(f"전주: 이번에 다 받지 못해 지난번 전주 노선 {n}개를 그대로 넣음", flush=True)
        db.commit()
        return n
    finally:
        db.execute("DETACH DATABASE prev")


def write_jeonju(db, routes, known):
    """전주 노선·정류장 순서·첫차·막차를 DB 에 (실시간은 전주 API 로: realtime = 'jeonju'). 다 받았을 때만 부름"""
    db.execute("DELETE FROM route_stops WHERE route_id LIKE 'JUB%'")
    db.execute("DELETE FROM route_info WHERE id LIKE 'JUB%'")
    db.execute("DELETE FROM routes WHERE id LIKE 'JUB%'")
    n = 0
    for rid, r in routes.items():
        it, stops = r["route"], r["stops"]
        if not stops:
            continue
        jid = "JUB" + rid
        rows = [(jid, seq, "JUB" + std, "" if "JUB" + std in known else (nm or "정류장"), None, None, None) for seq, std, nm in stops]
        db.executemany("INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)", rows)
        db.execute("INSERT OR REPLACE INTO routes(id,no,start_name,end_name,start_node,end_node,city,region,rtype,realtime) VALUES(?,?,?,?,?,?,?,?,?,?)",
                   (jid, jeonju_no(it["brtId"], it["brtClass"]), it["brtSname"] or None, it["brtEname"] or None, rows[0][2], rows[-1][2], "35010", "전주",
                    jeonju_type(it["brtClass"], it["brtId"], it["brtSname"], it["brtEname"]), "jeonju"))
        first, last = clock(it["brtFirstTime"] or None), clock(it["brtLastTime"] or None)
        if first or last:
            db.execute("INSERT OR REPLACE INTO route_info VALUES(?,?,?,?,?,?)", (jid, first, last, to_int(it["brtMininTerVal"] or None), None, None))
        n += 1
    print(f"전주 노선 {n}개를 DB 에 넣음", flush=True)
    return n


def fetch_tago_cities(fx):
    """TAGO 버스 도시 목록 [(코드, 이름)] (호출 1번). 앱이 처음 켤 때 받던 것을 DB 에 넣음. 못 받으면 빈 목록"""
    try:
        got = items(fx.get(f"{TAGO}/getCtyCodeList", {"_type": "json"}, "tago/cities.json", DAY))
    except Exception as e:  # 도시 목록이 없어도 나머지는 그대로
        print(f"TAGO 도시 목록 못 받음: {e}", flush=True)
        return []
    return [(str(c.get("citycode")), str(c.get("cityname", "")).strip()) for c in got if c.get("citycode")]


def prune_unserved(db, complete, api_served=None, api_ok=False, tago_counts=None, tago_trusted=()):
    """
    노선이 하나도 안 서는 정류장(폐지·미사용) 지우기. 지도·검색에 쓰지 않는 정류장이 남아 점이 겹치거나 하나 더 보였음
    (예: 전주 백자삼거리 옆 '백자동' JUB306101039 — 노선 0개).
    - 노선 자료가 믿을 만한 지역([PRUNE_MIN] 넘게 노선 정류장 자료가 있음, [PRUNE_KEEP] 제외): 노선 정류장 표에 없는 정류장
    - 전주: 전주시 API 로 모은 노선이 서는 정류장([fetch_jeonju_served])에 없는 정류장 (다 받았고 3/4 넘게 나왔을 때만)
    수집이 다 끝나지 않았으면(하루 한도 등) 노선이 빠져 있을 수 있어 지우지 않음.
    """
    if not complete:
        print("노선 없는 정류장 지우기: 수집이 다 끝나지 않아 이번엔 건너뜀", flush=True)
        return 0
    used = {r[0] for r in db.execute("SELECT DISTINCT stop_id FROM route_stops WHERE stop_id IS NOT NULL")}
    ids = [r[0] for r in db.execute("SELECT id FROM stops")]
    # 정류장의 TAGO 도시코드 (믿을 만한 지역·도시 묶음을 찾으려고, fetch_unserved_check 와 같게 city_alias 를 거침)
    cols = {r[1] for r in db.execute("PRAGMA table_info(stops)")}
    alias = dict(db.execute("SELECT code, tago FROM city_alias")) if db.execute("SELECT 1 FROM sqlite_master WHERE name = 'city_alias'").fetchone() else {}
    city_of = {sid: alias.get(c, c) for sid, c in db.execute("SELECT id, city FROM stops")} if "city" in cols else {}
    total, served = {}, {}
    for sid in ids:
        p = prefix(sid)
        total[p] = total.get(p, 0) + 1
        if sid in used:
            served[p] = served.get(p, 0) + 1
    covered = {p for p, n in total.items() if p not in PRUNE_KEEP and served.get(p, 0) / n > PRUNE_MIN}
    gone = [sid for sid in ids if prefix(sid) in covered and sid not in used]
    # 노선 자료가 일부만 있는 지역(경기·교통안전공단 등)과 아예 없는 지역: TAGO 가 이 정류장 경유노선이 0개라고 한 곳만
    counts = tago_counts or {}
    trusted_here = lambda sid: prefix(sid) in tago_trusted or group_key(sid, city_of.get(sid)) in tago_trusted
    by_tago = [sid for sid in ids if sid not in used and prefix(sid) not in covered and trusted_here(sid) and counts.get(sid) == 0]
    gone += by_tago
    jub = [sid for sid in ids if sid.startswith("JUB")]
    # 다 받았고 정류장의 3/4 넘게 나왔을 때만 (2026-11: 2,913곳 중 2,565곳)
    if api_ok and api_served and jub and sum(1 for s in jub if s in api_served) / len(jub) > 0.75:
        gone += [sid for sid in jub if sid not in api_served and sid not in used]
    db.executemany("DELETE FROM stops WHERE id = ?", [(s,) for s in gone])
    print(f"노선 없는 정류장 지우기: {len(gone)}곳 (노선 자료로 지역 {len(covered)}곳 + TAGO 확인 {len(by_tago)}곳 + 전주)", flush=True)
    return len(gone)


def package(out_db, version, complete, added, report=None):
    """압축 + 안내 파일 (pipeline/out). Release 에 같은 이름으로 덮어 올리고, 앱은 manifest 의 version 으로 새것인지 판단"""
    gz = out_db.with_suffix(".db.gz")
    with open(out_db, "rb") as src, gzip.open(gz, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst, 1 << 20)
    sha = hashlib.sha256(gz.read_bytes()).hexdigest()
    manifest = {
        "version": version,
        "file": gz.name,
        "sha256": sha,
        "size": gz.stat().st_size,
        "dbSize": out_db.stat().st_size,
        "complete": complete,
        "tagoRoutes": added,
        "compare": report or {},
    }
    (out_db.parent / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


KEY_HELP = """인증키가 공공데이터포털에서 거부됐습니다 ({code}).
확인할 것:
 1) GitHub 저장소 Settings → Secrets → Actions 의 TAGO_SERVICE_KEY 값이 공공데이터포털 '일반 인증키'와 똑같은지
    (앞뒤 공백·따옴표·'TAGO_SERVICE_KEY=' 같은 글자 없이 키만. Decoding·Encoding 어느 쪽이든 됨)
 2) 공공데이터포털에서 '국토교통부_(TAGO)_버스노선정보' 활용신청이 승인돼 있는지
 3) 브라우저에서 같은 키로 아래 주소가 열리는지:
    https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteNoList?serviceKey=키&_type=json&cityCode=25&routeNo=5"""


def check_key(fx):
    """시작 전에 실제 키로 한 번 불러봄 — 키 오류면 몇 초 만에 멈춤 (전에는 모든 요청을 세 번씩 다시 시도하느라 한 시간을 허비)"""
    f = CACHE / "tago/_keycheck.json"
    if f.exists():
        f.unlink()
    try:
        t = fx.get(f"{TAGO}/getRouteNoList", {"_type": "json", "cityCode": "25", "routeNo": "5", "numOfRows": 1, "pageNo": 1}, "tago/_keycheck.json", 0)
    except KeyError_ as e:
        sys.exit(KEY_HELP.format(code=e))
    if t is None:
        sys.exit("공공데이터포털 응답이 없습니다 (연결 실패). 해외 접속 제한일 수 있습니다 — pipeline/README.md")
    print("인증키 확인: 정상", flush=True)


def main():
    key = os.environ.get("TAGO_SERVICE_KEY", "").strip()
    if not key:
        sys.exit("환경변수 TAGO_SERVICE_KEY 가 없습니다 (GitHub 저장소 Settings → Secrets → Actions 에 등록)")
    OUT.mkdir(parents=True, exist_ok=True)
    targets = json.loads((HERE / "tago_targets.json").read_text(encoding="utf-8"))
    fx = Fetcher(key)
    check_key(fx)
    try:
        tago, complete = fetch_tago(fx, targets)
    except KeyError_ as e:
        sys.exit(KEY_HELP.format(code=e))
    gbis = fetch_gbis(fx)
    kotsa = fetch_kotsa(fx)
    report = compare(kotsa, fetch_tago_lists(fx, kotsa_cities(BASE_DB))) if kotsa else {}
    if report:
        print(f"TAGO ↔ 교통안전공단 비교: 같은 노선 {report['sameRoutes']}개, 평일 첫차 일치 {report['firstMatch']}%, 막차 일치 {report['lastMatch']}%, "
              f"TAGO 에 첫차 있음 {report['tagoHasFirst']}%, 교통안전공단에만 {report['kotsaOnly']}개, TAGO 에만 {report['tagoOnly']}개", flush=True)
    version = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M")  # utcnow() 는 3.12 부터 없어질 기능
    if sum(len(c["routes"]) for c in tago.values()) == 0:
        sys.exit("TAGO 노선을 하나도 받지 못했습니다 — 데이터를 올리지 않습니다. 위 '노선 0개 — 마지막 응답' 을 확인하세요.")
    tsb = fetch_tsb_stops(fx, BASE_DB)
    print(f"교통안전공단 노선 정류장 순서: {len(tsb)}개 노선", flush=True)
    tsb_info = fetch_tsb_info(fx, BASE_DB)
    incheon_info = fetch_incheon_info(fx, BASE_DB)
    jeonju = fetch_jeonju_served()
    cities = fetch_tago_cities(fx)
    gbis_stops = fetch_gbis_stops(fx, BASE_DB, gbis)
    new_used = {str(st.get("nodeid", "")).strip() for c in tago.values() for r in c["routes"].values() for st in r["stops"]}
    new_used |= {str(st.get("nodeid", "")).strip() for lst in tsb.values() for st in lst}
    new_used |= {"GGB" + str(r.get("stationId", "")).split(".")[0] for lst in gbis_stops.values() for r in lst}
    unserved_check = fetch_unserved_check(fx, BASE_DB, new_used)
    jeju = fetch_jeju()
    print(f"TAGO 도시 목록: {len(cities)}곳", flush=True)
    # 전주를 다 받지 못했으면 지난번에 올린 DB 에서 전주 노선을 가져옴
    prev = None if (jeonju and len(jeonju) > 1 and jeonju[1]) else fetch_prev_db(OUT / "prev_bus_stops.db")
    added, stops = write_db(BASE_DB, OUT / "bus_stops.db", tago, gbis, version, kotsa, tsb, complete=complete, served_api=jeonju, tago_cities=cities,
                            gbis_stops=gbis_stops, jeju=jeju, tsb_info=tsb_info, incheon_info=incheon_info,
                            unserved_check=unserved_check, prev_db=prev)
    m = package(OUT / "bus_stops.db", version, complete, added, report)
    print(f"완료: 버전 {version}, TAGO 노선 {added}개(정류장 {stops}), 경기 번호부 {len(gbis)}개, 호출 {fx.calls}, 서버 바쁨 {fx.busy_hits}번, 완료 여부 {complete}")
    print(json.dumps(m, ensure_ascii=False))


if __name__ == "__main__":
    main()
