"""서울시 버스 정류소·노선 공식 파일(서울 열린데이터광장/공공데이터포털, 2026-09-02 기준)로
앱 안 데이터(app/src/main/assets/bus_stops.db)의 서울 부분(id 가 SEB 로 시작)을 새로 채운다.

  python pipeline/update_seoul_bus.py [엑셀 파일이 있는 폴더]     # 기본: ~/Downloads

쓰는 파일 (폴더 안에 있어야 함, 이름 앞부분으로 찾음):
  서울시버스정류소위치정보  : 정류소(NODE_ID·ARS_ID·이름·좌표·유형)
  서울시버스노선ID정보       : 노선명 → ROUTEID
  서울시버스노선기본정보     : 평일/토요일/공휴일 시트 — 유형·기점·종점·배차간격·첫차·막차
  서울시버스노선별정류소정보 : 노선별 정류소 순서

고치는 것:
  - 정류소 번호(ARS)를 5자리로 맞춤. 예전 데이터는 엑셀을 거치며 앞의 0 이 빠져 '1001' 처럼 4자리가 된
    종로·중구·용산·성동 … 정류소가 3천 개 넘었고, 서울 도착정보 API 가 이 번호로 조회해서 도착정보가 안 나왔음.
  - 정류소 위치 파일에 없고 노선 파일에만 나오는 정류소(마을버스 가상 정류소 등)도 정류소로 넣음
  - 노선의 유형·기점·종점, 노선 정보(첫차·막차·평일/토/일 배차간격)를 채움 (예전엔 서울 노선 정보가 비어 있었음)
  - 정류소 이름은 정류소 표에서 가져오므로 노선별 정류소 표에는 이름을 두지 않음 (용량 절약, build_data.py 와 같은 방식)
"""
import sqlite3
import sys
from pathlib import Path

import openpyxl

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "app/src/main/assets/bus_stops.db"
PREFIX = "SEB"
CITY = "11"
VERSION = "20261024s"


def find(folder: Path, start: str) -> Path:
    for p in sorted(folder.glob("*.xlsx")):
        if p.name.startswith(start):
            return p
    raise SystemExit(f"{folder} 에 '{start}…' 파일이 없습니다.")


def rows(path: Path, sheet=0):
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    ws = wb.worksheets[sheet] if isinstance(sheet, int) else wb[sheet]
    it = ws.iter_rows(values_only=True)
    head = [str(h).strip() if h is not None else "" for h in next(it)]
    for r in it:
        if r and any(v is not None for v in r):
            yield dict(zip(head, r))


def ars(v):
    """정류소 번호 5자리 (숫자가 아니거나 0 이면 None = 번호 없는 가상 정류소)"""
    s = str(v).strip().split(".")[0] if v is not None else ""
    if not s.isdigit() or int(s) == 0:
        return None
    return s.zfill(5)


def clock(v):
    """'05:00' / '0500' / time → 'HH:MM'"""
    if v is None:
        return None
    if hasattr(v, "hour"):
        return f"{v.hour:02d}:{v.minute:02d}"
    s = str(v).strip()
    if ":" in s:
        h, m = s.split(":")[:2]
        return f"{int(h):02d}:{int(m):02d}"
    if s.isdigit():
        s = s.zfill(4)[-4:]
        return f"{s[:2]}:{s[2:]}"
    return None


def to_int(v):
    try:
        n = int(float(v))
        return n if n > 0 else None
    except (TypeError, ValueError):
        return None


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    stops_file = find(folder, "서울시버스정류소위치정보")
    ids_file = find(folder, "서울시버스노선ID정보")
    basic_file = find(folder, "서울시버스노선기본정보")
    rs_file = find(folder, "서울시버스노선별정류소정보")

    db = sqlite3.connect(DB)
    old_routes = {r[0]: r for r in db.execute("SELECT id, no, start_name, end_name, rtype, realtime FROM routes WHERE id LIKE ?", (PREFIX + "%",))}

    # ── 정류소 ──
    stops = {}  # NODE_ID → (name, lat, lng, ars)
    for r in rows(stops_file):
        node = str(r["NODE_ID"]).split(".")[0]
        stops[node] = (str(r["정류소명"]).strip(), float(r["Y좌표"]), float(r["X좌표"]), ars(r["ARS_ID"]))
    route_rows = list(rows(rs_file))
    extra = 0
    for r in route_rows:
        node = str(r["NODE_ID"]).split(".")[0]
        if node not in stops and r["X좌표"] is not None and r["Y좌표"] is not None:
            stops[node] = (str(r["정류소명"]).strip(), float(r["Y좌표"]), float(r["X좌표"]), ars(r["ARS_ID"]))
            extra += 1

    # ── 노선 ──
    ids = {str(r["노선명"]).strip(): str(r["ROUTEID"]).split(".")[0] for r in rows(ids_file)}
    basic = {}  # (시트, 노선번호) → 행
    for sheet, key in (("평일공동배차", "weekday"), ("토요일공동배차", "sat"), ("공휴일공동배차", "sun")):
        for r in rows(basic_file, sheet):
            basic.setdefault((key, str(r["노선번호"]).strip()), r)

    by_route = {}
    for r in route_rows:
        by_route.setdefault(str(r["ROUTE_ID"]).split(".")[0], []).append(r)
    for lst in by_route.values():
        lst.sort(key=lambda x: int(x["순번"]))

    # ── 써 넣기 (서울 부분을 통째로 바꿈) ──
    db.execute("CREATE TABLE IF NOT EXISTS route_info(id TEXT PRIMARY KEY, first TEXT, last TEXT, interval INTEGER, int_sat INTEGER, int_sun INTEGER) WITHOUT ROWID")
    db.execute("DELETE FROM route_stops WHERE route_id LIKE ?", (PREFIX + "%",))
    db.execute("DELETE FROM route_info WHERE id LIKE ?", (PREFIX + "%",))
    db.execute("DELETE FROM routes WHERE id LIKE ?", (PREFIX + "%",))
    db.execute("DELETE FROM stops WHERE id LIKE ?", (PREFIX + "%",))
    db.executemany(
        "INSERT INTO stops(id,name,lat,lng,no,city) VALUES(?,?,?,?,?,?)",
        [(PREFIX + n, v[0], v[1], v[2], v[3], CITY) for n, v in stops.items()],
    )

    n_routes = n_rs = no_basic = 0
    for name, rid in ids.items():
        app_id = PREFIX + rid
        wk = basic.get(("weekday", name))
        old = old_routes.get(app_id)
        seq_rows = by_route.get(rid, [])
        if not wk:
            no_basic += 1
        start = (wk or {}).get("기점명") or (old[2] if old else None) or (str(seq_rows[0]["정류소명"]).strip() if seq_rows else None)
        end = (wk or {}).get("종점명") or (old[3] if old else None) or (str(seq_rows[-1]["정류소명"]).strip() if seq_rows else None)
        rtype = ((wk or {}).get("유형") or "").strip() or (old[4] if old else None)
        first_node = PREFIX + str(seq_rows[0]["NODE_ID"]).split(".")[0] if seq_rows else None
        last_node = PREFIX + str(seq_rows[-1]["NODE_ID"]).split(".")[0] if seq_rows else None
        db.execute(
            "INSERT INTO routes(id,no,start_name,end_name,start_node,end_node,city,region,rtype,realtime) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (app_id, name, start, end, first_node, last_node, CITY, "서울", rtype, old[5] if old and old[5] else "none"),
        )
        if wk:
            sat = basic.get(("sat", name)) or {}
            sun = basic.get(("sun", name)) or {}
            db.execute(
                "INSERT INTO route_info VALUES(?,?,?,?,?,?)",
                (app_id, clock(wk.get("첫차시간")), clock(wk.get("막차시간")), to_int(wk.get("배차간격")), to_int(sat.get("배차간격")), to_int(sun.get("배차간격"))),
            )
        db.executemany(
            "INSERT OR REPLACE INTO route_stops VALUES(?,?,?,?,?,?,?)",
            [(app_id, int(r["순번"]), PREFIX + str(r["NODE_ID"]).split(".")[0], "", None, None, None) for r in seq_rows],
        )
        n_routes += 1
        n_rs += len(seq_rows)

    db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES('version', ?)", (VERSION,))
    db.commit()
    short = sum(1 for (n, v) in stops.items() if v[3] is None)
    print(f"정류소 {len(stops)}개 (노선 파일에서 보충 {extra}개, 번호 없음 {short}개)")
    print(f"노선 {n_routes}개 (기본정보 없는 노선 {no_basic}개), 노선별 정류소 {n_rs}행")
    # 검증: 노선 정류소가 모두 정류소 표에 있는지, 번호 길이
    missing = db.execute("SELECT count(DISTINCT stop_id) FROM route_stops WHERE route_id LIKE ? AND stop_id NOT IN (SELECT id FROM stops)", (PREFIX + "%",)).fetchone()[0]
    lens = db.execute("SELECT length(no), count(*) FROM stops WHERE id LIKE ? GROUP BY length(no)", (PREFIX + "%",)).fetchall()
    print("정류소 표에 없는 노선 정류소:", missing, "| ARS 길이 분포:", lens)
    db.execute("VACUUM")
    db.close()


if __name__ == "__main__":
    main()
