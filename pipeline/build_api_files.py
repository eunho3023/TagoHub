#!/usr/bin/env python3
"""
거의 안 바뀌는 API 응답을 앱 파일(app/src/main/assets)로 만든다. 앱은 파일을 먼저 쓰고, 파일이 없거나 비면 예전처럼 API.
가끔(몇 달에 한 번, 휴게소·IC 가 새로 생겼을 때 등) 한 번 돌리면 된다.

    python pipeline/build_api_files.py

만드는 파일:
  ex_places.json        한국도로공사 휴게소·영업소·IC/JCT 위치 {"ex_rest": [행], "ex_toll": [행], "ex_ic": [행]} (행 = API 그대로의 글자 칸)
  air_routes.json       한국공항공사 노선별 소요시간·거리 (API 응답 모양 그대로, ODP·ARP·KM·TIME 만)
  ulsan_crossroads.json 울산 교차로 위치 (API 응답 모양 그대로, crsrdId·crsrdNm·mapCtptIntLat·mapCtptIntLot 만)
키: 환경변수 또는 local.properties — EX_API_KEY(도로공사), KAC_API_KEY(공항공사, 없으면 TAGO_SERVICE_KEY), KLID_BUS_KEY(없으면 TAGO_SERVICE_KEY). 앱과 같은 키
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
UA = "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/124.0 Mobile Safari/537.36"


def key(*names):
    for n in names:
        v = os.environ.get(n, "").strip()
        if v:
            return v
    try:
        with open(os.path.join(ROOT, "local.properties"), encoding="utf-8") as f:
            props = dict(l.strip().split("=", 1) for l in f if "=" in l and not l.lstrip().startswith("#"))
    except OSError:
        props = {}
    for n in names:
        if props.get(n, "").strip():
            return props[n].strip()
    sys.exit(f"키가 없습니다: {' / '.join(names)}")


def get(url):
    for wait in (0, 3, 10):
        time.sleep(wait)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=40) as r:
                return r.read().decode("utf-8")
        except Exception as e:  # 잠깐 끊김은 다시
            err = e
    raise SystemExit(f"받지 못함: {url.split('?')[0]} ({err})")


def encoded(k):
    """data.go.kr 키: 이미 인코딩된 키(%)는 그대로"""
    return k if "%" in k else urllib.parse.quote(k, safe="")


def ex_all(k, path):
    """도로공사: 한 쪽 99건, count 만큼 모일 때까지"""
    out, page = [], 1
    while page <= 30:
        q = urllib.parse.urlencode({"key": k, "type": "json", "numOfRows": 99, "pageNo": page})
        j = json.loads(get(f"https://data.ex.co.kr{path}?{q}"))
        if str(j.get("code", "SUCCESS")).upper() != "SUCCESS":
            raise SystemExit(f"도로공사 오류 {path}: {j.get('message')}")
        rows = next((v for v in j.values() if isinstance(v, list) and v and all(isinstance(x, dict) for x in v)), [])
        if not rows:
            break
        out += [{a: ("" if b is None else str(b)) for a, b in r.items()} for r in rows]
        total = int(float(j.get("count") or 0)) or None
        if not total or len(out) >= total or len(rows) >= total:
            break
        page += 1
    return out


def datago_items(text):
    j = json.loads(text)
    body = (j.get("response") or j).get("body") or {}
    items = body.get("items")
    raw = items.get("item") if isinstance(items, dict) else items
    return (raw if isinstance(raw, list) else [raw] if isinstance(raw, dict) else []), int(float(body.get("totalCount") or 0))


def air_routes(k):
    out = []
    for page in range(1, 13):
        q = f"serviceKey={encoded(k)}&type=json&pageNo={page}&numOfRows=100"
        rows, total = datago_items(get(f"https://apis.data.go.kr/B551178/flight-routes/info?{q}"))
        out += [{c: r.get(c) for c in ("ODP", "ARP", "TOF", "KM", "TIME")} for r in rows]
        if not rows or len(out) >= total:
            break
    return out


def ulsan_crossroads(k):
    q = f"serviceKey={encoded(k)}&type=json&pageNo=1&numOfRows=1000&stdgCd=3100000000"
    rows, _ = datago_items(get(f"https://apis.data.go.kr/B551982/rti/crsrd_map_info?{q}"))
    return [{c: r.get(c) for c in ("crsrdId", "crsrdNm", "mapCtptIntLat", "mapCtptIntLot")} for r in rows]


def write(name, data, n, least):
    if n < least:  # 키 오류·빈 응답으로 파일을 비우지 않게
        print(f"{name}: {n}건뿐이라 쓰지 않음 (예전 파일 그대로)")
        return
    path = os.path.join(ASSETS, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{name}: {n}건, {os.path.getsize(path) // 1024}KB")


def main():
    ex = key("EX_API_KEY")
    places = {"ex_rest": ex_all(ex, "/openapi/locationinfo/locationinfoRest"),
              "ex_toll": ex_all(ex, "/openapi/locationinfo/locationinfoUnit"),
              "ex_ic": ex_all(ex, "/openapi/locationinfo/locationinfoIc")}
    write("ex_places.json", places, min(len(v) for v in places.values()), 50)
    air = air_routes(key("KAC_API_KEY", "TAGO_SERVICE_KEY"))
    write("air_routes.json", {"response": {"body": {"totalCount": len(air), "items": {"item": air}}}}, len(air), 100)
    ul = ulsan_crossroads(key("KLID_BUS_KEY", "TAGO_SERVICE_KEY"))
    write("ulsan_crossroads.json", {"body": {"items": {"item": ul}}}, len(ul), 100)


if __name__ == "__main__":
    main()
