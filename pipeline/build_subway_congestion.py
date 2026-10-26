"""서울교통공사 지하철 혼잡도 정보(공공데이터 파일, CSV)를 앱에 넣을 파일로 바꾼다.

    python pipeline/build_subway_congestion.py  [CSV 파일]      # 기본: ~/Downloads/서울교통공사_지하철혼잡도정보_*.csv 중 마지막

만드는 파일: app/src/main/assets/subway_congestion.tsv
  요일(W 평일·S 토·U 일) \t 호선 \t 역명 \t 방향(상선·하선·내선·외선) \t 30분 단위 혼잡도(%) 39개를 쉼표로 (5:30 ~ 00:30)
혼잡도 = 열차 정원 대비 승객 비율(%) — 100% 면 정원만큼 탄 것.
"""
import csv
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/subway_congestion.tsv"
DAY = {"평일": "W", "토요일": "S", "일요일": "U"}


def main():
    if len(sys.argv) > 1:
        src = Path(sys.argv[1])
    else:
        src = sorted((Path.home() / "Downloads").glob("서울교통공사_지하철혼잡도정보_*.csv"))[-1]
    raw = src.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    rows = list(csv.reader(io.StringIO(text)))[1:]
    out = []
    for r in rows:
        if len(r) < 6 or r[0] not in DAY:
            continue
        vals = []
        for v in r[5:44]:
            v = v.strip()
            vals.append(str(int(round(float(v)))) if v else "")
        out.append("\t".join([DAY[r[0]], r[1].strip(), r[3].strip(), r[4].strip(), ",".join(vals)]))
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
    print("rows", len(out), "->", OUT)


if __name__ == "__main__":
    main()
