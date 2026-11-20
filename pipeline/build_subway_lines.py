"""
서울·수도권 실시간 지하철 노선의 역 차례와 이어진 모양 → app/src/main/assets/subway_lines.json
(웹은 build_web_data.py 가 data/files/ 로 그대로 복사, 앱 assets 에도 들어감)

자료: 한국철도공단 KRIC 노선 정보(trainUseInfo/subwayRouteInfo, 키 KRIC_SUBWAY_KEY). 역 좌표는 앱 subway_stations.tsv.
KRIC 역 차례(stinConsOrdr)는 지선이 본선과 같은 번호를 나눠 씀 (1호선 구일/가산디지털단지 46, 2호선 대림/도림천 12).
→ 차례 k 의 역은 차례 k-1 의 역 중 가장 가까운 역에 잇는다 (지선 첫 역은 갈라지는 본선 역에 이어짐).
  2호선은 마지막 역(을지로입구)과 첫 역(시청)이 가까워 순환으로 이음.

결과: {"서울 노선ID": {"name", "color", "stations": [[이름, 위도, 경도, 차례]], "edges": [[i, j]]}}
쓰는 법: python pipeline/build_subway_lines.py   (local.properties 의 KRIC_SUBWAY_KEY)
"""
import json
import math
import os
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
OUT = ASSETS / "subway_lines.json"
BASE = "https://openapi.kric.go.kr/openapi/trainUseInfo/subwayRouteInfo"

# 서울 실시간 노선 ID (앱 SeoulLines) → KRIC 노선 코드(lnCd), 표준데이터 노선명(좌표 찾기), 노선 색 (앱 subwayLineColor)
LINES = {
    "1001": ("1", "1호선", ["1호선", "경부선", "경원선", "경인선", "장항선"], "#0052A4"),
    "1002": ("2", "2호선", ["2호선"], "#00A84D"),
    "1003": ("3", "3호선", ["3호선", "일산선"], "#EF7C1C"),
    "1004": ("4", "4호선", ["4호선", "안산과천선", "진접선"], "#00A5DE"),
    "1005": ("5", "5호선", ["5호선"], "#996CAC"),
    "1006": ("6", "6호선", ["6호선"], "#CD7C2F"),
    "1007": ("7", "7호선", ["7호선"], "#747F00"),
    "1008": ("8", "8호선", ["8호선"], "#E6186C"),
    "1009": ("9", "9호선", ["9호선"], "#BDB092"),
    "1063": ("K4", "경의중앙선", ["경의중앙선"], "#77C4A3"),
    "1065": ("A1", "공항철도", ["공항철도선", "인천국제공항선"], "#0090D2"),
    "1067": ("K2", "경춘선", ["경춘선"], "#0C8E72"),
    "1075": ("K1", "수인분당선", ["분당선", "수인선", "수인분당선"], "#FABE00"),
    "1077": ("D1", "신분당선", ["신분당선"], "#D4003B"),
    "1081": ("K5", "경강선", ["경강선"], "#003DA5"),
    "1092": ("UI", "우이신설선", ["우이신설선"], "#B0CE18"),
    "1093": ("WS", "서해선", ["서해선"], "#8FC31F"),
    "1094": ("L1", "신림선", ["신림선"], "#6789CA"),
    "1032": ("A", "GTX-A", ["GTX-A"], "#9A6292"),
}
REGION = "01"  # 수도권
MAX_LINK_M = 15000  # 차례가 이어져도 이보다 멀면 다른 구간 (GTX-A 동탄↔서울역처럼 따로 운영). 연천↔전곡·평택↔성환·청라↔영종은 8~10km
JOIN_M = 3000  # 차례로 못 이은 조각(경의중앙선 서울역 지선)은 본선 가장 가까운 역에 이 거리 안이면 이음
# 표준데이터 좌표 오류 (노선, 역 코드) → (위도, 경도) — 앱 StationDataFixes 와 같음
FIXES = {("6호선", "2615"): (37.6190, 126.9212),      # 연신내: 경도가 신내 근처(127.0914)로 적힘
         ("진접선", "408"): (37.66778, 127.11581)}    # 별내별가람: 오남역 자리로 적힌 줄 (0408 줄이 맞음)
# 차례만으로는 틀리는 이음 (노선 ID → 지울 것, 더할 것). 6호선 응암 순환: 구산 → 응암 (새절이 아니라), 응암 ↔ 새절
EDGE_FIX = {"1006": ([("구산", "새절")], [("구산", "응암"), ("응암", "새절")]),
            # 경의중앙선 서울역 지선 (가좌–신촌–서울역): 차례가 거꾸로라 신촌이 한국항공대에 붙음
            "1063": ([("한국항공대", "신촌")], [("가좌", "신촌")])}


def key():
    v = os.environ.get("KRIC_SUBWAY_KEY", "").strip()
    if not v:
        props = ROOT / "local.properties"
        if props.exists():
            v = next((l.split("=", 1)[1].strip() for l in props.read_text(encoding="utf-8").splitlines() if l.startswith("KRIC_SUBWAY_KEY=")), "")
    if not v:
        sys.exit("KRIC_SUBWAY_KEY 가 없습니다 (local.properties)")
    return v if "%" in v else urllib.parse.quote(v, safe="")


def norm(s):
    """앱 StationName.norm: 괄호·빈칸 빼고, 3글자 넘는 'OO역' 은 '역' 뺌"""
    t = ""
    depth = 0
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif depth == 0 and ch != " ":
            t += ch
    return t[:-1] if len(t) >= 3 and t.endswith("역") else t


def meters(a, b):
    return math.hypot((b[0] - a[0]) * 110540, (b[1] - a[1]) * 111320 * math.cos(math.radians(a[0])))


def stations_tsv():
    out = []
    for i, line in enumerate((ASSETS / "subway_stations.tsv").read_text(encoding="utf-8").splitlines()):
        c = line.split("\t")
        if i == 0 or len(c) < 5:
            continue
        try:
            lat, lng = FIXES.get((c[2], c[0]), (float(c[3]), float(c[4])))
            out.append({"code": c[0], "name": norm(c[1]), "line": c[2], "lat": lat, "lng": lng})
        except ValueError:
            continue
    return out


def fetch(k, ln):
    url = f"{BASE}?serviceKey={k}&format=json&mreaWideCd={REGION}&lnCd={urllib.parse.quote(ln)}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TagoHub-pipeline"}), timeout=40) as r:
        j = json.loads(r.read().decode("utf-8"))
    rows = j.get("body") or []
    return rows if isinstance(rows, list) else []


def build_line(rows, data_lines, all_st):
    """KRIC 행 → (역 목록, 이음). 좌표: 같은 노선(표준데이터 노선명) 같은 이름 → 같은 코드 → 다른 노선 같은 이름 (앞 역에서 가까운 것)"""
    rows = sorted(rows, key=lambda r: (int(r.get("stinConsOrdr") or 0), str(r.get("stinCd"))))
    picked = []
    seen = set()
    for r in rows:
        nm = norm(str(r.get("stinNm") or ""))
        if not nm or nm in seen:
            continue
        cands = [s for s in all_st if s["name"] == nm and s["line"] in data_lines] or \
                [s for s in all_st if s["code"] == str(r.get("stinCd")) and s["name"] == nm] or \
                [s for s in all_st if s["name"] == nm]
        if not cands:
            print(f"  좌표 없음: {nm}", flush=True)
            continue
        ref = picked[-1] if picked else None
        s = min(cands, key=lambda x: meters((ref[1], ref[2]), (x["lat"], x["lng"])) if ref else 0)
        seen.add(nm)
        picked.append([nm, round(s["lat"], 6), round(s["lng"], 6), int(r.get("stinConsOrdr") or 0)])
    by_order = {}
    for i, s in enumerate(picked):
        by_order.setdefault(s[3], []).append(i)
    orders = sorted(by_order)
    edges = []
    for a, b in zip(orders, orders[1:]):
        for j in by_order[b]:
            i = min(by_order[a], key=lambda i: meters(picked[i][1:3], picked[j][1:3]))
            if meters(picked[i][1:3], picked[j][1:3]) <= MAX_LINK_M:
                edges.append([i, j])
    # 차례로 못 이은 조각: 본선(가장 큰 조각)의 가장 가까운 역에 이음 (경의중앙선 가좌–신촌–서울역 지선은 차례가 거꾸로)
    while True:
        p = list(range(len(picked)))
        def f(i):
            while p[i] != i:
                p[i] = p[p[i]]
                i = p[i]
            return i
        for i, j in edges:
            p[f(i)] = f(j)
        comps = {}
        for i in range(len(picked)):
            comps.setdefault(f(i), []).append(i)
        if len(comps) < 2:
            break
        main = max(comps.values(), key=len)
        best = None
        for c in comps.values():
            if c is main:
                continue
            for i in c:
                for j in main:
                    d = meters(picked[i][1:3], picked[j][1:3])
                    if d <= JOIN_M and (best is None or d < best[0]):
                        best = (d, i, j)
        if not best:
            break
        edges.append([best[2], best[1]])
    # 순환선 (2호선): 마지막 차례 역과 첫 차례 역이 가까우면 이음
    if orders:
        first, last = by_order[orders[0]][0], by_order[orders[-1]][0]
        if len(picked) > 10 and meters(picked[first][1:3], picked[last][1:3]) < 2500:
            edges.append([last, first])
    return picked, edges


def main():
    k = key()
    all_st = stations_tsv()
    out = {}
    for lid, (ln, name, data_lines, color) in LINES.items():
        try:
            rows = fetch(k, ln)
        except Exception as e:
            print(f"{name}: 받지 못함 ({type(e).__name__})", flush=True)
            continue
        st, edges = build_line(rows, data_lines, all_st)
        rm, add = EDGE_FIX.get(lid, ([], []))
        at = {s[0]: i for i, s in enumerate(st)}
        drop = {frozenset((at.get(a), at.get(b))) for a, b in rm}
        edges = [e for e in edges if frozenset(e) not in drop] + [[at[a], at[b]] for a, b in add if a in at and b in at]
        if len(st) < 2:
            print(f"{name}: 역 {len(st)}개라 뺌", flush=True)
            continue
        out[lid] = {"name": name, "color": color, "stations": [s[:3] for s in st], "edges": edges}
        print(f"{name}: 역 {len(st)}개, 이음 {len(edges)}개", flush=True)
    if len(out) < 10:
        sys.exit(f"노선이 {len(out)}개뿐이라 쓰지 않음")
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{OUT.name}: 노선 {len(out)}개, {OUT.stat().st_size // 1024}KB", flush=True)


if __name__ == "__main__":
    main()
