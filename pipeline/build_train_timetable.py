"""
한국철도공사 여객열차 운행정보(공공데이터포털 B551457/run/v2/travelerTrainRunInfo2)로 앱의 열차 시간표 파일을 다시 만든다.

  python pipeline/build_train_timetable.py            # app/src/main/assets/train_timetable.tsv 를 새로 씀
  python pipeline/build_train_timetable.py --out x.tsv

- 이 API 는 '지난 운행 실적'이라 어제까지만 있다. 최근 2주 안에서 평일(화~목)·토·일 하루씩 골라 받는다.
- 한 줄 = 열차 한 편:  번호 \t 종별(비워 둠) \t 운행요일(실적평일·실적토·실적일) \t 역@분;역@분;...
  분 = 0시부터의 분(자정을 넘으면 1440 이상). 시발역은 출발, 중간 역은 출발, 종착역은 도착 시각.
- 같은 번호에서 정차역이 같고 시각 차이가 5분 이내인 것은 한 줄로 합친다.
- 인증키: 환경변수 TAGO_SERVICE_KEY (없으면 저장소 루트의 local.properties).
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

URL = "https://apis.data.go.kr/B551457/run/v2/travelerTrainRunInfo2"
PAGE = 1000
TOLERANCE_MIN = 5
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "train_timetable.tsv")


def read_key():
    key = os.environ.get("TAGO_SERVICE_KEY", "").strip()
    if key:
        return key
    path = os.path.join(ROOT, "local.properties")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if line.startswith("TAGO_SERVICE_KEY="):
                return re.sub(r"\\(.)", r"\1", line.split("=", 1)[1].strip())
    return ""


def pick_days(today):
    """최근 2주 안에서 어제부터 거슬러 올라가며 평일(화~목)·토·일 하루씩. [(날짜, '평일'|'토'|'일')]"""
    want = {"평일": None, "토": None, "일": None}
    for back in range(1, 15):
        d = today - dt.timedelta(days=back)
        tag = {5: "토", 6: "일"}.get(d.weekday()) or ("평일" if d.weekday() in (1, 2, 3) else None)
        if tag and want[tag] is None:
            want[tag] = d
    return [(d, tag) for tag, d in want.items() if d]


def fetch_day(key, day, get=None):
    """하루치 전체 행 (쪽을 나눠 받음). 기록이 없으면 빈 목록. get 은 시험용으로 바꿔 끼울 수 있는 호출 함수"""
    get = get or _http_get
    rows, page = [], 1
    while True:
        q = {"serviceKey": key, "pageNo": page, "numOfRows": PAGE, "cond[run_ymd::EQ]": day.strftime("%Y%m%d")}
        body = get(URL + "?" + urllib.parse.urlencode(q, safe=""))
        resp = json.loads(body)["response"]["body"]
        items = resp.get("items")
        got = items.get("item", []) if isinstance(items, dict) else []
        rows += got
        if not got or len(rows) >= int(resp.get("totalCount") or 0):
            return rows
        page += 1


def _http_get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tagohub-pipeline"}), timeout=90) as r:
                return r.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            print("재시도:", str(e)[:80].replace(urllib.parse.unquote(url.split("serviceKey=")[-1].split("&")[0]), "<KEY>"), file=sys.stderr)
            time.sleep(1.5 * (attempt + 1))
    raise SystemExit("호출에 계속 실패했습니다.")


def minute(text):
    return int(text[11:13]) * 60 + int(text[14:16])


def train_stops(rows, day):
    """한 날의 행들 → {열차번호: [(역, 분), ...]} (정차 순서대로, 자정을 넘으면 1440 이상)"""
    by = defaultdict(list)
    for r in rows:
        by[r["trn_no"]].append(r)
    iso = day.isoformat()
    out = {}
    for no, rs in by.items():
        rs.sort(key=lambda r: int(r["trn_run_sn"]))
        stops, prev, shift = [], None, 0
        for r in rs:
            t = r.get("trn_dptre_dt") or r.get("trn_arvl_dt")
            if not t:
                continue
            m = minute(t) + (1440 if t[:10] != iso else 0)
            if prev is not None and m < prev - 600:
                shift += 1440
            m += shift
            stops.append((r["stn_nm"].strip(), m))
            prev = m
        if len(stops) >= 2:
            out[str(int(no))] = stops
    return out


def same(a, b, tol=TOLERANCE_MIN):
    return [n for n, _ in a] == [n for n, _ in b] and all(abs(x[1] - y[1]) <= tol for x, y in zip(a, b))


def build_lines(days):
    """[(날짜, 태그, 행들)] → 파일 줄들 (번호 순). 정차역이 같고 시각이 5분 안으로 같으면 한 줄"""
    kept = {}
    for day, tag, rows in days:
        for no, stops in train_stops(rows, day).items():
            cur = kept.setdefault(no, [])
            if any(same(stops, o[1]) for o in cur):
                continue
            cur.append(("실적" + tag, stops))
    lines = []
    for no in sorted(kept, key=lambda n: (int(n) if n.isdigit() else 10**9, n)):
        for remark, stops in kept[no]:
            lines.append("\t".join([no, "", remark, ";".join(f"{n}@{m}" for n, m in stops)]))
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args(argv)
    key = read_key()
    if not key:
        raise SystemExit("TAGO_SERVICE_KEY 가 없습니다 (환경변수 또는 local.properties).")
    days = []
    for day, tag in pick_days(dt.date.today()):
        rows = fetch_day(key, day)
        print(f"{day} ({tag}): {len(rows)}행, 열차 {len({r['trn_no'] for r in rows})}편")
        if rows:
            days.append((day, tag, rows))
    if not days:
        raise SystemExit("받은 기록이 없어 파일을 바꾸지 않았습니다.")
    lines = build_lines(days)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)}줄 ({len({l.split(chr(9))[0] for l in lines})}개 열차번호) → {args.out}")


if __name__ == "__main__":
    main()
