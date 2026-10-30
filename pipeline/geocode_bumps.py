#!/usr/bin/env python3
"""
좌표 없이 주소만 있는 지자체 과속방지턱 CSV 에 카카오 주소 검색으로 위도·경도를 붙여, rebuild_bumps.py 가 읽는 CSV 로 바꾼다.

  python pipeline/geocode_bumps.py <입력.csv> <출력.csv>

  - 입력: 열 '시군구'(예: 서울특별시 동작구)와 '위치'(도로명 주소, 예: 강남초등6길 2)가 있는 CSV (UTF-8 또는 CP949).
  - 출력: 열 도로명·시군구명·위도·경도 (UTF-8). 못 찾은 줄은 뺌.
  - 검색 결과는 pipeline/out/bump_geocode.json 에 저장해 두고 다시 돌릴 때 재사용. 필요: local.properties 의 KAKAO_REST_KEY (키는 출력·저장하지 않음)
  - 시군구 이름으로 범위를 좁혀 엉뚱한 지역의 같은 도로명을 거름(결과가 그 시군구 이름을 포함해야 함).
"""
import csv
import io
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "pipeline/out/bump_geocode.json"
URL = "https://dapi.kakao.com/v2/local/search/address.json?"


def rest_key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("KAKAO_REST_KEY="):
            return line.split("=", 1)[1].replace("\\", "").strip()
    raise SystemExit("local.properties 에 KAKAO_REST_KEY 가 없습니다")


def read_rows(path):
    raw = Path(path).read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return list(csv.DictReader(io.StringIO(raw.decode(enc))))
        except UnicodeDecodeError:
            continue
    return list(csv.DictReader(io.StringIO(raw.decode("cp949", errors="replace"))))


def pick(documents, region: str):
    """검색 결과 중 주소에 시군구 이름(예: 동작구)이 들어 있는 첫 결과의 (위도, 경도)"""
    last = region.split()[-1] if region else ""
    for d in documents:
        addr = " ".join(filter(None, [
            (d.get("road_address") or {}).get("address_name"), (d.get("address") or {}).get("address_name"), d.get("address_name"),
        ]))
        if last and last in addr:
            try:
                return float(d["y"]), float(d["x"])
            except (KeyError, ValueError):
                continue
    return None


def main(argv) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    rows = read_rows(argv[1])
    cache = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    key = rest_key()
    out, asked, miss = [], 0, 0
    for r in rows:
        region, road = (r.get("시군구") or "").strip(), (r.get("위치") or "").strip()
        if not road:
            continue
        query = f"{region} {road}"
        if query not in cache:
            req = urllib.request.Request(URL + urllib.parse.urlencode({"query": query, "size": 5}), headers={"Authorization": f"KakaoAK {key}"})
            try:
                docs = json.load(urllib.request.urlopen(req, timeout=20)).get("documents", [])
            except Exception as e:  # 한 건 실패로 멈추지 않음
                print("실패", query, type(e).__name__)
                time.sleep(1)
                continue
            cache[query] = pick(docs, region)
            asked += 1
            if asked % 100 == 0:
                CACHE.parent.mkdir(parents=True, exist_ok=True)
                CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
                print("진행", asked)
            time.sleep(0.05)
        hit = cache[query]
        if hit:
            out.append({"도로명": road, "시군구명": region, "위도": f"{hit[0]:.6f}", "경도": f"{hit[1]:.6f}"})
        else:
            miss += 1
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    with open(argv[2], "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["도로명", "시군구명", "위도", "경도"])
        w.writeheader()
        w.writerows(out)
    print(f"입력 {len(rows):,}줄 → 좌표 {len(out):,}개 (못 찾음 {miss:,}, 새로 검색 {asked:,}) → {argv[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
