#!/usr/bin/env python3
"""
거의 안 바뀌는 API 응답을 앱 파일(app/src/main/assets)로 만든다. 앱은 파일을 먼저 쓰고, 파일이 없거나 비면 예전처럼 API.
가끔(몇 달에 한 번, 휴게소·IC 가 새로 생겼을 때 등) 한 번 돌리면 된다.

    python pipeline/build_api_files.py
    python pipeline/build_api_files.py --out pipeline/out/files   # 매주 데이터 갱신: 노선 DB 에 넣어 앱이 받게

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


def key(*names, need=True):
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
    if need:
        sys.exit(f"키가 없습니다: {' / '.join(names)}")
    print(f"키가 없어 건너뜀: {' / '.join(names)}")
    return None


def get(url, timeout=40):
    for wait in (0, 3, 10):
        time.sleep(wait)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=timeout) as r:
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


KOMSA = "https://apis.data.go.kr/B554035"


def komsa_all(k, path):
    """한국해양교통안전공단 여객선 자료: 한 번에 1만 건까지"""
    rows, _ = datago_items(get(f"{KOMSA}/{path}?serviceKey={encoded(k)}&pageNo=1&numOfRows=10000&dataType=JSON"))
    return rows


def ferry_files(k):
    """
    여객선 기항지(항구 이름·위치)와 운항노선(항로마다 들르는 기항지 순서·정방향/역방향 정박 여부·소요 분).
    앱은 이것으로 그날 운항 스케줄(실시간 API)의 출항지 → 도착 항구 사이를 찾음
    """
    ports = [[r["portcl_cd"], r["portcl_nm"], r.get("lat"), r.get("lot"), r.get("admdst_ctpv_nm") or ""]
             for r in komsa_all(k, "port-call-info-v2/get-port-call-info-v2")]
    rts = {(r["lcns_seawy_cd"], r["nvg_seawy_cd"]): r for r in komsa_all(k, "oprt-rt-info-v3/get-oprt-rt-info-v3")}
    lines = {}
    for r in sorted(komsa_all(k, "oprt-line-info-v2/get-oprt-line-info-v2"), key=lambda r: int(r.get("portcl_sn") or 0)):
        key = (r["lcns_seawy_cd"], r["nvg_seawy_cd"])
        rt = rts.get(key) or {}
        line = lines.setdefault("|".join(key), {
            "n": r.get("nvg_seawy_nm") or rt.get("nvg_seawy_nm") or "", "c": 1 if rt.get("seawy_se_cd") == "2" else 0,
            "f": int(float(rt.get("fwd_nvg_req_hr") or 0)), "r": int(float(rt.get("rev_nvg_req_hr") or 0)), "s": []})
        line["s"].append([r["portcl_cd"], (r.get("fwd_stp_yn") or "Y") + (r.get("rev_stp_yn") or "Y")])
    return ports, lines


PARKING = "https://apis.data.go.kr/B553881/Parking_v1"


def parking_live_lots(k, minutes=40, size=1000, quiet=8):
    """
    한국교통안전공단 주차정보: 실시간 빈자리를 주는 주차장(약 4,700곳)의 이름·위치·면 수.
    위치는 주차장 기본정보(약 170만 행)에 있는데, 실시간 주차장은 앞쪽에 몰려 있음(1쪽 156곳, 2쪽 71곳, 20쪽 0곳, 2026-11).
    앞쪽은 빠르고(5~15초) 뒤쪽은 느려서(1천 행에 1~3분, 큰 쪽은 504) 1쪽부터 차례로 보다가
    새로 찾는 곳 없이 quiet 쪽이 이어지거나 정한 시간이 지나면 멈춤. 예전 파일에서 찾았던 위치와 합침
    """
    live = json.loads(get(f"{PARKING}/PrkRealtimeInfo_v1?serviceKey={encoded(k)}&pageNo=1&numOfRows=10000&format=2", timeout=120)).get("PrkRealtimeInfo") or []
    want = {r["prk_center_id"] for r in live}
    out = {}
    for path in (os.path.join(OUT_DIR, "parking_live.json"), os.path.join(ASSETS, "parking_live.json")):
        try:
            with open(path, encoding="utf-8") as f:
                for v in json.load(f):
                    if v[0] in want:
                        out.setdefault(v[0], v)
        except (OSError, ValueError):
            pass
    known = len(out)
    until = time.time() + minutes * 60
    page, empty, fails, seen = 0, 0, 0, set()
    while time.time() < until and empty < quiet and len(seen) < len(want):
        page += 1
        try:
            rows = json.loads(get(f"{PARKING}/PrkSttusInfo_v1?serviceKey={encoded(k)}&pageNo={page}&numOfRows={size}&format=2", timeout=120)).get("PrkSttusInfo") or []
        except (Exception, SystemExit):  # 한 쪽 실패는 건너뜀
            fails += 1
            continue
        if not rows:
            break
        new = 0
        for r in rows:
            i = r.get("prk_center_id")
            if i in want:  # 새로 받은 것으로 바꿈 (이름·면 수가 바뀌었을 수 있음)
                new += i not in seen
                seen.add(i)
                out[i] = [i, r.get("prk_plce_nm") or "주차장", float(r.get("prk_plce_entrc_la") or 0), float(r.get("prk_plce_entrc_lo") or 0),
                          int(float(r.get("prk_cmprt_co") or 0)), r.get("prk_manage_agent_nm") or "", r.get("parkng_chrge_se_nm") or ""]
        empty = 0 if new else empty + 1
        if page % 5 == 0:
            print(f"주차장 기본정보 {page}쪽 봄 (실패 {fails}), 위치 {len(out)}/{len(want)}곳", flush=True)
    lots = [v for v in out.values() if 33 < v[2] < 39 and 124 < v[3] < 132]
    print(f"실시간 주차장 {len(want)}곳 중 위치 {len(lots)}곳 (예전 파일 {known}곳, {page}쪽 봄, 실패 {fails}쪽)")
    return lots


def fast_exit(k):
    """서울교통공사 빠른하차: 역마다 방면별로 엘리베이터·에스컬레이터·계단에 가까운 칸-문 번호 {역: [[호선, 방면, 칸-문, 시설]]}"""
    out, rows_n = {}, 0
    for page in range(1, 30):
        rows, total = datago_items(get(f"https://apis.data.go.kr/B553766/inout/getFstExit?serviceKey={encoded(k)}&pageNo={page}&numOfRows=1000&dataType=JSON"))
        for r in rows:
            stn = (r.get("stnNm") or "").strip()
            if stn and r.get("qckgffVhclDoorNo"):
                out.setdefault(stn, []).append([r.get("lineNm") or "", r.get("drtnInfo") or "", r["qckgffVhclDoorNo"], r.get("plfmCmgFac") or ""])
        rows_n += len(rows)
        if not rows or rows_n >= total:
            break
    return out, rows_n


def write(name, data, n, least):
    if n < least:  # 키 오류·빈 응답으로 파일을 비우지 않게
        print(f"{name}: {n}건뿐이라 쓰지 않음 (예전 파일 그대로)")
        return
    path = os.path.join(OUT_DIR, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{name}: {n}건, {os.path.getsize(path) // 1024}KB")


OUT_DIR = ASSETS


def main():
    global OUT_DIR
    if "--out" in sys.argv:
        OUT_DIR = sys.argv[sys.argv.index("--out") + 1]
        os.makedirs(OUT_DIR, exist_ok=True)
    ex = key("EX_API_KEY", need=False)   # 데이터 갱신 러너에 도로공사 키가 없으면 휴게소 파일만 건너뜀 (앱 안 파일 그대로)
    if ex:
        places = {"ex_rest": ex_all(ex, "/openapi/locationinfo/locationinfoRest"),
                  "ex_toll": ex_all(ex, "/openapi/locationinfo/locationinfoUnit"),
                  "ex_ic": ex_all(ex, "/openapi/locationinfo/locationinfoIc")}
        write("ex_places.json", places, min(len(v) for v in places.values()), 50)
    air = air_routes(key("KAC_API_KEY", "TAGO_SERVICE_KEY"))
    write("air_routes.json", {"response": {"body": {"totalCount": len(air), "items": {"item": air}}}}, len(air), 100)
    ports, lines = ferry_files(key("TAGO_SERVICE_KEY"))
    write("ferry_ports.json", ports, len(ports), 100)
    write("ferry_lines.json", lines, len(lines), 300)
    lots = parking_live_lots(key("TAGO_SERVICE_KEY"))
    write("parking_live.json", lots, len(lots), 300)
    fx, fx_n = fast_exit(key("TAGO_SERVICE_KEY"))
    write("subway_fast_exit.json", fx, fx_n, 500)
    ul = ulsan_crossroads(key("KLID_BUS_KEY", "TAGO_SERVICE_KEY"))
    write("ulsan_crossroads.json", {"body": {"items": {"item": ul}}}, len(ul), 100)


if __name__ == "__main__":
    main()
