"""공공데이터포털 표준데이터(단속카메라·사고다발지역)를 앱의 안전 지점 목록(assets/safety_zones.csv)에 합친다.

    python pipeline/fetch_datago_std.py 15028200 pipeline/out/camera.csv     # 전국무인교통단속카메라표준데이터
    python pipeline/fetch_datago_std.py 15029185 pipeline/out/accident.csv   # 전국교통사고다발지역표준데이터 (보행노인·보행어린이·스쿨존어린이·자전거)
    python pipeline/merge_datago_zones.py [카메라 CSV] [사고다발 CSV]

과속방지턱(bump)·어린이 보호구역(child) 줄은 그대로 두고, 카메라·사고다발 줄만 새로 만든다 (여러 번 돌려도 같은 결과).
종류코드: speed 과속 / signal 신호위반 / section 구간단속 / park 주정차 / cam 그 밖 / acc 사고다발

단속구분 코드는 제공 기관마다 '1'과 '01'처럼 달리 적혀 있다. 1·3 과속(·신호 겸용), 2 신호위반, 4 주정차, 99 그 밖(이름에 '구간'이 있으면 구간단속).
단속구간위치구분이 1(시점)·2(종점)이면 구간단속의 양 끝 지점이다.
"""
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/safety_zones.csv"
KEEP = {"bump", "child"}


def clean(s: str) -> str:
    return " ".join(s.replace(",", " ").replace("\n", " ").split())


def code_of(row) -> str:
    sct = row["단속구간위치구분"].strip().lstrip("0")
    se = row["단속구분"].strip()
    name = row["설치장소"] + row["도로노선명"]
    if sct in ("1", "2"):
        return "section"
    first = se.split("+")[0].lstrip("0")
    if first in ("1", "3") or "+" in se:
        return "speed"
    if first == "2":
        return "signal"
    if first == "4":
        return "park"
    if "구간" in name:
        return "section"
    return "cam"


def cameras(path: Path):
    out = []
    for r in csv.DictReader(open(path, encoding="utf-8-sig")):
        try:
            lat, lng = float(r["위도"]), float(r["경도"])
        except ValueError:
            continue
        if not (33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0):
            continue
        code = code_of(r)
        try:
            lim = int(float(r["제한속도"] or 0))
        except ValueError:
            lim = 0
        # 제한속도는 과속·구간 단속에서만 의미가 있다 (신호·주정차 줄에 적힌 값은 믿지 않음)
        limit = lim if code in ("speed", "section") and 10 <= lim <= 130 else ""
        out.append((code, lat, lng, limit, clean(r["설치장소"] or r["도로노선명"] or r["소재지지번주소"])))
    return out


def accidents(path: Path):
    out = []
    for r in csv.DictReader(open(path, encoding="utf-8-sig")):
        try:
            lat, lng = float(r["위도"]), float(r["경도"])
        except ValueError:
            continue
        if not (33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0):
            continue
        kind = r["사고유형구분"].strip()
        out.append(("acc", lat, lng, "", clean(f"{kind} 사고다발 지역")))
    return out


def main():
    cam = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "pipeline/out/camera.csv"
    acc = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "pipeline/out/accident.csv"
    kept = [l for l in OUT.read_text(encoding="utf-8").splitlines() if l.split(",", 1)[0] in KEEP]
    seen, rows = set(), []
    for code, lat, lng, limit, name in cameras(cam) + accidents(acc):
        key = (code, round(lat, 5), round(lng, 5))
        if key in seen:
            continue
        seen.add(key)
        rows.append(f"{code},{lat:.5f},{lng:.5f},{limit},{name}")
    OUT.write_text("\n".join(kept + rows) + "\n", encoding="utf-8")
    from collections import Counter
    print("유지", len(kept), "줄 + 새로", Counter(r.split(",", 1)[0] for r in rows))


if __name__ == "__main__":
    main()
