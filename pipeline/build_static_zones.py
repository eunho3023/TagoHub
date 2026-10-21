#!/usr/bin/env python3
"""
공공데이터포털에서 내려받은 파일 2개로 앱에 넣을 안전구간 점 목록을 만든다 (자주 안 바뀌는 자료라 앱에 함께 넣음).
  - 과속방지턱정보.csv                 (전국과속방지턱표준데이터, CP949 CSV)
  - 전국어린이보호구역표준데이터-*.xls  (전국어린이보호구역표준데이터, 엑셀 97~2003; 읽으려면 pip install xlrd)

결과: app/src/main/assets/safety_zones.csv — 한 줄에 한 곳, "종류코드,위도,경도,제한속도,이름" (이름에 쉼표가 있어도 마지막 칸이라 괜찮음)
  종류코드: bump 과속방지턱 / child 어린이 보호구역
사용: python pipeline/build_static_zones.py <과속방지턱.csv> <어린이보호구역.xls> [출력 경로]
새 파일을 받으면 같은 명령으로 다시 만들어 앱에 반영한다.
"""
import csv
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "safety_zones.csv")


def _num(v):
    try:
        return float(str(v).strip())
    except (TypeError, ValueError):
        return None


def in_korea(lat, lng) -> bool:
    return lat is not None and lng is not None and 33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0


def bump_rows(rows):
    """과속방지턱 CSV 행(헤더 포함) → [(code, lat, lng, name)]. 열 이름으로 찾음"""
    it = iter(rows)
    head = next(it)
    la, ln = head.index("WGS84위도"), head.index("WGS84경도")
    road = head.index("도로명") if "도로명" in head else None
    out = []
    for r in it:
        if len(r) <= max(la, ln):
            continue
        lat, lng = _num(r[la]), _num(r[ln])
        if in_korea(lat, lng):
            out.append(("bump", lat, lng, (r[road].strip() if road is not None else "")))
    return out


def child_rows(rows):
    """어린이보호구역 표(첫 줄이 비어 있을 수 있음) → [(code, lat, lng, name)]. 지정 해제(N)는 뺌"""
    rows = [r for r in rows if any(str(c).strip() for c in r)]
    head = [str(c).strip() for c in rows[0]]
    la, ln, nm = head.index("위도"), head.index("경도"), head.index("대상시설명")
    yn = head.index("어린이보호구역지정여부") if "어린이보호구역지정여부" in head else None
    out = []
    for r in rows[1:]:
        lat, lng = _num(r[la]), _num(r[ln])
        if not in_korea(lat, lng):
            continue
        if yn is not None and str(r[yn]).strip().upper() == "N":
            continue
        out.append(("child", lat, lng, str(r[nm]).strip()))
    return out


def to_text(points) -> str:
    """같은 종류·같은 자리(소수 5자리 ≈ 1m)는 하나로, 종류·위도 순으로 정렬해 한 줄씩"""
    seen, lines = set(), []
    for code, lat, lng, name in sorted(points, key=lambda p: (p[0], p[1], p[2])):
        key = (code, round(lat, 5), round(lng, 5))
        if key in seen:
            continue
        seen.add(key)
        name = name.replace("\n", " ").replace("\r", " ")
        lines.append(f"{code},{lat:.5f},{lng:.5f},,{name}")
    return "\n".join(lines) + "\n"


def read_xls(path):
    sys.path.insert(0, os.environ.get("PYLIB", ""))
    import xlrd  # noqa: PLC0415 - 없으면 안내 문구와 함께 멈춤

    sh = xlrd.open_workbook(path).sheet_by_index(0)
    return [sh.row_values(i) for i in range(sh.nrows)]


def main(argv) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    bumps_csv, child_xls = argv[1], argv[2]
    out = argv[3] if len(argv) > 3 else DEFAULT_OUT
    with open(bumps_csv, encoding="cp949", newline="") as f:
        bumps = bump_rows(csv.reader(f))
    children = child_rows(read_xls(child_xls))
    text = to_text(bumps + children)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    # 일부러 .gz 로 압축하지 않음: 안드로이드 빌드가 assets 의 .gz 를 풀고 이름도 바꿔 버림 (APK 가 알아서 압축함)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"과속방지턱 {len(bumps):,}개, 어린이보호구역 {len(children):,}개 → {text.count(chr(10)):,}줄, {os.path.getsize(out):,} bytes: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
