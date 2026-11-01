#!/usr/bin/env python3
"""
한국지역정보개발원 교차로 정보 API(공공데이터포털 B551982/rti/crsrd_map_info)에서 교차로 위치를 받아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다. 신호 값은 오지 않고 위치만 옴.

  python pipeline/fetch_klid_lights.py <출력.csv> [시도코드 ...]      (시도코드를 생략하면 제주 5000000000, 울산 3100000000)

  - 필요: local.properties 의 KLID_BUS_KEY (없으면 TAGO_SERVICE_KEY). 키는 출력·저장하지 않음
  - 2026-11 확인: 서울 2,779곳·울산 402곳·제주 1,058곳만 자료가 있고 다른 시도는 NODATA(K3).
    서울은 pipeline/fetch_seoul_lights.py 로 받는 서울시 신호등 부착대가 더 자세해서 여기서는 받지 않아도 됨.
"""
import csv
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "https://apis.data.go.kr/B551982/rti/crsrd_map_info"
PAGE = 1000
DEFAULT = ["5000000000", "3100000000"]


def key() -> str:
    props = {}
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip().replace("\\", "")
    k = props.get("KLID_BUS_KEY") or props.get("TAGO_SERVICE_KEY")
    if not k:
        raise SystemExit("local.properties 에 KLID_BUS_KEY 또는 TAGO_SERVICE_KEY 가 없습니다")
    return k


def items_of(body: dict) -> list:
    item = ((body.get("body") or {}).get("items") or {}).get("item") or []
    return item if isinstance(item, list) else [item]


def total_of(body: dict) -> int:
    return int((body.get("body") or {}).get("totalCount") or 0)


def point(item: dict):
    try:
        lat, lng = float(item.get("mapCtptIntLat")), float(item.get("mapCtptIntLot"))
    except (TypeError, ValueError):
        return None
    return (lat, lng) if 33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0 else None


def fetch(stdg: str, k: str) -> list:
    enc = k if "%" in k else urllib.parse.quote(k, safe="")
    pts, page, total = [], 1, None
    while total is None or (page - 1) * PAGE < total:
        url = f"{URL}?serviceKey={enc}&type=json&pageNo={page}&numOfRows={PAGE}&stdgCd={stdg}"
        body = json.loads(urllib.request.urlopen(url, timeout=60).read().decode("utf-8"))
        if total is None:
            total = total_of(body)
        pts += [p for p in map(point, items_of(body)) if p]
        page += 1
    return pts


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    k = key()
    pts = []
    for stdg in argv[2:] or DEFAULT:
        got = fetch(stdg, k)
        print(f"{stdg}: {len(got):,}곳")
        pts += got
    with open(argv[1], "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows((f"{la:.6f}", f"{ln:.6f}", "N") for la, ln in pts)
    print(f"교차로 {len(pts):,}곳 → {argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
