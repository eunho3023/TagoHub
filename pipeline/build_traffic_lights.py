#!/usr/bin/env python3
"""
전국신호등표준데이터(공공데이터포털, 행정안전부) 엑셀(.xls)에서 신호등 위치만 뽑아 앱에 넣을 작은 파일(assets/traffic_lights.csv)을 만든다.

  python pipeline/build_traffic_lights.py <전국신호등표준데이터-날짜.xls> [추가.csv ...] [--out 출력경로]
  (필요: pip install xlrd — 환경변수 PYLIB 에 설치한 폴더를 두면 거기서도 찾음)
  - 추가.csv: 열 위도·경도·잔여시간표시기유무(Y/N) 가 있는 UTF-8 CSV. 예: pipeline/fetch_jeonbuk_lights.py 로 받은 전북 신호등.
  - --out 을 생략하면 app/src/main/assets/traffic_lights.csv 에 씀.

  - 위도·경도가 한국 범위 안인 줄만. **[CLUSTER_M]미터 안의 신호등은 한 곳으로 합침**(사거리 하나에 신호등이 8~12개 있어도 점 하나. 합친 점은 그 신호등 중 가운데에 가장 가까운 실제 신호등 자리).
  - 한 줄: "위도,경도,잔여시간표시기" — 잔여시간표시기는 합친 신호등 중 하나라도 있으면 1. 위도 순으로 정렬(앱이 이진 탐색).
  - 이 엑셀은 포털에서 한 번에 50,000건까지만 받아져서 전국 전부가 아닐 수 있음 (시군구별 파일을 더 받으면 같은 방식으로 합칠 수 있게 여러 파일 가능).
  - 여러 번 돌려도 같은 결과.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "traffic_lights.csv")
DECIMALS = 5

# 이 거리(미터) 안의 신호등은 같은 교차로로 보고 한 점으로 합침. 큰 사거리도 가장 먼 신호등끼리 70m 안팎이라 가운데 기준 반지름 45m
CLUSTER_M = 45.0
M_PER_DEG_LAT = 111_320.0
M_PER_DEG_LNG = 90_000.0   # 위도 36° 근처(한국 가운데)에서의 경도 1도


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
    """가까운 신호등([CLUSTER_M]m 안)을 한 교차로 한 점으로. 점은 합친 신호등 중 가운데(평균)에 가장 가까운 실제 신호등 자리, 잔여시간표시기는 하나라도 있으면 있음. 위도·경도 순 정렬"""
    cell_lat, cell_lng = 0.0006, 0.0007           # 약 67m·63m 칸: 이웃 칸까지만 보면 [CLUSTER_M] 안의 군집을 다 찾음
    grid = {}                                      # 칸 → 그 칸에 중심이 있는 군집 번호들
    clusters = []                                  # [위도 합, 경도 합, 개수, 잔여시간표시기, 합친 신호등 좌표들]
    for lat, lng, t in sorted(pts, key=lambda p: (round(p[0], 6), round(p[1], 6))):
        ci, cj = int(lat / cell_lat), int(lng / cell_lng)
        best, best_d = None, CLUSTER_M
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                for k in grid.get((ci + di, cj + dj), ()):
                    c = clusters[k]
                    d = (((lat - c[0] / c[2]) * M_PER_DEG_LAT) ** 2 + ((lng - c[1] / c[2]) * M_PER_DEG_LNG) ** 2) ** 0.5
                    if d <= best_d:
                        best, best_d = k, d
        if best is None:
            clusters.append([lat, lng, 1, t, [(lat, lng)]])
            grid.setdefault((ci, cj), []).append(len(clusters) - 1)
        else:
            c = clusters[best]
            c[0] += lat; c[1] += lng; c[2] += 1; c[3] = c[3] or t; c[4].append((lat, lng))
    return sorted((*_nearest_member(c), c[3]) for c in clusters)


def _nearest_member(c):
    """군집의 가운데(평균)에 가장 가까운 실제 신호등 좌표. 평균 좌표는 길 한가운데나 건물 위에 떨어질 수 있어서. 같은 거리면 작은 좌표"""
    mlat, mlng = c[0] / c[2], c[1] / c[2]
    la, ln = min(c[4], key=lambda p: (((p[0] - mlat) * M_PER_DEG_LAT) ** 2 + ((p[1] - mlng) * M_PER_DEG_LNG) ** 2, p))
    return round(la, DECIMALS), round(ln, DECIMALS)


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
