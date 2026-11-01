#!/usr/bin/env python3
"""
서울시 신호등 위치(서울 열린데이터광장 「서울시 신호등 관련 정보」 = 신호등 부착대, 서비스 trafficSafetyA057PInfo, 약 11만 건)를 전부 받아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다.

  python pipeline/fetch_seoul_lights.py <출력.csv> [서울특별시_잔여시간_표시_신호등_현황.xlsx]

  - 필요: local.properties 의 SEOUL_SUBWAY_KEY (서울 열린데이터광장 인증키. 키는 출력·저장하지 않음)
  - 한 번에 1000건까지. 좌표(XCRD·YCRD)는 EPSG:5186(한국 중부원점 2010, 미터)이라 위도·경도로 바꿈 ([to_wgs84]).
  - 부착대 자료에는 잔여시간표시기 정보가 없어서 모두 N 으로 쓰고, 엑셀(잔여시간 표시 신호등 현황, 약 1,500곳)을 함께 주면 그 위치는 Y 로 더함.
"""
import csv
import json
import math
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVICE = "trafficSafetyA057PInfo"
PAGE = 1000

# EPSG:5186 = Korea 2000 / Central Belt 2010: 횡메르카토르, 기준 위도 38°, 중앙 경선 127°, 축척 1, 가짜 동쪽 200000m·북쪽 600000m, 타원체 GRS80
_A = 6378137.0
_F = 1 / 298.257222101
_E2 = 2 * _F - _F * _F
_EP2 = _E2 / (1 - _E2)
_LAT0 = math.radians(38.0)
_LON0 = math.radians(127.0)
_FE, _FN = 200000.0, 600000.0


def _meridian(phi: float) -> float:
    e2, e4, e6 = _E2, _E2 ** 2, _E2 ** 3
    return _A * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                 - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * phi)
                 + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * phi)
                 - (35 * e6 / 3072) * math.sin(6 * phi))


def to_wgs84(x: float, y: float):
    """EPSG:5186 (x 동쪽, y 북쪽, 미터) → (위도, 경도) 도. 한국 2000 과 WGS84 는 1m 안쪽으로 같아서 그대로 씀"""
    e1 = (1 - math.sqrt(1 - _E2)) / (1 + math.sqrt(1 - _E2))
    m = _meridian(_LAT0) + (y - _FN)
    mu = m / (_A * (1 - _E2 / 4 - 3 * _E2 ** 2 / 64 - 5 * _E2 ** 3 / 256))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    s, c, t = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    n1 = _A / math.sqrt(1 - _E2 * s * s)
    r1 = _A * (1 - _E2) / (1 - _E2 * s * s) ** 1.5
    c1, t1 = _EP2 * c * c, t * t
    d = (x - _FE) / n1
    lat = phi1 - (n1 * t / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * _EP2) * d ** 4 / 24
                                  + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * _EP2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = _LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * _EP2 + 24 * t1 ** 2) * d ** 5 / 120) / c
    return math.degrees(lat), math.degrees(lon)


def key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("SEOUL_SUBWAY_KEY="):
            return line.split("=", 1)[1].replace("\\", "").strip()
    raise SystemExit("local.properties 에 SEOUL_SUBWAY_KEY 가 없습니다")


def rows_of(body: dict) -> list:
    return (body.get(SERVICE) or {}).get("row") or []


def total_of(body: dict) -> int:
    return int((body.get(SERVICE) or {}).get("list_total_count") or 0)


def point(row: dict):
    """부착대 한 줄 → (위도, 경도). 좌표가 없거나 서울 밖이면 None"""
    try:
        x, y = float(row.get("XCRD")), float(row.get("YCRD"))
    except (TypeError, ValueError):
        return None
    lat, lng = to_wgs84(x, y)
    return (lat, lng) if 37.0 <= lat <= 38.0 and 126.5 <= lng <= 127.5 else None


def timer_points(xlsx: str):
    """잔여시간 표시 신호등 현황 엑셀의 위치(X좌표·Y좌표 열) → [(위도, 경도)]"""
    import openpyxl  # noqa: PLC0415

    ws = openpyxl.load_workbook(xlsx, read_only=True, data_only=True).worksheets[0]
    rows = [r for r in ws.iter_rows(values_only=True) if any(c is not None for c in r)]
    head = next(i for i, r in enumerate(rows) if r and "X좌표" in r)
    xi, yi = rows[head].index("X좌표"), rows[head].index("Y좌표")
    out = []
    for r in rows[head + 1:]:
        try:
            lat, lng = to_wgs84(float(r[xi]), float(r[yi]))
        except (TypeError, ValueError):
            continue
        if 37.0 <= lat <= 38.0 and 126.5 <= lng <= 127.5:
            out.append((lat, lng))
    return out


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    k = key()
    pts = []
    start, total = 1, None
    while total is None or start <= total:
        url = f"http://openapi.seoul.go.kr:8088/{k}/json/{SERVICE}/{start}/{start + PAGE - 1}/"
        for attempt in range(3):
            try:
                body = json.loads(urllib.request.urlopen(url, timeout=60).read().decode("utf-8"))
                break
            except Exception as e:  # noqa: BLE001
                if attempt == 2:
                    raise SystemExit(f"{start}번째부터 받지 못했어요: {type(e).__name__}")
                time.sleep(2)
        if total is None:
            total = total_of(body)
            if not total:
                raise SystemExit("받을 자료가 없어요 (키나 서비스 이름을 확인하세요)")
        pts += [p for p in map(point, rows_of(body)) if p]
        start += PAGE
        if (start // PAGE) % 20 == 0:
            print(f"진행 {min(start, total):,} / {total:,}")
    timers = timer_points(argv[2]) if len(argv) > 2 else []
    with open(argv[1], "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows((f"{la:.6f}", f"{ln:.6f}", "N") for la, ln in pts)
        w.writerows((f"{la:.6f}", f"{ln:.6f}", "Y") for la, ln in timers)
    print(f"서울 신호등 부착대 {len(pts):,}줄 (전체 {total:,}건) + 잔여시간표시기 {len(timers):,}곳 → {argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
