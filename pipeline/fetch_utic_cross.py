#!/usr/bin/env python3
"""
경찰청·도로교통공단 UTIC 「교차로 기반정보」(CrossRoadInfoService/download/crossInfo)에서 지역별 교차로 위치를 받아
build_traffic_lights.py 가 읽는 CSV(열: 위도,경도,잔여시간표시기유무)로 저장한다. 신호 값은 없고 위치만 있음.

  python pipeline/fetch_utic_cross.py <출력.csv> [지역코드 ...]      (지역코드를 생략하면 인천 L02)
  python pipeline/fetch_utic_cross.py --table [지역코드 ...]        (신호 계획용 교차로 표 → assets/utic_signals.tsv, 생략하면 인천·대구)

  - 필요: local.properties 의 UTIC_API_KEY (키는 출력·저장하지 않음). 명세서: http://www.utic.go.kr/upload/CrossRoadInfoService.hwp
  - 응답은 엑셀 파일(.xlsx): 열 REGION_CD · INT_NO · INT_NM · X_COORD · Y_COORD · NODE_ID. 좌표는 도의 1천만 배 정수(경도 X, 위도 Y).
  - 이 서비스는 인천(L02)·대전·대구만 다룸. 대전·대구는 전국신호등표준데이터에 이미 많이 있어 인천만 받아도 됨.
  - 필요: pip install openpyxl
"""
import csv
import io
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "http://tsihub.utic.go.kr/tsi/api/CrossRoadInfoService/download/crossInfo"
DEFAULT = ["L02"]
TABLE_DEFAULT = ["L02", "L29"]       # 인천·대구 (대전 L30 은 2026-11 에 HTTP 409 로 파일이 오지 않음)
TABLE_OUT = ROOT / "app" / "src" / "main" / "assets" / "utic_signals.tsv"
SCALE = 1e7


def key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("UTIC_API_KEY="):
            return line.split("=", 1)[1].replace("\\", "").strip()
    raise SystemExit("local.properties 에 UTIC_API_KEY 가 없습니다")


def to_degrees(x, y):
    """정수 좌표(도 × 1천만) → (위도, 경도). 값이 없거나 한국 밖이면 None"""
    try:
        lat, lng = float(y) / SCALE, float(x) / SCALE
    except (TypeError, ValueError):
        return None
    return (lat, lng) if 33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0 else None


def points_from_rows(rows):
    """엑셀 표(첫 줄이 머리) → [(위도, 경도)]. 열 이름으로 찾음"""
    rows = [r for r in rows if r and any(c is not None for c in r)]
    head = list(rows[0])
    xi, yi = head.index("X_COORD"), head.index("Y_COORD")
    return [p for p in (to_degrees(r[xi], r[yi]) for r in rows[1:]) if p]


def table_from_rows(rows):
    """엑셀 표 → [(지역코드, 교차로번호, 이름, 위도, 경도)] (신호 계획 API 는 지역코드·교차로 이름으로 부르고 번호로 고름)"""
    rows = [r for r in rows if r and any(c is not None for c in r)]
    head = list(rows[0])
    ri, ni, mi = head.index("REGION_CD"), head.index("INT_NO"), head.index("INT_NM")
    xi, yi = head.index("X_COORD"), head.index("Y_COORD")
    out, seen = [], set()
    for r in rows[1:]:
        p = to_degrees(r[xi], r[yi])
        name = " ".join(str(r[mi] or "").split())     # 탭·여러 칸 띄움은 한 칸으로
        key = (str(r[ri] or "").strip(), str(r[ni] or "").strip())
        if p is None or not all(key) or not name or key in seen:
            continue
        seen.add(key)
        out.append((key[0], key[1], name, p[0], p[1]))
    return out


def download_rows(code: str, k: str) -> list:
    import openpyxl  # noqa: PLC0415

    enc = k if "%" in k else urllib.parse.quote(k, safe="")
    data = urllib.request.urlopen(f"{URL}?serviceKey={enc}&srchCTId={code}", timeout=120).read()
    if data[:2] != b"PK":
        raise SystemExit(f"{code}: 엑셀이 아닌 응답이 왔어요 (키나 지역코드를 확인하세요)")
    ws = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=True).worksheets[0]
    return list(ws.iter_rows(values_only=True))


def fetch(code: str, k: str) -> list:
    return points_from_rows(download_rows(code, k))


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    k = key()
    if argv[1] == "--table":
        items = []
        for code in argv[2:] or TABLE_DEFAULT:
            got = table_from_rows(download_rows(code, k))
            print(f"{code}: {len(got):,}곳")
            items += got
        with open(TABLE_OUT, "w", encoding="utf-8", newline="\n") as f:
            f.write("".join(f"{r}\t{n}\t{m}\t{la:.6f}\t{ln:.6f}\n" for r, n, m, la, ln in items))
        print(f"신호 계획 교차로 {len(items):,}곳 → {TABLE_OUT}")
        return 0
    pts = []
    for code in argv[2:] or DEFAULT:
        got = fetch(code, k)
        print(f"{code}: {len(got):,}곳")
        pts += got
    with open(argv[1], "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["위도", "경도", "잔여시간표시기유무"])
        w.writerows((f"{la:.6f}", f"{ln:.6f}", "N") for la, ln in pts)
    print(f"교차로 {len(pts):,}곳 → {argv[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
