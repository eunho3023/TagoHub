"""행정안전부 재난안전데이터 공유플랫폼의 '교통안전정보' CSV 에서 안내할 위험 지점을 뽑아 앱의 안전 지점 목록(assets/safety_zones.csv)에 합친다.

    python pipeline/build_safetydata_zones.py  [CSV 가 있는 폴더]        # 기본: ~/Downloads

읽는 파일 (폴더 안에서 이름에 들어 있는 말로 찾음) → 종류코드
  교통안전정보(위험요소)-도로환경별_급커브(굽은도로).csv → curve   급커브
  교통안전정보(위험요소)-도로환경별_낙석도로.csv          → rock    낙석
  교통안전정보(위험요소)-도로환경별_기상-안개.csv         → fog     안개
  교통안전정보(위험요소)-도로환경별_추락.csv              → fall    추락
  교통안전정보(위험요소)-도로환경별_철길건널목.csv        → rail    철길 건널목
  교통안전정보(노인보호구역).csv                           → senior  노인 보호구역
  교통안전정보(장애인보호구역).csv                         → disabled 장애인 보호구역

주의: 포털 화면에서 내려받은 CSV 는 한 파일에 100건까지만 담긴다 (전국 전체가 아님).
전체를 쓰려면 같은 이름 규칙의 전체 파일(또는 API 로 받은 것)을 넣고 다시 돌린다. 여러 번 돌려도 같은 결과.
"""
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/safety_zones.csv"
SOURCES = [
    ("급커브(굽은도로)", "curve", "급커브 구간"),
    ("낙석도로", "rock", "낙석 위험 구간"),
    ("기상-안개", "fog", "안개 주의 구간"),
    ("추락", "fall", "추락 위험 구간"),
    ("철길건널목", "rail", "철길 건널목"),
    ("(노인보호구역)", "senior", "노인 보호구역"),
    ("(장애인보호구역)", "disabled", "장애인 보호구역"),
]
OWN = {code for _, code, _ in SOURCES}


def clean(s: str) -> str:
    return " ".join(s.replace(",", " ").split())


def rows_of(path: Path, code: str, label: str):
    for r in csv.DictReader(open(path, encoding="utf-8-sig")):
        try:
            lat, lng = float(r["LAT"]), float(r["LOT"])
        except (KeyError, ValueError):
            continue   # 두 번째 줄(한글 항목 설명) 등
        if not (33.0 <= lat <= 39.5 and 124.0 <= lng <= 132.0):
            continue
        name = clean(r.get("OBFA_NM") or r.get("DTL_CN") or "") or label
        yield code, lat, lng, name[:60]


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    kept = [l for l in OUT.read_text(encoding="utf-8").splitlines() if l.split(",", 1)[0] not in OWN]
    seen, rows = set(), []
    for key, code, label in SOURCES:
        files = [p for p in folder.glob("교통안전정보*.csv") if key in p.name]
        for p in files:
            for c, lat, lng, name in rows_of(p, code, label):
                k = (c, round(lat, 4), round(lng, 4))
                if k in seen:
                    continue
                seen.add(k)
                rows.append(f"{c},{lat:.5f},{lng:.5f},,{name}")
        print(label, len(files), "개 파일")
    OUT.write_text("\n".join(kept + rows) + "\n", encoding="utf-8")
    from collections import Counter
    print("새로", dict(Counter(r.split(",", 1)[0] for r in rows)))


if __name__ == "__main__":
    main()
