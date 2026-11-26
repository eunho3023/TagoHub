"""배포할 때 만드는 공식 배차시간표 자료 조각 (public/data/times/<지역>.json).

지금은 강릉만: 공공데이터포털 배차정보 파일(이용허락범위 제한 없음, pipeline/static_routes/gangneung/deps.csv).
천안·구미·안동·거제·제천·속초 버스정보 사이트에서 받던 시간표는 뺐음 (2026-11-27 사용자 결정).
그 사이트들은 공공누리 표시 없이 "All rights reserved" 라 옮겨 보여 줄 허락이 없어서다.
웹은 그 도시 정류장·노선에 원본 사이트 링크만 보여 준다(site/js/busdata.js OUTSIDE_TIMES).
형식: {노선 9자리: {"weekday": [분...], "sat": [...], "holi": [...], "source": "..."}}
실패해도 배포는 계속 (그 지역은 첫차·막차 예상 시각으로 보임).
"""
import argparse
import json
import pathlib
import re
import sys


def clock_list(items):
    out = set()
    for t in items:
        m = re.match(r"\s*(\d{1,2}):?(\d\d)\b", str(t or ""))
        if m and int(m.group(1)) < 30 and int(m.group(2)) < 60:
            out.add(int(m.group(1)) * 60 + int(m.group(2)))
    return sorted(out)


def gangneung_all():
    """강릉: 공공데이터포털 배차정보 파일(pipeline/static_routes/gangneung/deps.csv)에서. 받을 것 없음"""
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    import build_data
    got = {rid: {**d, "source": "강릉시 배차정보 (공공데이터포털)"} for rid, d in build_data.gangneung_deps().items()}
    return got, len(got)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="public/data 폴더")
    ap.add_argument("--max-age-days", type=float, default=7, help="(예전 옵션, 지금은 쓰지 않음)")
    a = ap.parse_args()
    dest = pathlib.Path(a.out) / "times"
    dest.mkdir(parents=True, exist_ok=True)
    for area, name, fetch_all in (("gangneung", "강릉", gangneung_all),):
        try:
            got, n = fetch_all()
            print(f"  {name} 배차시간표 {len(got)}/{n}개 노선")
            if got:
                (dest / f"{area}.json").write_text(json.dumps(got, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        except Exception as e:
            print(f"  {name} 배차시간표 만들기 실패 (건너뜀): {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
