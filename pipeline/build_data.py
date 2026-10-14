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
import datetime, gzip, hashlib, json, os, pathlib, shutil, sqlite3, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE_DB = ROOT / "app/src/main/assets/bus_stops.db"
HERE = pathlib.Path(__file__).resolve().parent
CACHE = HERE / "cache"
OUT = HERE / "out"                        # 만드는 중인 DB (올리지 않음)
TAGO = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService"
GBIS = "https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteListv2"
KEYWORDS = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "마을", "급행", "순환", "좌석", "직행"]
DAY = 86400
# 동시에 받는 개수: 활용가이드 '초당 최대 30건' 안에서 (4개 × 호출 사이 0.12초 ≈ 초당 20건)
WORKERS = 4


class Limit(Exception):
    """하루 호출 한도 (TAGO resultCode 22)"""


class KeyError_(Exception):
    """인증키 오류 — 다시 시도해도 소용없으니 바로 멈춤 (등록 안 된 키, 활용신청 안 된 서비스 등)"""


# 공공데이터포털 인증 오류 (returnReasonCode): 20 접근 거부, 30 등록되지 않은 서비스키, 31 기한 만료, 32 등록되지 않은 IP
KEY_ERRORS = ("SERVICE_KEY_IS_NOT_REGISTERED", "SERVICE_ACCESS_DENIED", "DEADLINE_HAS_EXPIRED", "UNREGISTERED_IP")


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
    def __init__(self, key, session=None):
        import requests  # 테스트에서는 Fetcher 를 안 써서 requests 없이도 import 가능
        self.key = urllib.parse.unquote(key.strip())  # 인코딩된 키면 풀어서 requests 가 다시 인코딩
        self.s = session or requests.Session()
        self.calls = 0
        self.lock = threading.Lock()

    def get(self, url, params, cache_file, max_age):
        f = CACHE / cache_file
        if f.exists() and time.time() - f.stat().st_mtime < max_age:
            return f.read_text(encoding="utf-8")
        for attempt in range(3):
            try:
                with self.lock:
                    self.calls += 1
                r = self.s.get(url, params={"serviceKey": self.key, **params}, timeout=60)
                text = r.content.decode("utf-8", errors="replace")
                if is_limit(text):
                    raise Limit()
                if key_error(text):
                    raise KeyError_(key_error(text))
                if r.status_code != 200:
                    raise RuntimeError(f"HTTP {r.status_code}: {text[:200]}")
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(text, encoding="utf-8")
                time.sleep(0.12)
                return text
            except (Limit, KeyError_):
                raise
            except Exception as e:  # 연결 오류는 세 번까지
                print(f"  재시도 {attempt + 1}: {e}", flush=True)
                time.sleep(2)
        return None


def list_all(fx, url, params, cache_prefix, max_age, rows):
    """totalCount 를 보고 여러 쪽(pageNo)을 모두 받기 (최대 20쪽)"""
    out = []
    for page in range(1, 21):
        t = fx.get(url, {**params, "numOfRows": rows, "pageNo": page}, f"{cache_prefix}_p{page}.json", max_age)
        got = items(t)
        out += got
        total = total_count(t) if t else None
        if not got or total is None or len(out) >= total:
            break
    return out


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
        jobs = [(code, rid, r) for code, c in data.items() for rid, r in c["routes"].items()]

        def stops_of(job):
            code, rid, r = job
            r["stops"] = list_all(fx, f"{TAGO}/getRouteAcctoThrghSttnList", {"_type": "json", "cityCode": code, "routeId": rid},
                                  f"tago/{code}/stops_{rid}", 7 * DAY, 500)

        def info_of(job):
            code, rid, r = job
            t = fx.get(f"{TAGO}/getRouteInfoIem", {"_type": "json", "cityCode": code, "routeId": rid}, f"tago/{code}/info_{rid}.json", 7 * DAY)
            info = items(t)
            r["info"] = info[0] if info else None

        run_parallel(fx, jobs, stops_of, "정류장 순서")
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


# ───────────── 쓰기 ─────────────

def write_db(base_db, out_db, tago, gbis_routes, version):
    shutil.copyfile(base_db, out_db)
    db = sqlite3.connect(out_db)
    db.execute("CREATE TABLE IF NOT EXISTS route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER) WITHOUT ROWID")
    known = {r[0] for r in db.execute("SELECT id FROM stops")}
    added_routes = added_stops = 0
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
    for rid, no, s, e in gbis_routes:
        db.execute("INSERT OR REPLACE INTO gbis_routes(id,no,start_name,end_name) VALUES(?,?,?,?)", ("GGB" + rid, no, s, e))
    db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('version', ?)", (version,))
    db.commit()
    db.execute("VACUUM")
    db.close()
    return added_routes, added_stops


def package(out_db, version, complete, added):
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
    version = datetime.datetime.utcnow().strftime("%Y%m%d%H%M")
    added, stops = write_db(BASE_DB, OUT / "bus_stops.db", tago, gbis, version)
    m = package(OUT / "bus_stops.db", version, complete, added)
    print(f"완료: 버전 {version}, TAGO 노선 {added}개(정류장 {stops}), 경기 번호부 {len(gbis)}개, 호출 {fx.calls}, 완료 여부 {complete}")
    print(json.dumps(m, ensure_ascii=False))


if __name__ == "__main__":
    main()
