"""보행자우선도로·버스전용차로를 앱의 안전 지점 목록(assets/safety_zones.csv)에 합친다.

    python pipeline/build_extra_zones.py  [CSV·XLS 가 있는 폴더]        # 기본: ~/Downloads

읽는 파일
  행안부_보행자우선도로 지정 현황_*.csv  (주소·도로 이름만 있음) → 카카오 지도 검색으로 좌표를 구해 ped 종류로
        - 좌표 검색 결과는 pipeline/out/ped_geocode.json 에 저장해 두고 다음에 다시 쓴다.
        - 필요: local.properties 의 KAKAO_REST_KEY (키는 출력·저장하지 않음)
  전국버스전용차로정보표준데이터-*.xls (시점 좌표가 있음) → buslane 종류로. 필요: pip install xlrd

여러 번 돌려도 같은 결과.
"""
import csv
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/safety_zones.csv"
CACHE = ROOT / "pipeline/out/ped_geocode.json"
OWN = {"ped", "buslane"}
# 시도별 대략의 범위 (위도 아래·위, 경도 왼·오른). 검색이 엉뚱한 지역을 찾았을 때 거르는 용도
SIDO_BOX = {
    "서울": (37.40, 37.72, 126.76, 127.19), "부산": (34.88, 35.40, 128.75, 129.32), "대구": (35.60, 36.05, 128.35, 128.80),
    "인천": (36.9, 37.85, 124.5, 126.8), "대전": (36.18, 36.50, 127.20, 127.55), "울산": (35.30, 35.75, 128.95, 129.50),
    "경기": (36.85, 38.35, 126.3, 127.9), "강원": (37.0, 38.7, 127.0, 129.4), "충북": (36.0, 37.3, 127.2, 128.7),
    "충남": (35.9, 37.2, 125.9, 127.7), "전북": (35.2, 36.2, 126.3, 127.9), "전남": (33.8, 35.5, 125.0, 127.9),
    "경북": (35.4, 37.6, 128.0, 131.0), "경남": (34.6, 35.9, 127.5, 129.5), "제주": (33.1, 33.6, 126.1, 127.0),
}


def inside_sido(sido: str, lat: float, lng: float) -> bool:
    key = "전남" if sido.startswith("전남") else sido
    b = SIDO_BOX.get(key)
    return b is None or (b[0] <= lat <= b[1] and b[2] <= lng <= b[3])


def clean(s: str) -> str:
    return " ".join(str(s).replace(",", " ").split())


def kakao_key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("KAKAO_REST_KEY="):
            return line.split("=", 1)[1].strip().replace("\\", "")   # properties 의 \= 같은 이스케이프를 뺌
    raise SystemExit("local.properties 에 KAKAO_REST_KEY 가 없습니다")


def kakao(path: str, query: str, key: str):
    url = f"https://dapi.kakao.com/v2/local/search/{path}.json?" + urllib.parse.urlencode({"query": query, "size": 1})
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {key}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        docs = json.loads(r.read().decode("utf-8")).get("documents", [])
    return (float(docs[0]["y"]), float(docs[0]["x"])) if docs else None


def locate(region: str, road: str, key: str):
    """'서울 종로구' + '인사동4길' → (위도, 경도). 주소 검색 → 키워드 검색 순"""
    road = re.sub(r"\(.*?\)|[~∼].*|하단|인근|일대|주변|보행자우선도로", "", road).split(",")[0].strip()
    for q in (f"{region} {road}", road):
        for path in ("address", "keyword"):
            try:
                hit = kakao(path, q, key)
            except Exception:
                hit = None
            if hit:
                return hit
            time.sleep(0.05)
    return None


def pedestrian(folder: Path):
    files = sorted(folder.glob("*보행자우선도로*.csv"))
    if not files:
        return []
    cache = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    key = None
    out = []
    with open(files[-1], encoding="cp949", newline="") as f:
        rows = list(csv.reader(f))[1:]
    for r in rows:
        if len(r) < 5:
            continue
        sido, sigungu, name, where = r[1], r[2], r[3], r[4]
        ck = f"{sido}|{sigungu}|{where or name}"
        if ck not in cache:
            key = key or kakao_key()
            cache[ck] = locate(f"{sido} {sigungu}", where or name, key)
        hit = cache[ck]
        if hit and inside_sido(sido, hit[0], hit[1]):
            out.append(("ped", hit[0], hit[1], clean(name)[:40] or "보행자우선도로"))
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    print(f"보행자우선도로: {len(out)}/{len(rows)} 곳 좌표 찾음")
    return out


def buslane(folder: Path):
    import xlrd
    files = sorted(folder.glob("*버스전용차로*.xls"))
    if not files:
        return []
    sh = xlrd.open_workbook(files[-1]).sheet_by_index(0)
    head = [str(c).strip() for c in sh.row_values(1)]
    out = []
    for i in range(2, sh.nrows):
        r = dict(zip(head, sh.row_values(i)))
        try:
            lat, lng = float(r["버스전용차로기점위도"]), float(r["버스전용차로기점경도"])
        except (KeyError, ValueError):
            continue
        if not (33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0):
            continue
        road = clean(r.get("도로노선명", "")) or clean(r.get("버스전용차로기점명", ""))
        out.append(("buslane", lat, lng, road[:40] or "버스전용차로"))
    print(f"버스전용차로: {len(out)}곳")
    return out


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    kept = [l for l in OUT.read_text(encoding="utf-8").splitlines() if l.split(",", 1)[0] not in OWN]
    seen, rows = set(), []
    for code, lat, lng, name in pedestrian(folder) + buslane(folder):
        k = (code, round(lat, 4), round(lng, 4))
        if k in seen:
            continue
        seen.add(k)
        rows.append(f"{code},{lat:.5f},{lng:.5f},,{name}")
    OUT.write_text("\n".join(kept + rows) + "\n", encoding="utf-8")
    print("새로", len(rows), "줄")


if __name__ == "__main__":
    main()
