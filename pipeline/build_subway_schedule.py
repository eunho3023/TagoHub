#!/usr/bin/env python3
"""
TAGO 지하철 역 목록·역별 시간표(전국 약 1,100역 × 평일·토·일 × 상·하행)를 앱 파일 app/src/main/assets/subway_schedule.tsv 로 만든다.
앱은 이 파일을 먼저 쓰고, 파일에 없는 역·요일·방향만 예전처럼 API (한 번 받으면 저장).

    python pipeline/build_subway_schedule.py            # 받은 응답은 pipeline/cache/subway 에 저장 → 하루 한도에 걸리면 다음 날 다시 돌리면 이어서
    python pipeline/build_subway_schedule.py --refresh  # 모두 새로 받음 (못 받은 것은 저장해 둔 응답 그대로)
    python pipeline/build_subway_schedule.py --refresh --out pipeline/out/files   # 매주 데이터 갱신: 노선 DB 에 넣어 앱이 받게
    응답 저장 폴더: 환경변수 TAGO_CACHE_DIR 가 있으면 그 아래 subway (데이터 갱신 러너), 없으면 pipeline/cache/subway

호출: 역 목록 2번 + 역마다 6번 (약 6,700번). 키: TAGO_SERVICE_KEY (환경변수 또는 local.properties, 앱과 같은 키)

파일 형식 (탭으로 나눔):
  S  역 id  역 이름  노선 이름  노선 id
  T  역 id  요일(01 평일·02 토·03 일)  방향(U·D)  (운행이 없다고 온 것은 넣지 않음)  종착역 목록(id~이름|id~이름…)  출발 목록
     출발 하나 = 운행일 기준 분(00~02시는 +24시간)을 36진수로 + 종착역 순번 글자(a=첫째, 종착만 하는 열차는 대문자). 공백으로 나눔
"""
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "subway_schedule.tsv")
CACHE = os.path.join(os.environ.get("TAGO_CACHE_DIR") or os.path.join(ROOT, "pipeline", "cache"), "subway")
BASE = "https://apis.data.go.kr/1613000/SubwayInfo"
DAYS = ("01", "02", "03")
DIRS = ("U", "D")


class Limit(Exception):
    pass


def key():
    v = os.environ.get("TAGO_SERVICE_KEY", "").strip()
    if not v:
        with open(os.path.join(ROOT, "local.properties"), encoding="utf-8") as f:
            v = next((l.split("=", 1)[1].strip() for l in f if l.startswith("TAGO_SERVICE_KEY=")), "")
    if not v:
        sys.exit("TAGO_SERVICE_KEY 가 없습니다")
    return v if "%" in v else urllib.parse.quote(v, safe="")


def get(k, op, params):
    q = "&".join(f"{a}={urllib.parse.quote(str(b))}" for a, b in params.items())
    url = f"{BASE}/{op}?serviceKey={k}&_type=json&{q}"
    for wait in (0, 2, 6, 15):
        time.sleep(wait)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TagoHub-pipeline"}), timeout=30) as r:
                t = r.read().decode("utf-8")
        except Exception:
            continue
        if "LIMITED_NUMBER_OF_SERVICE_REQUESTS" in t:
            raise Limit()
        try:
            j = json.loads(t)
        except ValueError:
            continue
        if j.get("response", {}).get("header", {}).get("resultCode") == "00":
            return j
    return None


def items(j):
    it = ((j or {}).get("response", {}).get("body", {}).get("items") or {})
    raw = it.get("item") if isinstance(it, dict) else None
    return raw if isinstance(raw, list) else [raw] if isinstance(raw, dict) else []


def minute(raw):
    """앱 TagoTime.minutesOfDay + serviceMinute 와 같음 (못 읽으면 None)"""
    d = "".join(c for c in str(raw or "") if c.isdigit())
    if len(d) in (3, 5):
        d = d.zfill(len(d) + 1)
    if len(d) not in (4, 6):
        return None
    h, m = int(d[:2]), int(d[2:4])
    if m >= 60 or h >= 30:
        return None
    v = h * 60 + m
    return v + 1440 if v < 180 else v


def departures(rows):
    """앱 Mappers.cleanSchedule 와 같음: 출발(없으면 도착) 시각, 0만 있는 칸은 빈 것, (분, 종착역) 중복 빼고 시간순"""
    out, seen = [], set()
    for r in rows:
        dep = str(r.get("depTime") or "").strip()
        arr = str(r.get("arrTime") or "").strip()
        dep = "" if not dep or set(dep) == {"0"} else dep
        arr = "" if not arr or set(arr) == {"0"} else arr
        m = minute(dep or arr)
        if m is None or not (dep or arr):
            continue
        end = (str(r.get("endSubwayStationId") or ""), str(r.get("endSubwayStationNm") or r.get("endSubwayStationName") or "").strip())
        if (m, end[0]) in seen:
            continue
        seen.add((m, end[0]))
        out.append((m, end, not dep))
    return sorted(out, key=lambda x: x[0])


def b36(n):
    s = ""
    while True:
        n, r = divmod(n, 36)
        s = "0123456789abcdefghijklmnopqrstuvwxyz"[r] + s
        if not n:
            return s


def line(sid, day, ud, deps):
    ends = []
    for _, e, _ in deps:
        if e not in ends:
            ends.append(e)
    ends = ends[:26]
    idx = {e: i for i, e in enumerate(ends)}
    ent = []
    for m, e, arr_only in deps:
        if e not in idx:
            continue
        c = chr(ord("a") + idx[e])
        ent.append(b36(m) + (c.upper() if arr_only else c))
    clean = lambda s: s.replace("\t", " ").replace("|", " ").replace("~", " ")
    return "\t".join(["T", sid, day, ud, "|".join(f"{clean(i)}~{clean(n)}" for i, n in ends), " ".join(ent)])


def main():
    global OUT
    refresh = "--refresh" in sys.argv
    if "--out" in sys.argv:
        d = sys.argv[sys.argv.index("--out") + 1]
        os.makedirs(d, exist_ok=True)
        OUT = os.path.join(d, "subway_schedule.tsv")
    os.makedirs(CACHE, exist_ok=True)
    k = key()
    stations, page = [], 1
    while page <= 5:
        j = get(k, "GetKwrdFndSubwaySttnList", {"subwayStationName": "", "numOfRows": 1000, "pageNo": page})
        got = items(j)
        stations += got
        total = int(((j or {}).get("response", {}).get("body", {}) or {}).get("totalCount") or 0)
        if not got or len(stations) >= total:
            break
        page += 1
    stations = [s for s in stations if s.get("subwayStationId")]
    if len(stations) < 500:
        sys.exit(f"역 목록이 {len(stations)}곳뿐이라 멈춤")
    print(f"역 {len(stations)}곳", flush=True)
    jobs = [(str(s["subwayStationId"]), d, u) for s in stations for d in DAYS for u in DIRS]
    limited = threading.Event()
    done = [0]

    def one(job):
        sid, d, u = job
        f = os.path.join(CACHE, f"{sid}_{d}_{u}.json")
        if os.path.exists(f) and not refresh:
            return
        if limited.is_set():
            return
        try:
            j = get(k, "GetSubwaySttnAcctoSchdulList", {"subwayStationId": sid, "dailyTypeCode": d, "upDownTypeCode": u, "numOfRows": 1000, "pageNo": 1})
        except Limit:
            limited.set()
            return
        if j is not None:
            with open(f, "w", encoding="utf-8") as w:
                json.dump(items(j), w, ensure_ascii=False)
        done[0] += 1
        if done[0] % 300 == 0:
            print(f"  시간표 {done[0]}번 받음", flush=True)

    with ThreadPoolExecutor(4) as ex:
        list(ex.map(one, jobs))
    if limited.is_set():
        print("하루 한도에 걸림 — 받은 데까지 파일로 씀. 내일 다시 돌리면 이어서 받음", flush=True)
    lines, have = [], 0
    for s in stations:
        lines.append("\t".join(["S", str(s["subwayStationId"]), str(s.get("subwayStationName") or s.get("subwayStationNm") or "").strip(),
                                str(s.get("subwayRouteName") or s.get("subwayRouteNm") or "").strip(), str(s.get("subwayRouteId") or "")]))
    for sid, d, u in jobs:
        f = os.path.join(CACHE, f"{sid}_{d}_{u}.json")
        if not os.path.exists(f):
            continue
        with open(f, encoding="utf-8") as r:
            deps = departures(json.load(r))
        if not deps:  # 빈 응답은 넣지 않음 → 앱이 예전처럼 API 로 물음 (잠깐 비었던 것일 수 있어서)
            continue
        lines.append(line(sid, d, u, deps))
        have += 1
    with open(OUT, "w", encoding="utf-8", newline="\n") as w:
        w.write("\n".join(lines) + "\n")
    print(f"subway_schedule.tsv: 역 {len(stations)}곳, 시간표 {have}/{len(jobs)}개, {os.path.getsize(OUT) // 1024}KB", flush=True)


if __name__ == "__main__":
    main()
