"""정류장·노선 DB 를 서버(Cloudflare Workers 정적 파일)에 올릴 작은 JSON 조각으로 나누기.

웹·PC 버전이 120MB DB 를 통째로 받지 않고 필요한 조각만 받게 한다 (무료 요금제: 파일 하나 25MB, 2만 개까지).
  tiles/{위도*10}_{경도*10}.json : 그 0.1도 칸의 정류장 [id, 이름, 위도, 경도, 번호, 시군, [지나는 노선 id...]]
  names/{첫 글자 코드}.json      : 이름의 낱말(점·공백으로 나눔)이 그 글자로 시작하는 정류장 (검색용, 칸 파일과 같은 모양)
  routes/{0~1023}.json          : 노선 묶음 {노선 id: {no, start, end, city, region, rtype, realtime, info, stops}}
                                  묶음 번호 = FNV-1a 32비트(노선 id, UTF-8) % 1024 (앱 공용 코드 WebData.bucket 과 같게)
  cities.json                   : {시군 코드: 이름}, {시군 코드: {노선 번호: [노선 id...]}}
  files/*                       : 앱 assets 의 다른 자료 파일 (열차 시간표, 지하철역 등) 그대로
  manifest.json                 : 만든 때, DB 버전, 파일 수

실행: python pipeline/build_web_data.py [--db 경로] [--out server/tago-proxy/public/data]
--db 를 안 주면 GitHub Release data-latest 의 최신 DB 를 받고, 못 받으면 앱에 들어 있는 DB 를 쓴다.
"""
import argparse, gzip, json, pathlib, shutil, sqlite3, sys, tempfile, time, urllib.request
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
RELEASE_DB = "https://github.com/eunho3023/TagoHub/releases/download/data-latest/bus_stops.db.gz"
BUCKETS = 1024
# 파일 하나가 25MB 를 넘으면 올라가지 않으므로 큰 DB 는 빼고, 나머지 자료 파일만
SKIP_FILES = {"bus_stops.db"}
MAX_FILE = 25 * 1024 * 1024


def bucket(route_id: str) -> int:
    h = 0x811C9DC5
    for b in route_id.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h % BUCKETS


def tile(lat: float, lng: float) -> str:
    return f"{int(lat * 10 // 1)}_{int(lng * 10 // 1)}"


def words(name: str):
    out = set()
    for w in name.replace(".", " ").replace("(", " ").replace(")", " ").split():
        out.add(w[0])
    return out


def dump(path: pathlib.Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def fetch_release_db(tmp: pathlib.Path):
    try:
        req = urllib.request.Request(RELEASE_DB, headers={"User-Agent": "TagoHub-build"})
        with urllib.request.urlopen(req, timeout=120) as r:
            gz = r.read()
        db = tmp / "bus_stops.db"
        db.write_bytes(gzip.decompress(gz))
        return db
    except Exception as e:  # 못 받으면 앱 안 DB
        print(f"최신 DB 를 받지 못해 앱 안 DB 를 씀: {e}", flush=True)
        return None


def build(db_path: pathlib.Path, out: pathlib.Path, assets: pathlib.Path = ASSETS) -> dict:
    if out.exists():
        shutil.rmtree(out)
    db = sqlite3.connect(db_path)
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    stop_routes = defaultdict(list)
    for rid, sid in db.execute("SELECT DISTINCT route_id, stop_id FROM route_stops WHERE stop_id IS NOT NULL"):
        stop_routes[sid].append(rid)

    tiles, names = defaultdict(list), defaultdict(list)
    for sid, name, lat, lng, no, city in db.execute("SELECT id, name, lat, lng, no, city FROM stops"):
        row = [sid, name, round(lat, 6), round(lng, 6), no, city, sorted(stop_routes.get(sid, []))]
        tiles[tile(lat, lng)].append(row)
        for ch in words(name):
            names[f"{ord(ch):x}"].append(row)
    for k, rows in tiles.items():
        dump(out / "tiles" / f"{k}.json", rows)
    for k, rows in names.items():
        dump(out / "names" / f"{k}.json", rows)

    info = {}
    if "route_info" in tables:
        for rid, first, last, iv, sat, sun in db.execute("SELECT id, first, last, interval, int_sat, int_sun FROM route_info"):
            info[rid] = [first, last, iv, sat, sun]
    stops_of = defaultdict(list)
    for rid, seq, sid, name, lat, lng, ud in db.execute(
            "SELECT rs.route_id, rs.seq, rs.stop_id, COALESCE(NULLIF(rs.name, ''), s.name, ''), COALESCE(rs.lat, s.lat), COALESCE(rs.lng, s.lng), rs.updown "
            "FROM route_stops rs LEFT JOIN stops s ON s.id = rs.stop_id ORDER BY rs.route_id, rs.seq"):
        stops_of[rid].append([seq, sid, name, lat and round(lat, 6), lng and round(lng, 6), ud])
    groups, by_no = defaultdict(dict), defaultdict(lambda: defaultdict(list))
    for rid, no, start, end, city, region, rtype, rt in db.execute(
            "SELECT id, no, start_name, end_name, city, region, rtype, realtime FROM routes"):
        groups[bucket(rid)][rid] = {"no": no, "start": start, "end": end, "city": city, "region": region, "rtype": rtype,
                                    "realtime": rt, "info": info.get(rid), "stops": stops_of.get(rid, [])}
        by_no[city][no].append(rid)
    for b, g in groups.items():
        dump(out / "routes" / f"{b}.json", g)
    cities = {c: n for c, n in db.execute("SELECT code, name FROM cities")}
    dump(out / "cities.json", {"names": cities, "routesByNo": by_no})

    copied = 0
    if assets.exists():
        for f in sorted(assets.iterdir()):
            if f.is_file() and f.name not in SKIP_FILES and f.stat().st_size <= MAX_FILE:
                (out / "files").mkdir(parents=True, exist_ok=True)
                shutil.copyfile(f, out / "files" / f.name)
                copied += 1
    version = dict(db.execute("SELECT k, v FROM meta").fetchall()).get("version") if "meta" in tables else None
    db.close()
    manifest = {"built": int(time.time()), "dbVersion": version, "tiles": len(tiles), "names": len(names),
                "routeBuckets": len(groups), "files": copied, "buckets": BUCKETS}
    dump(out / "manifest.json", manifest)
    total = sum(1 for _ in out.rglob("*") if _.is_file())
    if total > 19000:
        raise SystemExit(f"파일이 {total}개라 무료 요금제 한도(2만 개)에 가까워요")
    return manifest


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--db")
    ap.add_argument("--out", default=str(ROOT / "server/tago-proxy/public/data"))
    a = ap.parse_args(argv)
    with tempfile.TemporaryDirectory() as tmp:
        db = pathlib.Path(a.db) if a.db else (fetch_release_db(pathlib.Path(tmp)) or ASSETS / "bus_stops.db")
        m = build(db, pathlib.Path(a.out))
    print(f"웹 자료 만듦: 칸 {m['tiles']}개, 이름 {m['names']}개, 노선 묶음 {m['routeBuckets']}개, 자료 파일 {m['files']}개 → {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
