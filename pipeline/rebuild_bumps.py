#!/usr/bin/env python3
"""
과속방지턱 줄(bump)만 새로 만들어 앱의 안전 지점 목록(assets/safety_zones.csv)에 바꿔 넣는다. 다른 종류(카메라·보호구역 …)는 그대로 둔다.

  python pipeline/rebuild_bumps.py <과속방지턱정보.csv> [추가.csv ...]      # 기본 출력: app/src/main/assets/safety_zones.csv

  - 첫 파일은 공공데이터포털 '과속방지턱정보'(행정안전부, CP949 CSV, 약 14만 건). 뒤에 지자체가 따로 낸 CSV(예: 전북특별자치도_과속방지턱 현황 제공_20211221.zip 안의 CSV, UTF-8, 열 이름이 위도·경도)를 더 붙이면 합침.
  - 좌표가 한국 범위 밖이거나 비어 있으면 뺌. 같은 자리(소수 5자리 ≈ 1m)는 하나로.
  - 여러 번 돌려도 같은 결과.
"""
import csv
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_static_zones import DEFAULT_OUT, bump_rows, to_text  # noqa: E402


def read_rows(path):
    """CSV 를 UTF-8(BOM 포함) 먼저, 안 되면 CP949 로 읽음. 열 이름이 '위도'·'경도'인 지자체 파일은 표준 이름(WGS84위도·WGS84경도)으로 바꿔 줌"""
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        text = raw.decode("cp949", errors="replace")
    rows = list(csv.reader(text.splitlines()))
    if rows:
        rows[0] = ["WGS84위도" if c.strip() == "위도" else "WGS84경도" if c.strip() == "경도" else c for c in rows[0]]
    return rows


def read_bumps(path):
    return bump_rows(read_rows(path))


def replace_bumps(existing_text: str, new_bump_text: str) -> str:
    """기존 안전 지점 텍스트에서 bump 줄을 빼고, 새 bump 줄을 앞에 붙인다(종류 순으로 정렬된 기존 파일 순서를 지키려고 bump 가 맨 앞)"""
    kept = [line for line in existing_text.splitlines() if line and not line.startswith("bump,")]
    return new_bump_text + "\n".join(kept) + "\n"


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    out = DEFAULT_OUT
    points = []
    for path in argv[1:]:
        got = read_bumps(path)
        print(f"{os.path.basename(path)}: 좌표 있는 {len(got):,}개")
        points += got
    new_text = to_text(points)
    old = open(out, encoding="utf-8").read()
    before = Counter(l.split(",", 1)[0] for l in old.splitlines() if l)
    text = replace_bumps(old, new_text)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    after = Counter(l.split(",", 1)[0] for l in text.splitlines() if l)
    print(f"과속방지턱 {before['bump']:,} → {after['bump']:,}개 (다른 종류는 그대로: {sum(before.values()) - before['bump'] == sum(after.values()) - after['bump']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
