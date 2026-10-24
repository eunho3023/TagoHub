"""서울시 교통 자료(TOPIS·TBIS)를 앱 안 데이터에 합친다.

    python pipeline/merge_tbis_data.py [CSV 가 있는 폴더]        # 기본: ~/Downloads

쓰는 파일 (폴더 안에서 이름으로 찾음):
  지하철역_GEOM (역사마스터).csv        → 수도권 지하철역 위치 보정·빠진 역 추가 (assets/subway_stations.tsv)
  노드정보.csv                          → 수도권 정류장(유형 0)의 번호·좌표: 앱에 없는 정류장 추가, 번호가 빈 곳 채움 (assets/bus_stops.db)
  노선별 경유노드.csv (+ 노드정보.csv)  → 서울 노선의 모양(지나는 길목 좌표) (assets/bus_route_shapes.tsv)
  노선별 좌표정보.csv                   → 노선 배치도(정류장을 줄 세운 그림)의 칸 위치 (assets/bus_route_layouts.tsv)

여러 번 돌려도 같은 결과가 되도록 만들었다 (이미 반영된 것은 건너뜀).
"""
import collections
import csv
import math
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
VERSION = "20261024t"


def rd(folder: Path, name: str):
    with open(folder / name, encoding="utf-8-sig", newline="") as f:
        r = csv.reader(f)
        head = [h.strip().lstrip("﻿") for h in next(r)]
        for row in r:
            yield {head[i]: row[i].strip().lstrip("﻿") for i in range(min(len(head), len(row)))}


def meters(la1, ln1, la2, ln2):
    return math.hypot((la1 - la2) * 111_000, (ln1 - ln2) * 88_000)


# ───────────────────────── 1. 지하철역 ─────────────────────────

def norm_station(s: str) -> str:
    t = re.sub(r"\(.*?\)", "", s).replace(" ", "").strip()
    return t[:-1] if len(t) >= 3 and t.endswith("역") else t


def family(line: str) -> str:
    """같은 노선끼리 묶는 이름. 한국철도공사 구간 이름(경부선·안산선 …)은 지하철 호선으로"""
    s = line.strip()
    if s.startswith("인천1"):
        return "ic1"
    if s.startswith("인천2"):
        return "ic2"
    m = re.match(r"^(\d)호선", s)
    if m:
        return m.group(1)
    table = [("경의", "gj"), ("중앙선", "gj"), ("분당", "bd"), ("수인", "bd"), ("신분당", "sbd"), ("공항", "ar"), ("경춘", "gc"), ("경강", "gg"),
             ("의정부", "uj"), ("에버", "ev"), ("우이", "ui"), ("신림", "sl"), ("서해", "sh"), ("김포", "gp"), ("GTX", "gtx"), ("광역급행", "gtx"),
             ("진접", "jj"), ("별내", "8"), ("자기부상", "mg"), ("동해", "dh")]
    if s.startswith("신분당"):
        return "sbd"
    for key, fam in table:
        if key in s:
            return fam
    legacy = {"경부선": "1", "경인선": "1", "경원선": "1", "장항선": "1", "안산선": "4", "과천선": "4", "일산선": "3"}
    return legacy.get(s, s)


# GEOM 노선 이름 → 앱 노선 이름·운영사 (앱에 같은 계열 역이 있으면 그 이름을 그대로 씀)
def merge_subway(folder: Path):
    geom = []
    for r in rd(folder, "지하철역_GEOM (역사마스터).csv"):
        try:
            geom.append((r["외구간_역_수"], r["역한글명칭"], r["호선명칭"], float(r["환승역Y좌표"]), float(r["환승역X좌표"])))
        except (KeyError, ValueError):
            continue
    path = ASSETS / "subway_stations.tsv"
    lines = path.read_text(encoding="utf-8").splitlines()
    header, rows = lines[0], [l.split("\t") for l in lines[1:] if l.strip()]
    capital = lambda la, ln: 36.55 <= la <= 38.35 and 126.3 <= ln <= 127.95
    app = [i for i, r in enumerate(rows) if len(r) >= 6 and capital(float(r[3]), float(r[4]))]
    fam_lines = {}  # 계열 → (앱 노선 이름, 운영사)
    for i in app:
        fam_lines.setdefault(family(rows[i][2]), (rows[i][2], rows[i][5]))
    moved = added = 0
    for code, name, line, la, ln in geom:
        fam = family(line)
        # 같은 계열에서 이름이 같거나 600m 안에 있는 앱 역을 같은 역으로 봄
        best, bd = None, 1e9
        for i in app:
            r = rows[i]
            if family(r[2]) != fam:
                continue
            d = meters(la, ln, float(r[3]), float(r[4]))
            same = norm_station(r[1]) == norm_station(name)
            if (same and d < 3000) or d < 250:
                if d < bd:
                    best, bd = i, d
        if best is not None:
            if bd > 60:
                rows[best][3], rows[best][4] = f"{la:.7f}", f"{ln:.7f}"
                moved += 1
        elif fam in fam_lines:
            near = [i for i in app if family(rows[i][2]) == fam and meters(la, ln, float(rows[i][3]), float(rows[i][4])) < 600]
            if not near:
                line_name, operator = fam_lines[fam]
                rows.append([code, name, line_name, f"{la:.7f}", f"{ln:.7f}", operator])
                app.append(len(rows) - 1)
                added += 1
    path.write_text("\n".join([header] + ["\t".join(r) for r in rows]) + "\n", encoding="utf-8")
    print(f"지하철: 위치 바로잡음 {moved}곳, 새로 넣음 {added}곳")


# ───────────────────────── 2. 정류장 ─────────────────────────

def merge_stops(folder: Path):
    db = sqlite3.connect(ASSETS / "bus_stops.db")
    known = {r[0]: r for r in db.execute("SELECT id, name, lat, lng, no, city FROM stops WHERE id LIKE 'SEB%' OR id LIKE 'GGB%' OR id LIKE 'ICB%'")}
    grid = collections.defaultdict(list)
    for sid, name, la, ln, no, city in known.values():
        grid[(round(la, 2), round(ln, 2))].append((sid, la, ln, city))

    def neighbour(la, ln):
        best = None
        for dx in (-0.01, 0, 0.01):
            for dy in (-0.01, 0, 0.01):
                for sid, a, b, city in grid.get((round(la + dx, 2), round(ln + dy, 2)), []):
                    d = meters(la, ln, a, b)
                    if d < 1500 and (best is None or d < best[0]):
                        best = (d, sid[:3], city)
        return best

    added = filled = skipped = 0
    for r in rd(folder, "노드정보.csv"):
        if r["노드유형"] != "0":
            continue
        try:
            la, ln = float(r["좌표Y"]), float(r["좌표X"])
        except ValueError:
            continue
        nid = r["노드ID"]
        no = r["정류장번호"] if r["정류장번호"] not in ("", "0", "-") else None
        no = no.zfill(5) if no and no.isdigit() else no
        have = next((known[p + nid] for p in ("SEB", "GGB", "ICB") if p + nid in known), None)
        if have:
            if no and not have[4]:
                db.execute("UPDATE stops SET no = ? WHERE id = ?", (no, have[0]))
                filled += 1
            continue
        near = neighbour(la, ln)
        if near is None:
            skipped += 1
            continue
        _, prefix, city = near   # 가까운 앱 정류장의 지역(서울·경기·인천)과 같은 것으로
        db.execute("INSERT OR IGNORE INTO stops(id,name,lat,lng,no,city) VALUES(?,?,?,?,?,?)", (prefix + nid, r["노드명"], la, ln, no, city))
        added += 1
    db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('version', ?)", (VERSION,))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"정류장: 새로 넣음 {added}개, 번호 채움 {filled}개, 지역을 몰라 건너뜀 {skipped}개")


# ───────────────────────── 3. 노선 모양 ─────────────────────────

def build_shapes(folder: Path):
    """서울 노선(앱 id SEB+노선ID)의 모양: 경유노드 순서대로 노드 좌표를 이은 선.
    줄: 노선ID \t 첫 점(위도,경도) \t 이어지는 점들의 변화량(1e-5도, 위도 경도 쌍을 쉼표로)"""
    db = sqlite3.connect(ASSETS / "bus_stops.db")
    seoul = {r[0][3:] for r in db.execute("SELECT id FROM routes WHERE id LIKE 'SEB%'")}
    db.close()
    coord = {}
    for r in rd(folder, "노드정보.csv"):
        try:
            x, y = float(r["맵핑좌표X"]), float(r["맵핑좌표Y"])
            if not (x > 100 and y > 30):
                x, y = float(r["좌표X"]), float(r["좌표Y"])
            coord[r["노드ID"]] = (y, x)
        except (KeyError, ValueError):
            continue
    per = collections.defaultdict(list)
    for r in rd(folder, "노선별 경유노드.csv"):
        if r["노선ID"] in seoul and r["노드ID"] in coord:
            per[r["노선ID"]].append((int(r["노드순번"]), coord[r["노드ID"]]))
    out, points = [], 0
    for rid, lst in sorted(per.items()):
        lst.sort(key=lambda t: t[0])
        pts = []
        for _, p in lst:
            if not pts or meters(pts[-1][0], pts[-1][1], p[0], p[1]) > 1:
                pts.append(p)
        if len(pts) < 2:
            continue
        deltas, prev = [], (round(pts[0][0] * 1e5), round(pts[0][1] * 1e5))
        for la, ln in pts[1:]:
            cur = (round(la * 1e5), round(ln * 1e5))
            deltas += [cur[0] - prev[0], cur[1] - prev[1]]
            prev = cur
        out.append(f"{rid}\t{pts[0][0]:.5f},{pts[0][1]:.5f}\t{','.join(map(str, deltas))}")
        points += len(pts)
    (ASSETS / "bus_route_shapes.tsv").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"노선 모양: 서울 노선 {len(out)}개 / 앱의 서울 노선 {len(seoul)}개, 점 {points}개")


# ───────────────────────── 4. 노선 배치도 ─────────────────────────

def build_layouts(folder: Path):
    """노선 배치도: 노선마다 정류장이 놓일 칸(x, y). 줄: 노선ID \t 노드ID:x:y,… (칸이 없는 노드는 뺌)"""
    per = collections.defaultdict(list)
    for r in rd(folder, "노선별 좌표정보.csv"):
        try:
            x, y = int(float(r["노드ID_X위치"])), int(float(r["노드ID_Y위치"]))
            seq = int(r["노드순번"])
        except (KeyError, ValueError):
            continue
        if x == 0 and y == 0:
            continue
        per[r["노선ID"]].append((seq, r["노드ID"], x, y))
    out = []
    for rid, lst in sorted(per.items()):
        lst.sort()
        out.append(f"{rid}\t" + ",".join(f"{n}:{x}:{y}" for _, n, x, y in lst))
    (ASSETS / "bus_route_layouts.tsv").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"노선 배치도: {len(out)}개 노선")


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    merge_subway(folder)
    merge_stops(folder)
    build_shapes(folder)
    build_layouts(folder)


if __name__ == "__main__":
    main()
