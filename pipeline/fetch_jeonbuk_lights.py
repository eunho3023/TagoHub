#!/usr/bin/env python3
"""
전북특별자치도 신호등 현황 API(공공데이터포털 6450000/trafficlights, 약 3만 5천 건)를 전부 받아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다.

  python pipeline/fetch_jeonbuk_lights.py <출력.csv>

  - 필요: local.properties 의 TAGO_SERVICE_KEY (이 API 에 활용신청이 되어 있어야 함. 키는 출력·저장하지 않음)
  - 응답: latitude · longitude · remndrIdctYn(잔여시간표시기 Y/N). 자료 기준일은 2021-12-21(신설 때만 갱신).
"""
import csv
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "http://apis.data.go.kr/6450000/trafficlights/gettrafficlights"
PAGE = 1000


def key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("TAGO_SERVICE_KEY="):
            return line.split("=", 1)[1].replace("\\", "").strip()
    raise SystemExit("local.properties 에 TAGO_SERVICE_KEY 가 없습니다")


def rows_of(body: dict) -> list:
    """응답에서 (위도, 경도, 잔여시간표시기유무) 줄들. 좌표가 없는 항목은 뺌"""
    out = []
    for it in body.get("items") or []:
        la, ln = str(it.get("latitude", "")).strip(), str(it.get("longitude", "")).strip()
        if la and ln:
            out.append((la, ln, "Y" if str(it.get("remndrIdctYn", "")).strip().upper() == "Y" else "N"))
    return out


def fetch(page: int, k: str) -> dict:
    q = urllib.parse.urlencode({"pageNo": page, "numOfRows": PAGE, "type": "json"})
    req = urllib.request.Request(f"{URL}?serviceKey={urllib.parse.quote(k, safe='%')}&{q}", headers={"User-Agent": "Mozilla/5.0"})
    return json.load(urllib.request.urlopen(req, timeout=60))


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    k = key()
    first = fetch(1, k)
    total = int(first.get("totalCount", 0))
    pages = (total + PAGE - 1) // PAGE
    out = rows_of(first)
    for p in range(2, pages + 1):
        out += rows_of(fetch(p, k))
        time.sleep(0.2)
    with open(argv[1], "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows(out)
    print(f"전북 신호등 {len(out):,}줄 (API 전체 {total:,}건) → {argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
