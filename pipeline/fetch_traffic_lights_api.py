#!/usr/bin/env python3
"""
전국신호등표준데이터 API(공공데이터포털 api.data.go.kr/openapi/tn_pubr_public_traffic_light_api, 약 10만 건)를 전부 받아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다. (엑셀은 5만 건까지만 받아지지만 API 는 전체)

  python pipeline/fetch_traffic_lights_api.py <출력.csv>

  - 필요: local.properties 의 TAGO_SERVICE_KEY (이 API 에 활용신청이 되어 있어야 함. 키는 출력·저장하지 않음)
  - 한 번에 1000건까지(5000건은 빈 응답). 응답: latitude · longitude · remndrIdctYn(잔여시간표시기 Y/N)
"""
import csv
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "https://api.data.go.kr/openapi/tn_pubr_public_traffic_light_api"
PAGE = 1000


def key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("TAGO_SERVICE_KEY="):
            return line.split("=", 1)[1].replace("\\", "").strip()
    raise SystemExit("local.properties 에 TAGO_SERVICE_KEY 가 없습니다")


def items_of(body: dict) -> list:
    item = ((body.get("body") or {}).get("items") or {}).get("item") or []
    return item if isinstance(item, list) else [item]


def regions_of(body: dict) -> list:
    """응답 항목의 (시도명) 목록: 어느 지역 자료가 들어 있는지 세어 보려고"""
    return [str(it.get("ctprvnNm", "")).strip() for it in items_of(body)]


def rows_of(body: dict) -> list:
    """응답에서 (위도, 경도, 잔여시간표시기유무) 줄들. 좌표가 없는 항목은 뺌"""
    out = []
    for it in items_of(body):
        la, ln = str(it.get("latitude", "")).strip(), str(it.get("longitude", "")).strip()
        if la and ln:
            out.append((la, ln, "Y" if str(it.get("remndrIdctYn", "")).strip().upper() == "Y" else "N"))
    return out


def fetch(page: int, k: str) -> dict:
    q = urllib.parse.urlencode({"pageNo": page, "numOfRows": PAGE, "type": "json"})
    req = urllib.request.Request(f"{URL}?serviceKey={urllib.parse.quote(k, safe='%')}&{q}", headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(3):
        try:
            return json.load(urllib.request.urlopen(req, timeout=90))
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    k = key()
    first = fetch(1, k)
    total = int(((first.get("body") or {}).get("totalCount")) or 0)
    pages = (total + PAGE - 1) // PAGE
    out = rows_of(first)
    regions = regions_of(first)
    for p in range(2, pages + 1):
        body = fetch(p, k)
        out += rows_of(body)
        regions += regions_of(body)
        if p % 10 == 0:
            print("진행", p, "/", pages)
        time.sleep(0.15)
    with open(argv[1], "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows(out)
    print(f"전국 신호등 {len(out):,}줄 (API 전체 {total:,}건) → {argv[1]}")
    from collections import Counter  # noqa: PLC0415
    print("시도별:", ", ".join(f"{n or '(빈값)'} {c:,}" for n, c in Counter(regions).most_common()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
