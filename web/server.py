#!/usr/bin/env python3
"""
TagoHub 웹 버전(PC용) 서버.

브라우저는 이 서버만 부르고, 이 서버가 공공 API·카카오에 키를 붙여 대신 부른다.
  → 키가 브라우저에 보이지 않고, 브라우저가 막는 호출(CORS)도 문제없다.

실행:  python web/server.py            (브라우저가 자동으로 열림, 기본 주소 http://localhost:8000)
키:    저장소 루트(또는 exe 옆)의 local.properties 또는 환경변수
         TAGO_SERVICE_KEY  국토교통부 TAGO (버스·기차·항공)
         KAKAO_REST_KEY    카카오 장소 검색·길찾기
         SEOUL_SUBWAY_KEY  서울 지하철 실시간 도착
정류장 DB: 안드로이드 앱과 같은 bus_stops.db. 저장소의 app/src/main/assets 에 있으면 그것을, 없으면
         GitHub Release(data-latest)에서 받아 web/data 에 둔다.
"""
import argparse
import csv
import datetime as dt
import gzip
import io
import json
import mimetypes
import os
import re
import shutil
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UA = "Mozilla/5.0 TagoHub-web"
TAGO = "https://apis.data.go.kr/1613000/"
RELEASE = "https://github.com/eunho3023/TagoHub/releases/download/data-latest/"

# 실행 파일(exe) 안에 묶였을 때는 임시 폴더(_MEIPASS) 에서 화면 파일을 읽고, 데이터·키는 exe 옆에 둔다
BUNDLE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
HOME = os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(BUNDLE, "static")


# ───────────────────────── 키 ─────────────────────────

def read_properties() -> dict:
    """local.properties (줄마다 KEY=VALUE, 값 안의 \\= 같은 이스케이프는 풂)를 찾아 읽는다"""
    props: dict = {}
    seen = set()
    here = HOME
    for _ in range(4):
        for name in ("local.properties",):
            path = os.path.join(here, name)
            if path in seen or not os.path.exists(path):
                continue
            seen.add(path)
            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.rstrip("\n")
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        props.setdefault(k.strip(), re.sub(r"\\(.)", r"\1", v.strip()))
        here = os.path.dirname(here)
    return props


PROPS = read_properties()


def key(name: str) -> str:
    return (os.environ.get(name) or PROPS.get(name, "")).strip()


# ───────────────────────── 바깥 호출 ─────────────────────────

def http_get(url: str, headers: dict | None = None, timeout: int = 20) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


class ApiError(Exception):
    def __init__(self, message: str, status: int = 502):
        super().__init__(message)
        self.status = status


def tago(path: str, **params) -> dict:
    """TAGO 한 번 호출 → body 부분. 키가 없거나 오류 응답이면 ApiError"""
    k = key("TAGO_SERVICE_KEY")
    if not k:
        raise ApiError("local.properties 에 TAGO_SERVICE_KEY 가 없습니다.", 503)
    q = {"serviceKey": k, "_type": "json", "pageNo": 1, "numOfRows": 100}
    q.update({a: b for a, b in params.items() if b not in (None, "")})
    url = TAGO + path + "?" + urllib.parse.urlencode(q, safe="")
    try:
        raw = http_get(url)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:300]
        raise ApiError(f"TAGO 오류 {e.code}: {redact(body)}")
    except Exception as e:  # noqa: BLE001
        raise ApiError(f"TAGO 연결 실패: {e.__class__.__name__}")
    try:
        data = json.loads(raw.decode("utf-8"))
    except ValueError:
        text = raw.decode("utf-8", "replace")
        m = re.search(r"<returnAuthMsg>(.*?)</returnAuthMsg>|<errMsg>(.*?)</errMsg>", text)
        raise ApiError("TAGO 응답 오류: " + (next((g for g in (m.groups() if m else ()) if g), text[:120])))
    resp = data.get("response") or {}
    header = resp.get("header") or {}
    if str(header.get("resultCode", "00")) not in ("00", "0", "INFO-000"):
        raise ApiError(f"TAGO: {header.get('resultMsg', '오류')}")
    return resp.get("body") or {}


def redact(text: str) -> str:
    for k in (key("TAGO_SERVICE_KEY"), key("KAKAO_REST_KEY"), key("SEOUL_SUBWAY_KEY")):
        if k:
            text = text.replace(k, "***").replace(urllib.parse.quote(k, safe=""), "***")
    return text


def tago_items(path: str, **params) -> list:
    body = tago(path, **params)
    items = body.get("items")
    if not items or items == "":
        return []
    items = items.get("item", []) if isinstance(items, dict) else items
    return [items] if isinstance(items, dict) else list(items)


def tago_all(path: str, rows: int = 300, max_pages: int = 5, **params) -> list:
    """여러 쪽에 걸친 목록을 모두 (totalCount 까지)"""
    out: list = []
    for page in range(1, max_pages + 1):
        body = tago(path, pageNo=page, numOfRows=rows, **params)
        items = body.get("items")
        got = [] if not items else (items.get("item", []) if isinstance(items, dict) else items)
        got = [got] if isinstance(got, dict) else list(got)
        out += got
        total = int(body.get("totalCount") or 0)
        if not got or len(out) >= total:
            break
    return out


_cache: dict = {}
_cache_lock = threading.Lock()


def cached(name: str, ttl: float, load):
    now = time.time()
    with _cache_lock:
        hit = _cache.get(name)
        if hit and now - hit[0] < ttl:
            return hit[1]
    value = load()
    with _cache_lock:
        _cache[name] = (now, value)
    return value


# ───────────────────────── 정류장 DB ─────────────────────────

_db_lock = threading.Lock()
_db: sqlite3.Connection | None = None
db_status = {"state": "loading", "message": ""}


def find_db() -> str | None:
    for p in (
        os.path.join(HOME, "data", "bus_stops.db"),
        os.path.join(HOME, "..", "app", "src", "main", "assets", "bus_stops.db"),
        os.path.join(os.getcwd(), "app", "src", "main", "assets", "bus_stops.db"),
    ):
        if os.path.isfile(p):
            return os.path.abspath(p)
    return None


def download_db() -> str:
    """GitHub Release 의 bus_stops.db.gz 를 받아 web/data 에 풀어 둔다 (안드로이드 앱의 자동 갱신과 같은 파일)"""
    manifest = json.loads(http_get(RELEASE + "manifest.json", timeout=30).decode("utf-8"))
    name = manifest.get("file") or "bus_stops.db.gz"
    os.makedirs(os.path.join(HOME, "data"), exist_ok=True)
    out = os.path.join(HOME, "data", "bus_stops.db")
    tmp = out + ".part"
    with urllib.request.urlopen(urllib.request.Request(RELEASE + name, headers={"User-Agent": UA}), timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(gzip.GzipFile(fileobj=r), f)
    os.replace(tmp, out)
    return out


def get_db() -> sqlite3.Connection:
    global _db
    with _db_lock:
        if _db is None:
            path = find_db()
            if path is None:
                db_status.update(state="downloading", message="정류장 데이터를 받는 중…")
                path = download_db()
            _db = sqlite3.connect(path, check_same_thread=False)
            db_status.update(state="ready", message=path)
        return _db


def db_query(sql: str, args: tuple = ()) -> list:
    db = get_db()
    with _db_lock:
        return db.execute(sql, args).fetchall()


def stop_json(r) -> dict:
    return {"id": r[0], "name": r[1], "lat": r[2], "lng": r[3], "no": r[4], "city": r[5]}


def city_names() -> dict:
    return cached("cityNames", 3600, lambda: {c: n for c, n in db_query("SELECT code, name FROM cities")})


def haversine(lat1, lng1, lat2, lng2) -> float:
    import math
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))


def stops_near(lat: float, lng: float, radius: float = 500, limit: int = 40) -> list:
    d = radius / 110000.0
    rows = db_query(
        "SELECT id, name, lat, lng, no, city FROM stops WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?",
        (lat - d, lat + d, lng - d * 1.3, lng + d * 1.3),
    )
    out = []
    for r in rows:
        dist = haversine(lat, lng, r[2], r[3])
        if dist <= radius:
            s = stop_json(r)
            s["distance"] = round(dist)
            out.append(s)
    out.sort(key=lambda s: s["distance"])
    return out[:limit]


def stops_in_box(south, west, north, east, limit: int = 300) -> list:
    rows = db_query(
        "SELECT id, name, lat, lng, no, city FROM stops WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? LIMIT ?",
        (south, north, west, east, limit),
    )
    return [stop_json(r) for r in rows]


def stops_search(q: str, limit: int = 40) -> list:
    q = q.strip()
    if not q:
        return []
    names = city_names()
    if q.isdigit():
        rows = db_query("SELECT id, name, lat, lng, no, city FROM stops WHERE no = ? LIMIT ?", (q, limit))
    else:
        rows = db_query(
            "SELECT id, name, lat, lng, no, city FROM stops WHERE name LIKE ? ORDER BY length(name) LIMIT ?",
            (f"%{q}%", limit),
        )
    out = []
    for r in rows:
        s = stop_json(r)
        s["region"] = names.get(r[5], "")
        out.append(s)
    return out


# ───────────────────────── 시내버스 (TAGO) ─────────────────────────

def tago_cities() -> set:
    def load():
        return {str(c["citycode"]) for c in tago_items("BusSttnInfoInqireService/getCtyCodeList", numOfRows=200)}
    return cached("tagoCities", 6 * 3600, load)


def bus_arrivals(city: str, node: str) -> dict:
    if city not in tago_cities():
        return {"supported": False, "message": "이 지역은 국토교통부 실시간 버스 서비스 지역이 아니라 도착 정보를 받을 수 없습니다 (웹 버전은 아직 지역 전용 API를 쓰지 않습니다)."}
    items = tago_items("ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", cityCode=city, nodeId=node, numOfRows=100)
    rows = []
    for x in items:
        rows.append({
            "routeId": str(x.get("routeid", "")),
            "routeNo": str(x.get("routeno", "")),
            "routeType": x.get("routetp"),
            "vehicleType": x.get("vehicletp"),
            "prevStops": to_int(x.get("arrprevstationcnt")),
            "sec": to_int(x.get("arrtime")),
        })
    rows.sort(key=lambda r: (r["sec"] is None, r["sec"] or 0))
    return {"supported": True, "arrivals": rows, "at": int(time.time())}


def bus_stop_routes(city: str, node: str) -> list:
    items = tago_items("BusSttnInfoInqireService/getSttnThrghRouteList", cityCode=city, nodeid=node, numOfRows=100)
    seen, out = set(), []
    for x in items:
        rid = str(x.get("routeid", ""))
        if rid in seen:
            continue
        seen.add(rid)
        out.append({"routeId": rid, "routeNo": str(x.get("routeno", "")), "routeType": x.get("routetp"),
                    "start": x.get("startnodenm"), "end": x.get("endnodenm")})
    return out


def bus_routes_by_no(city: str, no: str) -> list:
    items = tago_items("BusRouteInfoInqireService/getRouteNoList", cityCode=city, routeNo=no, numOfRows=100)
    return [{"routeId": str(x.get("routeid", "")), "routeNo": str(x.get("routeno", "")), "routeType": x.get("routetp"),
             "start": x.get("startnodename") or x.get("startnodenm"), "end": x.get("endnodename") or x.get("endnodenm")} for x in items]


def bus_route(city: str, route: str) -> dict:
    def info():
        its = tago_items("BusRouteInfoInqireService/getRouteInfoIem", cityCode=city, routeId=route, numOfRows=10)
        return its[0] if its else {}

    def stops():
        return tago_all("BusRouteInfoInqireService/getRouteAcctoThrghSttnList", cityCode=city, routeId=route)

    def buses():
        try:
            return tago_all("BusLcInfoInqireService/getRouteAcctoBusLcList", rows=100, max_pages=2, cityCode=city, routeId=route)
        except ApiError:
            return []

    with ThreadPoolExecutor(3) as ex:
        fi, fs, fb = ex.submit(info), ex.submit(stops), ex.submit(buses)
        i, s, b = fi.result(), fs.result(), fb.result()
    return {
        "info": {
            "routeNo": str(i.get("routeno", "")), "routeType": i.get("routetp"),
            "start": i.get("startnodenm"), "end": i.get("endnodenm"),
            "first": i.get("startvehicletime"), "last": i.get("endvehicletime"),
            "interval": to_int(i.get("intervaltime")), "intervalSat": to_int(i.get("intervalsattime")),
            "intervalSun": to_int(i.get("intervalsuntime")),
        },
        "stops": sorted(
            [{"id": str(x.get("nodeid", "")), "name": x.get("nodenm"), "no": str(x.get("nodeno", "")) if x.get("nodeno") is not None else "",
              "ord": to_int(x.get("nodeord")), "dir": x.get("updowncd"), "lat": to_float(x.get("gpslati")), "lng": to_float(x.get("gpslong"))}
             for x in s],
            key=lambda r: r["ord"] or 0),
        "buses": [{"vehicleNo": x.get("vehicleno"), "nodeId": str(x.get("nodeid", "")), "nodeName": x.get("nodenm"),
                   "ord": to_int(x.get("nodeord")), "lat": to_float(x.get("gpslati")), "lng": to_float(x.get("gpslong"))} for x in b],
        "at": int(time.time()),
    }


# ───────────────────────── 지하철 ─────────────────────────

_stations: list | None = None


def subway_stations() -> list:
    """앱에 들어 있는 역 목록(subway_stations.tsv)을 이름별로 묶음"""
    global _stations
    if _stations is not None:
        return _stations
    path = None
    for p in (os.path.join(BUNDLE, "data", "subway_stations.tsv"), os.path.join(HOME, "..", "app", "src", "main", "assets", "subway_stations.tsv"),
              os.path.join(HOME, "data", "subway_stations.tsv")):
        if os.path.isfile(p):
            path = p
            break
    groups: dict = {}
    if path:
        with open(path, encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f, delimiter="\t"):
                name = (r.get("names") or "").split("|")[0].strip()
                if not name:
                    continue
                g = groups.setdefault(name, {"name": name, "lines": [], "lat": to_float(r.get("lat")), "lng": to_float(r.get("lng")), "operator": r.get("operator")})
                line = (r.get("line") or "").strip()
                if line and line not in g["lines"]:
                    g["lines"].append(line)
    _stations = sorted(groups.values(), key=lambda g: g["name"])
    return _stations


def subway_search(q: str) -> list:
    q = q.strip().removesuffix("역")
    if not q:
        return []
    hits = [g for g in subway_stations() if q in g["name"]]
    # 이름이 똑같은 역, 그 글자로 시작하는 역, 나머지 순
    hits.sort(key=lambda g: (g["name"] != q, not g["name"].startswith(q), g["name"]))
    return hits[:40]


def subway_arrivals(station: str) -> dict:
    k = key("SEOUL_SUBWAY_KEY")
    if not k:
        raise ApiError("local.properties 에 SEOUL_SUBWAY_KEY 가 없습니다.", 503)
    name = station.strip().removesuffix("역")
    url = f"http://swopenapi.seoul.go.kr/api/subway/{urllib.parse.quote(k, safe='')}/json/realtimeStationArrival/0/20/{urllib.parse.quote(name)}"
    try:
        data = json.loads(http_get(url).decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        raise ApiError(f"서울 지하철 연결 실패: {e.__class__.__name__}")
    if "errorMessage" in data and data["errorMessage"].get("status") not in (200, None):
        return {"supported": False, "message": redact(str(data["errorMessage"].get("message", "도착 정보가 없습니다.")))}
    out = []
    for x in data.get("realtimeArrivalList", []):
        out.append({
            "subwayId": x.get("subwayId"), "line": SUBWAY_LINE.get(str(x.get("subwayId")), x.get("trainLineNm", "")),
            "updown": x.get("updnLine"), "text": x.get("trainLineNm"), "sec": to_int(x.get("barvlDt")),
            "msg": x.get("arvlMsg2"), "now": x.get("arvlMsg3"), "dest": x.get("bstatnNm"),
            "express": "급행" in str(x.get("btrainSttus", "")), "last": str(x.get("lstcarAt")) == "1",
        })
    return {"supported": True, "arrivals": out, "at": int(time.time())}


SUBWAY_LINE = {
    "1001": "1호선", "1002": "2호선", "1003": "3호선", "1004": "4호선", "1005": "5호선", "1006": "6호선", "1007": "7호선", "1008": "8호선",
    "1009": "9호선", "1063": "경의중앙선", "1065": "공항철도", "1067": "경춘선", "1075": "수인분당선", "1077": "신분당선", "1092": "우이신설선", "1032": "GTX-A",
}


# ───────────────────────── 기차·항공 ─────────────────────────

def train_stations() -> list:
    def load():
        cities = tago_items("TrainInfo/GetCtyCodeList", numOfRows=100)

        def one(c):
            try:
                return [{"id": x.get("nodeid"), "name": x.get("nodename"), "city": c.get("cityname"), "cityCode": to_int(c.get("citycode"))}
                        for x in tago_all("TrainInfo/GetCtyAcctoTrainSttnList", cityCode=c.get("citycode"))]
            except ApiError:
                return []

        with ThreadPoolExecutor(8) as ex:
            lists = list(ex.map(one, cities))
        seen, out = set(), []
        for s in sorted((s for l in lists for s in l if s["id"]), key=lambda s: s["name"]):
            if s["id"] not in seen:
                seen.add(s["id"])
                out.append(s)
        return out
    return cached("trainStations", 6 * 3600, load)


def train_trips(dep: str, arr: str, date: str) -> list:
    items = tago_all("TrainInfo/GetStrtpntAlocFndTrainInfo", rows=300, max_pages=3, depPlaceId=dep, arrPlaceId=arr, depPlandTime=date.replace("-", ""))
    out = [{"kind": x.get("traingradename"), "no": str(x.get("trainno", "")), "dep": str(x.get("depplandtime", "")), "arr": str(x.get("arrplandtime", "")),
            "fare": to_int(x.get("adultcharge")), "depName": x.get("depplacename"), "arrName": x.get("arrplacename"), "depId": dep, "arrId": arr} for x in items]
    out.sort(key=lambda r: r["dep"])
    return out


def train_trips_cached(dep: str, arr: str, date: str) -> list:
    """구간 조회를 잠시 기억 (환승·정차역 탐색이 같은 구간을 여러 번 부르므로)"""
    return cached(f"trips:{dep}:{arr}:{date}", 600, lambda: train_trips(dep, arr, date))


# ── 환승 ──
# 환승역 후보: 노선이 갈라지거나 만나는 큰 역. 이름이 목록에 없으면 건너뜀.
TRANSFER_HUBS = ["서울", "용산", "영등포", "수원", "평택", "천안아산", "오송", "조치원", "대전", "서대전", "김천구미", "동대구", "신경주",
                 "울산(통도사)", "부산", "밀양", "마산", "익산", "정읍", "광주송정", "목포", "순천", "여수EXPO", "행신", "청량리", "원주", "제천", "영주", "강릉"]
MIN_TRANSFER_MIN = 10


def to_minutes(t: str):
    """'202610221530' → 분 단위 숫자 (비교·차이 계산용). 형식이 이상하면 None"""
    try:
        return int(dt.datetime.strptime(str(t)[:12], "%Y%m%d%H%M").timestamp() // 60)
    except ValueError:
        return None


def combine_transfers(first: list, second: list, hub: str, min_wait: int = MIN_TRANSFER_MIN) -> list:
    """첫 구간(출발→환승역)과 둘째 구간(환승역→도착)을 이어 붙임.
    첫 구간마다 '환승 시간을 지키면서 가장 빨리 도착하는' 둘째 구간 하나만 짝지음."""
    out = []
    seconds = [(to_minutes(b["dep"]), b) for b in second]
    seconds = sorted((m, b) for m, b in seconds if m is not None)
    for a in first:
        a_arr = to_minutes(a["arr"])
        if a_arr is None:
            continue
        best = None
        for m, b in seconds:
            if m < a_arr + min_wait:
                continue
            b_arr = to_minutes(b["arr"])
            if b_arr is None:
                continue
            if best is None or b_arr < best[1]:
                best = (b, b_arr, m - a_arr)
        if best:
            b, _, wait = best
            out.append({"hub": hub, "wait": wait, "dep": a["dep"], "arr": b["arr"], "legs": [a, b],
                        "fare": (a["fare"] or 0) + (b["fare"] or 0) or None})
    return out


def train_transfers(dep: str, arr: str, date: str, limit: int = 15) -> list:
    stations = train_stations()
    by_name = {s["name"]: s["id"] for s in stations}
    hubs = [(n, by_name[n]) for n in TRANSFER_HUBS if n in by_name and by_name[n] not in (dep, arr)]

    def leg(pair):
        try:
            return train_trips_cached(*pair, date)
        except ApiError:
            return []

    jobs = [(dep, hid) for _, hid in hubs] + [(hid, arr) for _, hid in hubs]
    with ThreadPoolExecutor(8) as ex:
        results = list(ex.map(leg, jobs))
    n = len(hubs)
    found = []
    for i, (name, _) in enumerate(hubs):
        if results[i] and results[n + i]:
            found += combine_transfers(results[i], results[n + i], name)
    found.sort(key=lambda r: (r["arr"], r["dep"]))
    # 같은 열차 조합은 하나만 (가장 빨리 도착하는 환승역)
    seen, out = set(), []
    for r in found:
        k = (r["legs"][0]["no"], r["legs"][0]["dep"])
        if k in seen:
            continue
        seen.add(k)
        out.append(r)
    out.sort(key=lambda r: r["dep"])
    # 도착이 너무 늦은 것(가장 빠른 도착보다 2시간 넘게 늦음)은 뺌
    best = min((to_minutes(r["arr"]) or 10**9 for r in out), default=None)
    out = [r for r in out if best is not None and (to_minutes(r["arr"]) or 10**9) <= best + 120]
    return out[:limit] if len(out) <= limit else _spread(out, limit)


def _spread(items: list, limit: int) -> list:
    """너무 많으면 하루에 고르게 퍼지도록 일정 간격으로 고름"""
    step = len(items) / limit
    return [items[int(i * step)] for i in range(limit)]


# ── 정차역 ──
# 한국철도공사 열차시간표·운행기록(app/src/main/assets/train_timetable.tsv)에서 바로 찾는다 (앱과 같은 파일).
# 한 줄 = 열차 한 편: 번호 \t 종별 \t 운행요일 \t 역@분;역@분...  (분 = 0시부터의 분, 자정을 넘으면 1440 이상)

_timetable = None
MAX_GAP_MIN = 60   # 조회한 열차의 출발 시각과 시간표가 이만큼(분) 넘게 다르면 다른 열차로 봄


def tt_norm(name: str) -> str:
    """역 이름 비교용: 괄호 안 설명·공백을 빼고, 시간표와 TAGO 의 표기가 다른 몇 곳을 맞춤"""
    n = re.sub(r"\(.*?\)|\s+", "", name or "")
    return {"여수엑스포": "여수EXPO", "평택지제": "지제"}.get(n, n)


def timetable() -> dict:
    """{열차번호: [[(역, 분), ...], ...]} — 파일이 없으면 빈 딕셔너리"""
    global _timetable
    if _timetable is None:
        table: dict = {}
        for p in (os.path.join(BUNDLE, "data", "train_timetable.tsv"), os.path.join(HOME, "..", "app", "src", "main", "assets", "train_timetable.tsv")):
            if os.path.isfile(p):
                with open(p, encoding="utf-8") as f:
                    table = parse_timetable(f.read())
                break
        _timetable = table
    return _timetable


def parse_timetable(text: str) -> dict:
    table: dict = {}
    for line in text.splitlines():
        f = line.split("\t")
        if len(f) < 4 or not f[0]:
            continue
        stops = []
        for x in f[3].split(";"):
            name, _, m = x.rpartition("@")
            if name and m.isdigit():
                stops.append((name, int(m)))
        if len(stops) >= 2:
            table.setdefault(f[0], []).append(stops)
    return table


def stops_from_timetable(table: dict, no: str, dep_name: str, arr_name: str, dep: str):
    """[{name, time}] (시각은 yyyymmddHHMM) 또는 못 찾으면 None. dep 은 조회한 열차의 계획 출발 시각 yyyymmddHHMM"""
    d, a = tt_norm(dep_name), tt_norm(arr_name)
    try:
        start = dt.datetime.strptime(dep[:12], "%Y%m%d%H%M")
    except ValueError:
        return None
    want = start.hour * 60 + start.minute

    def gap(m):
        x = abs(m % 1440 - want)
        return min(x, 1440 - x)

    best = None
    for stops in table.get(str(int(no)) if str(no).isdigit() else str(no), []):
        names = [tt_norm(n) for n, _ in stops]
        if d in names and a in names and names.index(d) < names.index(a):
            i = names.index(d)
            if best is None or gap(stops[i][1]) < gap(best[0][i][1]):
                best = (stops, i, names.index(a))
    if best is None or gap(best[0][best[1]][1]) > MAX_GAP_MIN:
        return None
    stops, i, j = best
    base = stops[i][1]
    out = []
    for k in range(i, j + 1):
        name = dep_name if k == i else arr_name if k == j else stops[k][0]
        out.append({"name": name, "time": (start + dt.timedelta(minutes=stops[k][1] - base)).strftime("%Y%m%d%H%M")})
    return out


def train_stops(dep: str, arr: str, date: str, no: str) -> dict:
    """한 열차의 정차역 (출발역~도착역, 시각순)"""
    trips = [t for t in train_trips_cached(dep, arr, date) if t["no"] == no]
    if not trips:
        return {"stops": [], "message": "이 열차의 시간표를 찾지 못했습니다."}
    t = trips[0]
    stops = stops_from_timetable(timetable(), no, t["depName"], t["arrName"], t["dep"])
    if stops is None:
        return {"stops": [], "message": "이 열차의 정차역을 시간표에서 찾지 못했습니다."}
    return {"stops": stops}


def airports() -> list:
    def load():
        return sorted(({"id": x.get("airportId"), "name": x.get("airportNm")} for x in tago_all("DmstcFlightNvgInfo/GetArprtList", rows=100)),
                      key=lambda a: a["name"] or "")
    return cached("airports", 6 * 3600, load)


def flights(dep: str, arr: str, date: str) -> list:
    items = tago_all("DmstcFlightNvgInfo/GetFlightOpratInfoList", rows=300, max_pages=3, depAirportId=dep, arrAirportId=arr, depPlandTime=date.replace("-", ""))
    out = [{"kind": x.get("airlineNm"), "no": str(x.get("vihicleId", "")), "dep": str(x.get("depPlandTime", "")), "arr": str(x.get("arrPlandTime", "")),
            "fare": to_int(x.get("economyCharge")) or to_int(x.get("prestigeCharge")), "depName": x.get("depAirportNm"), "arrName": x.get("arrAirportNm")} for x in items]
    out.sort(key=lambda r: r["dep"])
    return out


# ───────────────────────── 카카오 (장소 검색·길찾기) ─────────────────────────

def kakao_rest(url: str, **params) -> dict:
    k = key("KAKAO_REST_KEY")
    if not k:
        raise ApiError("local.properties 에 KAKAO_REST_KEY 가 없습니다.", 503)
    full = url + "?" + urllib.parse.urlencode({a: b for a, b in params.items() if b not in (None, "")}, safe="|,")
    try:
        return json.loads(http_get(full, {"Authorization": "KakaoAK " + k}).decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ApiError(f"카카오 오류 {e.code}: {redact(e.read().decode('utf-8', 'replace')[:200])}")
    except Exception as e:  # noqa: BLE001
        raise ApiError(f"카카오 연결 실패: {e.__class__.__name__}")


def place_search(q: str, lat=None, lng=None) -> list:
    d = kakao_rest("https://dapi.kakao.com/v2/local/search/keyword.json", query=q, size=15, x=lng, y=lat)
    return [{"name": p.get("place_name"), "address": p.get("road_address_name") or p.get("address_name"), "lat": to_float(p.get("y")), "lng": to_float(p.get("x")),
             "category": p.get("category_group_name"), "phone": p.get("phone"), "url": p.get("place_url")} for p in d.get("documents", [])]


OPTIONS = {
    "recommend": {"priority": "RECOMMEND"},
    "shortest": {"priority": "DISTANCE"},
    "notoll": {"priority": "RECOMMEND", "avoid": "toll"},
    "nomotorway": {"priority": "RECOMMEND", "avoid": "motorway"},
}


def car_route(olat, olng, dlat, dlng, option="recommend") -> dict:
    d = kakao_rest("https://apis-navi.kakaomobility.com/v1/directions", origin=f"{olng},{olat}", destination=f"{dlng},{dlat}",
                   car_fuel="GASOLINE", car_hipass="false", alternatives="false", road_details="true", **OPTIONS.get(option, OPTIONS["recommend"]))
    routes = d.get("routes") or []
    if not routes or routes[0].get("result_code") not in (0, None):
        return {"found": False, "message": (routes[0].get("result_msg") if routes else "경로를 찾지 못했습니다.")}
    r = routes[0]
    s = r.get("summary") or {}
    sec = (r.get("sections") or [{}])[0]
    roads = []
    pts = []
    for road in sec.get("roads", []):
        v = road.get("vertexes", [])
        line = [[v[i + 1], v[i]] for i in range(0, len(v) - 1, 2)]
        roads.append({"name": road.get("name"), "state": road.get("traffic_state"), "distance": road.get("distance"), "duration": road.get("duration"), "points": line})
        pts += line
    fare = s.get("fare") or {}
    return {
        "found": True,
        "summary": {"distance": s.get("distance"), "duration": s.get("duration"), "toll": fare.get("toll"), "taxi": fare.get("taxi")},
        "roads": roads, "points": pts,
        "guides": [{"name": g.get("name"), "guidance": g.get("guidance"), "distance": g.get("distance"), "type": g.get("type"), "lat": g.get("y"), "lng": g.get("x")}
                   for g in sec.get("guides", [])],
    }


def transit_route(olat, olng, dlat, dlng) -> dict:
    d = kakao_rest("https://dapi.kakao.com/v2/routing/publictraffic", start_x=olng, start_y=olat, end_x=dlng, end_y=dlat)
    routes = []
    for r in d.get("routes", []):
        p = r.get("properties") or {}
        steps = []
        for st in r.get("steps", []):
            sp = st.get("properties") or {}
            steps.append({
                "type": sp.get("type"), "guidance": sp.get("guidance"), "distance": sp.get("distance"), "time": sp.get("time"),
                "stops": [x.get("name") for x in sp.get("stops", [])],
                "vehicles": [{"name": x.get("name"), "type": x.get("type")} for x in sp.get("vehicles", [])],
                "points": [[pt[1], pt[0]] for pt in (st.get("path") or {}).get("points", []) if len(pt) >= 2],
            })
        routes.append({"type": p.get("type"), "totalTime": p.get("totalTime"), "totalDistance": p.get("totalDistance"), "transfers": p.get("transfers"),
                       "fare": (p.get("fare") or {}).get("value"), "steps": steps})
    return {"status": d.get("status"), "routes": routes}


# ───────────────────────── 작은 도구 ─────────────────────────

def to_int(v):
    try:
        return int(float(str(v).strip()))
    except (TypeError, ValueError):
        return None


def to_float(v):
    try:
        return float(str(v).strip())
    except (TypeError, ValueError):
        return None


def today() -> str:
    return dt.date.today().isoformat()


# ───────────────────────── 길(경로)표 ─────────────────────────

def route(path: str, q: dict):
    def g(name, default=""):
        return (q.get(name) or [default])[0]

    def f(name):
        v = to_float(g(name))
        if v is None:
            raise ApiError(f"{name} 값이 필요합니다.", 400)
        return v

    if path == "/api/config":
        # 카카오 JavaScript 키는 브라우저가 지도를 불러올 때 어차피 주소에 들어가는 공개용 키 (등록한 도메인에서만 동작)
        return {"tago": bool(key("TAGO_SERVICE_KEY")), "kakao": bool(key("KAKAO_REST_KEY")), "seoul": bool(key("SEOUL_SUBWAY_KEY")),
                "kakaoJs": key("KAKAO_JS_KEY"), "db": db_status}
    if path == "/api/place":
        return {"places": place_search(g("q"), to_float(g("lat")), to_float(g("lng")))}
    if path == "/api/stops/near":
        return {"stops": stops_near(f("lat"), f("lng"), to_float(g("r", "500")) or 500)}
    if path == "/api/stops/box":
        return {"stops": stops_in_box(f("s"), f("w"), f("n"), f("e"))}
    if path == "/api/stops/search":
        return {"stops": stops_search(g("q"))}
    if path == "/api/bus/arrivals":
        return bus_arrivals(g("city"), g("node"))
    if path == "/api/bus/stoproutes":
        return {"routes": bus_stop_routes(g("city"), g("node"))}
    if path == "/api/bus/routes":
        return {"routes": bus_routes_by_no(g("city"), g("no"))}
    if path == "/api/bus/route":
        return bus_route(g("city"), g("route"))
    if path == "/api/subway/search":
        return {"stations": subway_search(g("q"))}
    if path == "/api/subway/arrivals":
        return subway_arrivals(g("station"))
    if path == "/api/train/stations":
        return {"stations": train_stations()}
    if path == "/api/train/trips":
        return {"trips": train_trips(g("dep"), g("arr"), g("date", today()))}
    if path == "/api/train/transfers":
        return {"itineraries": train_transfers(g("dep"), g("arr"), g("date", today()))}
    if path == "/api/train/stops":
        return train_stops(g("dep"), g("arr"), g("date", today()), g("no"))
    if path == "/api/air/airports":
        return {"airports": airports()}
    if path == "/api/air/flights":
        return {"flights": flights(g("dep"), g("arr"), g("date", today()))}
    if path == "/api/route/car":
        return car_route(f("olat"), f("olng"), f("dlat"), f("dlng"), g("option", "recommend"))
    if path == "/api/route/transit":
        return transit_route(f("olat"), f("olng"), f("dlat"), f("dlng"))
    raise ApiError("없는 주소입니다.", 404)


class RateLimiter:
    """주소(IP)마다 1분에 [limit] 번까지만 허용 (인터넷에 올렸을 때 키 하나를 여럿이 쓰므로 한 사람이 다 쓰지 못하게). limit 이 0 이면 제한 없음"""

    def __init__(self, limit: int, window: float = 60.0):
        self.limit, self.window = limit, window
        self._hits: dict = {}
        self._lock = threading.Lock()

    def allow(self, ip: str, now: float | None = None) -> bool:
        if self.limit <= 0:
            return True
        now = time.time() if now is None else now
        with self._lock:
            if len(self._hits) > 20000:   # 오래된 주소는 비움
                self._hits = {k: v for k, v in self._hits.items() if v and now - v[-1] < self.window}
            hits = [t for t in self._hits.get(ip, ()) if now - t < self.window]
            if len(hits) >= self.limit:
                self._hits[ip] = hits
                return False
            hits.append(now)
            self._hits[ip] = hits
            return True


# 서버에 올릴 때는 환경변수 RATE_LIMIT_PER_MIN 으로 켬 (내 PC에서 쓸 때는 0 = 제한 없음)
LIMITER = RateLimiter(int(os.environ.get("RATE_LIMIT_PER_MIN", "0") or 0))


def client_ip(headers, fallback: str) -> str:
    """구글 클라우드(Cloud Run)는 X-Forwarded-For 맨 끝에 진짜 접속자 주소를 붙여 줌. 앞쪽 값은 접속자가 꾸밀 수 있어 쓰지 않음"""
    forwarded = (headers.get("X-Forwarded-For") or "").split(",")[-1].strip()
    return forwarded or fallback


class Handler(BaseHTTPRequestHandler):
    server_version = "TagoHubWeb"

    def log_message(self, fmt, *args):  # 조용히
        pass

    def send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/healthz":
            self.send_json({"ok": True})
            return
        if parsed.path.startswith("/api/"):
            if not LIMITER.allow(client_ip(self.headers, self.client_address[0])):
                self.send_json({"error": "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요."}, 429)
                return
            try:
                self.send_json(route(parsed.path, urllib.parse.parse_qs(parsed.query)))
            except ApiError as e:
                self.send_json({"error": redact(str(e))}, e.status)
            except Exception as e:  # noqa: BLE001
                self.send_json({"error": redact(f"{e.__class__.__name__}: {e}")}, 500)
            return
        rel = "index.html" if parsed.path in ("/", "") else parsed.path.lstrip("/")
        full = os.path.normpath(os.path.join(STATIC, rel))
        if not full.startswith(os.path.normpath(STATIC)) or not os.path.isfile(full):
            self.send_error(404)
            return
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        with open(full, "rb") as fh:
            data = fh.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text/") or ctype.endswith("javascript") else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="TagoHub 웹 버전 서버")
    # 환경변수 PORT 가 있으면 서버(Cloud Run 등)에서 도는 것: 모든 주소로 열고 브라우저는 띄우지 않음
    hosted = bool(os.environ.get("PORT"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT") or 8000))
    ap.add_argument("--host", default="0.0.0.0" if hosted else "127.0.0.1", help="다른 기기(폰)에서도 열려면 0.0.0.0")
    ap.add_argument("--no-browser", action="store_true", default=hosted)
    args = ap.parse_args(argv)
    # 정류장 DB 는 뒤에서 준비 (받아야 하면 오래 걸림)
    threading.Thread(target=lambda: _safe(get_db), daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://localhost:{args.port}"
    print(f"TagoHub 웹 버전: {url}  (끄려면 이 창에서 Ctrl+C)")
    missing = [n for n in ("TAGO_SERVICE_KEY", "KAKAO_REST_KEY", "SEOUL_SUBWAY_KEY") if not key(n)]
    if missing:
        print("키가 없는 기능은 동작하지 않습니다:", ", ".join(missing), "→ local.properties 에 넣으세요")
    if not args.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


def _safe(fn):
    try:
        fn()
    except Exception as e:  # noqa: BLE001
        db_status.update(state="error", message=redact(str(e)))


if __name__ == "__main__":
    sys.exit(main())
