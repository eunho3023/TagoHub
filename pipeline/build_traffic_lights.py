#!/usr/bin/env python3
"""
전국신호등표준데이터(공공데이터포털, 행정안전부) 엑셀(.xls)에서 신호등 위치만 뽑아 앱에 넣을 작은 파일(assets/traffic_lights.csv)을 만든다.

  python pipeline/build_traffic_lights.py <전국신호등표준데이터-날짜.xls> [추가.csv ...] [--out 출력경로]
  (필요: pip install xlrd — 환경변수 PYLIB 에 설치한 폴더를 두면 거기서도 찾음)
  - 추가.csv: 열 위도·경도·잔여시간표시기유무(Y/N) 가 있는 UTF-8 CSV. 예: pipeline/fetch_jeonbuk_lights.py 로 받은 전북 신호등.
  - --out 을 생략하면 app/src/main/assets/traffic_lights.csv 에 씀.

  - 위도·경도가 한국 범위 안인 줄만. 약 11m(소수 4자리) 안은 한 곳으로 합침(한 교차로의 신호등 여러 개가 점 하나로).
  - 한 줄: "위도,경도,잔여시간표시기" — 잔여시간표시기는 그 자리 신호등 중 하나라도 있으면 1. 위도 순으로 정렬(앱이 이진 탐색).
  - 이 엑셀은 포털에서 한 번에 50,000건까지만 받아져서 전국 전부가 아닐 수 있음 (시군구별 파일을 더 받으면 같은 방식으로 합칠 수 있게 여러 파일 가능).
  - 여러 번 돌려도 같은 결과.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "traffic_lights.csv")
DECIMALS = 4


def in_korea(lat, lng) -> bool:
    return lat is not None and lng is not None and 33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0


def num(v):
    try:
        return float(str(v).strip())
    except (TypeError, ValueError):
        return None


def points(rows):
    """표(첫 줄이 비어 있을 수 있음) → [(위도, 경도, 잔여시간표시기 있음)]. 열 이름으로 찾음"""
    rows = [r for r in rows if any(str(c).strip() for c in r)]
    head = [str(c).strip() for c in rows[0]]
    la, ln = head.index("위도"), head.index("경도")
    timer = head.index("잔여시간표시기유무") if "잔여시간표시기유무" in head else None
    out = []
    for r in rows[1:]:
        lat, lng = num(r[la]), num(r[ln])
        if in_korea(lat, lng):
            out.append((lat, lng, timer is not None and str(r[timer]).strip().upper() == "Y"))
    return out


def merge(pts) -> list:
    """같은 자리(소수 [DECIMALS]자리)는 하나로, 잔여시간표시기는 하나라도 있으면 있음. 위도·경도 순 정렬"""
    seen = {}
    for lat, lng, t in pts:
        key = (round(lat, DECIMALS), round(lng, DECIMALS))
        seen[key] = seen.get(key, False) or t
    return [(la, ln, t) for (la, ln), t in sorted(seen.items())]


def to_text(merged) -> str:
    return "".join(f"{la:.{DECIMALS}f},{ln:.{DECIMALS}f},{1 if t else 0}\n" for la, ln, t in merged)


def read_xls(path):
    sys.path.insert(0, os.environ.get("PYLIB", ""))
    import xlrd  # noqa: PLC0415 - 없으면 안내 문구와 함께 멈춤

    sh = xlrd.open_workbook(path).sheet_by_index(0)
    return [sh.row_values(i) for i in range(sh.nrows)]


def read_csv(path):
    import csv  # noqa: PLC0415

    with open(path, encoding="utf-8-sig", newline="") as f:
        return [r for r in csv.reader(f)]


def main(argv) -> int:
    args = argv[1:]
    out = DEFAULT_OUT
    if "--out" in args:
        i = args.index("--out")
        out = args[i + 1]
        del args[i:i + 2]
    paths = [a for a in args if a.lower().endswith((".xls", ".csv"))]
    if not paths:
        print(__doc__)
        return 2
    pts = []
    for p in paths:
        got = points(read_xls(p) if p.lower().endswith(".xls") else read_csv(p))
        print(f"{os.path.basename(p)}: 좌표 있는 {len(got):,}줄")
        pts += got
    merged = merge(pts)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(to_text(merged))
    print(f"신호등 {len(merged):,}곳(잔여시간표시기 {sum(1 for _, _, t in merged if t):,}곳) → {os.path.getsize(out):,} bytes: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
