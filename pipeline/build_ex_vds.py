#!/usr/bin/env python3
"""
한국도로공사 VDS 설치정보(0404, 약 8,500개)를 받아 위·경도로 바꾼 ex_vds.json 을 만든다.
정체 API(0406)는 좌표 없이 VDS ID 만 줘서, 앱이 이 파일로 지도에 찍는다. VDS 위치는 거의 안 바뀌어 가끔 한 번만 돌리면 된다.

형식: {"VDS ID": [위도, 경도, "노선명"], ...}   (소수 5자리)
좌표: API 의 grs80x/grs80y 는 GRS80 타원체의 TM(중부원점: 경도 127°, 위도 38°, 가산 200000 / 600000, 축척 1) — EPSG:5186 과 같음
키: 환경변수 EX_API_KEY 또는 local.properties
"""
import json
import math
import os
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_FILES = [os.path.join(ROOT, "pipeline", "ex_vds.json"), os.path.join(ROOT, "app", "src", "main", "assets", "ex_vds.json")]
UA = "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/124.0 Mobile Safari/537.36"

# GRS80
A = 6378137.0
F = 1 / 298.257222101
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
LAT0, LON0, K0, FE, FN = math.radians(38.0), math.radians(127.0), 1.0, 200000.0, 600000.0


def _meridian(phi: float) -> float:
    return A * ((1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256) * phi
                - (3 * E2 / 8 + 3 * E2 ** 2 / 32 + 45 * E2 ** 3 / 1024) * math.sin(2 * phi)
                + (15 * E2 ** 2 / 256 + 45 * E2 ** 3 / 1024) * math.sin(4 * phi)
                - (35 * E2 ** 3 / 3072) * math.sin(6 * phi))


def tm_to_wgs84(x: float, y: float):
    """TM(동쪽 x, 북쪽 y, m) → (위도, 경도) 도. GRS80 과 WGS84 의 차이는 1mm 안쪽이라 그대로 씀"""
    m = _meridian(LAT0) + (y - FN) / K0
    mu = m / (A * (1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    s, c, t = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    n1 = A / math.sqrt(1 - E2 * s * s)
    t1 = t * t
    c1 = EP2 * c * c
    r1 = A * (1 - E2) / (1 - E2 * s * s) ** 1.5
    d = (x - FE) / (n1 * K0)
    lat = phi1 - (n1 * t / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * EP2) * d ** 4 / 24
                                  + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * EP2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                  + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * EP2 + 24 * t1 ** 2) * d ** 5 / 120) / c
    return math.degrees(lat), math.degrees(lon)


def get_key() -> str:
    key = os.environ.get("EX_API_KEY", "")
    if not key:
        path = os.path.join(ROOT, "local.properties")
        if os.path.exists(path):
            for line in open(path, encoding="utf-8"):
                if line.startswith("EX_API_KEY="):
                    key = line.split("=", 1)[1].strip()
    return key


def page(key: str, n: int):
    q = urllib.parse.urlencode({"key": key, "type": "json", "numOfRows": 99, "pageNo": n})
    url = "https://data.ex.co.kr/openapi/vdsinfo/vdsList?" + q
    j = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60).read().decode())
    return j.get("count"), next((v for v in j.values() if isinstance(v, list)), [])


def main() -> int:
    key = get_key()
    if not key:
        print("EX_API_KEY 가 없습니다.", file=sys.stderr)
        return 1
    total, rows = page(key, 1)
    total = int(total)
    n = 1
    while len(rows) < total:
        n += 1
        _, more = page(key, n)
        if not more:
            break
        rows += more
    out = {}
    skipped = 0
    for r in rows:
        try:
            lat, lon = tm_to_wgs84(float(r["grs80x"]), float(r["grs80y"]))
        except (KeyError, ValueError, TypeError):
            skipped += 1
            continue
        if not (32.0 <= lat <= 39.5 and 123.0 <= lon <= 133.0):
            skipped += 1
            continue
        out[r["vdsId"]] = [round(lat, 5), round(lon, 5), r.get("routeName") or ""]
    text = json.dumps(out, ensure_ascii=False, separators=(",", ":"))
    for p in OUT_FILES:
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
    print(f"VDS 전체 {total}건 중 변환 {len(out)}건 (건너뜀 {skipped}) → {len(text) // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
