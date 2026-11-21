"""정류장 정리: 노선 없는 정류장 숨기기 + 같은 방면에 쪼개진 정류장을 한 점으로 묶기.

정류장 DB(bus_stops.db)에 두 표를 만든다 (앱 StopDb·웹 build_web_data.py 가 읽음):
  stop_hidden(id)      : 노선이 하나도 안 서는 정류장 → 지도·검색에서 뺌 (폐지·미사용)
  stop_spot(id, spot)  : 한 점으로 묶인 정류장 → 대표 정류장 id (묶음에 하나뿐이면 넣지 않음)

방면: 정류장을 지나는 노선마다 (앞 정류장 → 뒤 정류장) 방향을 더한 것. 노선마다 방향이 엇갈리면(종점 등) 모름.
묶는 규칙 (가까운 짝부터, 한 묶음 안에 반대 방면이 섞이지 않게, 묶음 지름 MAX_M 안):
  1) 다른 시군(지역번호), 이름이 같고, 같은 방면, MAX_M 안        — 시군마다 따로 등록돼 쪼개진 같은 정류장
  2) 다른 시군, 30m 안(이름이 같으면 60m 안), 반대 방면이 아님     — 예전 규칙 (같은 자리 다른 시군 정류장)
  3) 같은 시군, 이름이 같고, 같은 방면, 30m 안                    — 한 정류장이 번호만 달리 여러 개로 몰려 있는 것
  4) 정류장 번호가 앞의 0만 다르고(02001 = 2001) 100m 안, 반대 방면 아님 — 같은 정류장이 서울(SEB)·경기 버스(GGB)
     자료에 이름만 달리 따로 있는 것 (예: 명동성당.남대문세무서 02001 = 남대문세무서(중) 2001)
대표: 노선이 가장 많은 것 (같으면 교통안전공단 TS… 아닌 것, 번호 있는 것). 점은 대표 정류장 자리에 찍힘.

실행: python pipeline/stop_spots.py <bus_stops.db>   (build_data.py 가 DB 를 만들 때 부름)
"""
import math, re, sqlite3, sys
from collections import defaultdict

MAX_M = 150.0        # 1) 같은 이름·같은 방면이면 이만큼까지 한 정류장
RADIUS_M = 30.0      # 2) 다른 시군 같은 자리 (앱 StopMerge.RADIUS_M)
SAME_NAME_M = 60.0   # 2) 이름이 같으면 (앱 StopMerge.SAME_NAME_M)
CROWD_M = 30.0       # 3) 같은 시군, 같은 이름·같은 방면으로 몰려 있는 것
SAME_DIR = 60.0      # 이 각도(도) 안이면 같은 방면
OPPOSITE = 100.0     # 이 각도보다 벌어지면 반대 방면 (길 건너편)
CROWD_DIR = 45.0     # 3) 은 더 엄격하게
SAME_NO_M = 100.0    # 4) 번호가 같은 것 (앞의 0 빼고)
MIN_SURE = 0.35      # 노선들 방향을 더한 길이 / 노선 수 가 이보다 작으면 방면 모름
M_LAT = 111_000.0

_NAME = re.compile(r"[\s.·,()\[\]\-_]")


def name_key(n):
    return _NAME.sub("", n or "")


def meters(a_lat, a_lng, b_lat, b_lng):
    return math.hypot((a_lat - b_lat) * M_LAT, (a_lng - b_lng) * M_LAT * math.cos(math.radians((a_lat + b_lat) / 2)))


def angle(a, b):
    """두 방향(도) 사이 각 0~180"""
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


def headings(rows):
    """[(노선, 순번, 정류장, 위도, 경도)] (노선·순번 순) → {정류장: 방향(도, 북=0 동=90)}. 모르면 없음"""
    vec = defaultdict(lambda: [0.0, 0.0, 0])
    by_route = defaultdict(list)
    for rid, seq, sid, la, ln in rows:
        if la is not None and ln is not None:
            by_route[rid].append((seq, sid, la, ln))
    for pts in by_route.values():
        pts.sort(key=lambda p: p[0])
        for i, (_, sid, la, ln) in enumerate(pts):
            if not sid:
                continue
            p = pts[i - 1] if i > 0 else pts[i]
            n = pts[i + 1] if i + 1 < len(pts) else pts[i]
            dy = (n[2] - p[2]) * M_LAT
            dx = (n[3] - p[3]) * M_LAT * math.cos(math.radians(la))
            L = math.hypot(dx, dy)
            if L < 1:
                continue
            v = vec[sid]
            v[0] += dx / L
            v[1] += dy / L
            v[2] += 1
    out = {}
    for sid, (x, y, n) in vec.items():
        if n and math.hypot(x, y) / n >= MIN_SURE:
            out[sid] = math.degrees(math.atan2(x, y)) % 360
    return out


def spots(stops, served, head, routes=None):
    """stops: [(id, 이름, 위도, 경도, 번호, 시군)], served: {id: 노선 수}, head: {id: 방향},
    routes: {id: 지나는 노선 id 집합} (같은 노선이 둘 다 지나면 차례로 서는 다른 정류장이라 안 묶음)
    → (숨길 id 집합, {id: 대표 id} — 둘 이상 묶인 것만)"""
    routes = routes or {}
    hidden = {s[0] for s in stops if not served.get(s[0])}
    live = [s for s in stops if s[0] not in hidden and s[2] is not None and s[3] is not None]
    CELL = MAX_M / M_LAT
    grid = defaultdict(list)
    for i, s in enumerate(live):
        grid[(int(s[2] // CELL), int(s[3] // CELL))].append(i)
    keys = [name_key(s[1]) for s in live]
    nos = [str(s[4]).strip().lstrip("0") if s[4] not in (None, "", "null") else "" for s in live]
    edges = []
    for i, a in enumerate(live):
        gx, gy = int(a[2] // CELL), int(a[3] // CELL)
        ha = head.get(a[0])
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in grid.get((gx + dx, gy + dy), ()):
                    if j <= i:
                        continue
                    b = live[j]
                    d = meters(a[2], a[3], b[2], b[3])
                    if d > MAX_M:
                        continue
                    hb = head.get(b[0])
                    ang = angle(ha, hb) if ha is not None and hb is not None else None
                    same_name = bool(keys[i]) and keys[i] == keys[j]
                    if a[5] != b[5]:
                        ok = (same_name and ang is not None and ang <= SAME_DIR) or \
                             (d <= (SAME_NAME_M if same_name else RADIUS_M) and (ang is None or ang <= OPPOSITE))
                    else:
                        ok = same_name and d <= CROWD_M and ang is not None and ang <= CROWD_DIR
                    if not ok and nos[i] and nos[i] == nos[j] and d <= SAME_NO_M and (ang is None or ang <= OPPOSITE):
                        ok = True
                    if ok and not (routes.get(a[0], set()) & routes.get(b[0], set())):
                        edges.append((d, i, j))
    edges.sort()
    parent = list(range(len(live)))
    members = {i: [i] for i in range(len(live))}

    def root(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for d, i, j in edges:
        ri, rj = root(i), root(j)
        if ri == rj:
            continue
        mi, mj = members[ri], members[rj]
        bad = False
        for x in mi:
            for y in mj:
                sx, sy = live[x], live[y]
                if meters(sx[2], sx[3], sy[2], sy[3]) > MAX_M:
                    bad = True
                    break
                hx, hy = head.get(sx[0]), head.get(sy[0])
                if hx is not None and hy is not None and angle(hx, hy) > OPPOSITE:
                    bad = True
                    break
            if bad:
                break
        if bad:
            continue
        keep, gone = (ri, rj) if ri < rj else (rj, ri)
        parent[gone] = keep
        members[keep] = mi + mj if keep == ri else mj + mi
        del members[gone]

    spot = {}
    for g in members.values():
        if len(g) < 2:
            continue
        # 경기 밖(서울·인천 등)에 있는 경기 버스 정류장(GGB, 시군 31… 아님)은 그 지역 정류장 이름이 대표가 되게 뒤로
        main = min(g, key=lambda k: (live[k][0].startswith("GGB") and not str(live[k][5]).startswith("31"),
                                     -served.get(live[k][0], 0), live[k][5].startswith("TS"), live[k][4] is None, live[k][0]))
        for k in g:
            spot[live[k][0]] = live[main][0]
    return hidden, spot


def compute(db: sqlite3.Connection):
    """DB 를 읽어 (숨길 id 집합, {id: 대표 id}) — DB 는 그대로"""
    stops = db.execute("SELECT id, name, lat, lng, no, city FROM stops").fetchall()
    routes = defaultdict(set)
    for rid, sid in db.execute("SELECT DISTINCT route_id, stop_id FROM route_stops WHERE stop_id IS NOT NULL"):
        routes[sid].add(rid)
    served = {sid: len(r) for sid, r in routes.items()}
    rows = db.execute("SELECT rs.route_id, rs.seq, rs.stop_id, COALESCE(rs.lat, s.lat), COALESCE(rs.lng, s.lng) "
                      "FROM route_stops rs LEFT JOIN stops s ON s.id = rs.stop_id").fetchall()
    return spots(stops, served, headings(rows), routes)


def apply(db: sqlite3.Connection):
    """DB 에 stop_hidden·stop_spot 표를 새로 씀. (숨긴 수, 묶인 정류장 수, 묶음 수)"""
    hidden, spot = compute(db)
    db.execute("DROP TABLE IF EXISTS stop_hidden")
    db.execute("DROP TABLE IF EXISTS stop_spot")
    db.execute("CREATE TABLE stop_hidden(id TEXT PRIMARY KEY) WITHOUT ROWID")
    db.execute("CREATE TABLE stop_spot(id TEXT PRIMARY KEY, spot TEXT NOT NULL) WITHOUT ROWID")
    db.executemany("INSERT INTO stop_hidden VALUES(?)", [(i,) for i in sorted(hidden)])
    db.executemany("INSERT INTO stop_spot VALUES(?,?)", sorted(spot.items()))
    db.commit()
    return len(hidden), len(spot), len(set(spot.values()))


def main(argv):
    if len(argv) != 1:
        print(__doc__)
        return 2
    db = sqlite3.connect(argv[0])
    h, n, g = apply(db)
    db.close()
    print(f"정류장 정리: 노선 없는 정류장 {h}곳 숨김, {n}곳을 {g}개 점으로 묶음", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
