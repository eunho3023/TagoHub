#!/usr/bin/env python3
"""
서울 교통빅데이터 플랫폼(T Data)의 「교차로 Map 정보」(v2xCrossroadMapInformation) CSV에서 앱에 넣을 작은 표(assets/seoul_signals.tsv)를 만든다.
실시간 신호 API(v2xSignalPhaseTimingFusionCurrentInfo)는 교차로 ID(itstId)로 부르고 응답에 위치가 없어서, ID→이름·위치 표가 따로 필요하다.

  python pipeline/build_seoul_signals.py <v2xCrossroadMapInformation.csv> [--out 출력경로]

  - 입력 열: ITST_ID, ITST_NM, MAP_CTPT_INT_LAT, MAP_CTPT_INT_LOT (UTF-8, BOM 가능)
  - 한 줄: "교차로ID<탭>이름<탭>위도<탭>경도". 서울 범위 밖 좌표(문서 샘플처럼 엉뚱한 값)와 중복 ID는 뺌. ID 숫자 순으로 정렬해 여러 번 돌려도 같은 결과.
"""
import csv
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "seoul_signals.tsv")
# 서울시와 그 둘레(경기 일부 교차로도 있을 수 있어 조금 넓게)
LAT = (37.40, 37.72)
LNG = (126.75, 127.20)


def rows_of(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def clean(rows):
    """[(id, 이름, 위도, 경도)] — 좌표가 없거나 서울 밖이거나 같은 ID가 또 나오면 뺌"""
    seen, out, dropped = set(), [], 0
    for r in rows:
        iid = (r.get("ITST_ID") or "").strip()
        try:
            lat, lng = float(r.get("MAP_CTPT_INT_LAT")), float(r.get("MAP_CTPT_INT_LOT"))
        except (TypeError, ValueError):
            dropped += 1
            continue
        if not iid or iid in seen or not (LAT[0] <= lat <= LAT[1] and LNG[0] <= lng <= LNG[1]):
            dropped += 1
            continue
        seen.add(iid)
        name = " ".join((r.get("ITST_NM") or "").replace("\t", " ").split()) or "교차로"
        out.append((iid, name, lat, lng))
    out.sort(key=lambda t: (int(t[0]) if t[0].isdigit() else 10 ** 12, t[0]))
    return out, dropped


def to_text(items) -> str:
    return "".join(f"{i}\t{n}\t{la:.6f}\t{ln:.6f}\n" for i, n, la, ln in items)


def main(argv) -> int:
    args = argv[1:]
    out = DEFAULT_OUT
    if "--out" in args:
        k = args.index("--out")
        out = args[k + 1]
        del args[k:k + 2]
    if not args:
        print(__doc__)
        return 2
    items, dropped = clean(rows_of(args[0]))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(to_text(items))
    print(f"서울 신호 교차로 {len(items):,}곳 (뺀 줄 {dropped}) → {os.path.getsize(out):,} bytes: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
