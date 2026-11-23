"""한국공항공사_항공기 운항 스케줄 정보_GW (공공데이터포털 15158949) API 로 항공 시간표를 새로 만든다.

    py pipeline/build_air_schedule_api.py            # 키: 환경변수 TAGO_SERVICE_KEY 또는 local.properties

예전엔 공공데이터포털 파일데이터(CSV)를 손으로 내려받아 build_air_data.py 로 만들었는데, 여름 시간표(~2026-10-24)에서 멈춰
겨울 시간표가 없었음 (2026-11-23). API 는 정기운항 시작일·종료일·요일을 주므로 언제든 다시 만들 수 있음.

- 쓰는 기능: 면세점용 국내선(/taxfree-dom)·국제선(/taxfree-int) 스케줄. 지금 시즌(2026-10-27~2027-03-27)이 들어 있음.
  (/dom·/int 는 2009~2025년 지난 기록이 대부분이라 지금 운항이 거의 없음)
- 출발 시각(st)만 있음 → 도착 시각은 지난 파일(air_schedule.tsv)의 같은 편명 → 같은 구간의 비행시간(현지 시각 차이)으로 채움.
  둘 다 모르면 그 편은 뺌. 인천 국제선은 한국공항공사 관할이 아니라 예전 파일에도 없음
- 오늘 이전에 끝난 운항, 삭제(fgenType D) 항목은 뺌

만드는 파일(app/src/main/assets, 형식은 build_air_data.py 와 같음):
  air_schedule.tsv   종류(D/I) 	 항공사 	 편명 	 출발 	 도착 	 출발시각 	 도착시각 	 운항요일 	 시작일 	 종료일
  air_airlines.tsv   항공사 	 이름   (예전 ICAO 코드는 그대로, 새 항공사는 IATA 코드)
  air_airports.tsv   공항 코드 	 이름 	 나라 (예전 순서 그대로, 없던 공항만 뒤에 더함)
"""
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets"
BASE = "https://apis.data.go.kr/B551178/flight-schedule/"
PAGE = 100   # 이 API 는 한 쪽 100개까지 (200 넘으면 HTTP_ERROR)
DAYS = "월화수목금토일"


def read_key():
    key = os.environ.get("TAGO_SERVICE_KEY", "").strip()
    if not key:
        p = ROOT / "local.properties"
        if p.exists():
            for line in p.read_text(encoding="utf-8").splitlines():
                if line.startswith("TAGO_SERVICE_KEY="):
                    key = re.sub(r"\\(.)", r"\1", line.split("=", 1)[1].strip())
                    break
    return urllib.parse.unquote(key)


def fetch_all(op, key, get=None):
    """한 기능의 모든 항목 (쪽마다 100개, 국내선·국제선 합쳐 240번 남짓 = 하루 한도 5,000 안)"""
    get = get or (lambda url: urllib.request.urlopen(url, timeout=20).read().decode("utf-8"))

    def one(page):
        q = {"serviceKey": key, "pageNo": page, "numOfRows": PAGE, "type": "json"}
        for attempt in range(4):
            try:
                body = json.loads(get(BASE + op + "?" + urllib.parse.urlencode(q)))["response"]
                break
            except Exception as e:  # 잠깐 바쁨·연결 오류는 다시
                if attempt == 3:
                    raise RuntimeError(f"{op} {page}쪽을 받지 못함: {e}")
                time.sleep(2 * (attempt + 1))
        if str(body["header"]["resultCode"]) != "00":
            raise RuntimeError(f"{op}: {body['header']}")
        items = (body["body"].get("items") or {}).get("item") or []
        return int(body["body"]["totalCount"]), items if isinstance(items, list) else [items]

    # 한 쪽에 1초 남짓이라 하나씩이면 30분 넘게 걸림 → 첫 쪽으로 전체 수를 알고 나머지는 6개씩 같이
    total, out = one(1)
    pages = range(2, (total + PAGE - 1) // PAGE + 1)
    with ThreadPoolExecutor(6) as ex:
        for i, (_, items) in enumerate(ex.map(one, pages), 2):
            out += items
            if i % 100 == 0:
                print(f"  {op} {i}/{pages.stop - 1}쪽", flush=True)
    return out


def hhmm(v):
    v = re.sub(r"\D", "", str(v or ""))
    if len(v) == 3:
        v = "0" + v
    if len(v) != 4 or int(v[:2]) > 23 or int(v[2:]) > 59:
        return None
    return f"{v[:2]}:{v[2:]}"


def ymd(v):
    m = re.match(r"(\d{4})-?(\d\d)-?(\d\d)", str(v or ""))
    return f"{m[1]}-{m[2]}-{m[3]}" if m else None


def days(r):
    return "".join(c for c, k in zip(DAYS, ("mon", "tue", "wed", "thu", "fri", "sat", "sun")) if str(r.get("yn" + k, "")).upper() == "Y")


def minutes(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


def clock(n):
    n %= 1440
    return f"{n // 60:02d}:{n % 60:02d}"


def old_durations(text):
    """지난 파일의 비행시간(분, 현지 시각 차이): {편명: 분}, {(출발, 도착): 분}"""
    by_flight, by_pair = defaultdict(Counter), defaultdict(Counter)
    for line in (text or "").splitlines():
        f = line.split("\t")
        if len(f) < 10 or not re.fullmatch(r"\d\d:\d\d", f[5]) or not re.fullmatch(r"\d\d:\d\d", f[6]):
            continue
        d = (minutes(f[6]) - minutes(f[5])) % 1440
        by_flight[f[2]][d] += 1
        by_pair[(f[3], f[4])][d] += 1
    pick = lambda c: c.most_common(1)[0][0]
    return {k: pick(v) for k, v in by_flight.items()}, {k: pick(v) for k, v in by_pair.items()}


def build(dom, intl, old_schedule="", old_airlines="", old_airports="", today=None):
    """API 항목 → (시간표 줄, 항공사 줄, 공항 줄, 통계)"""
    today = (today or dt.date.today()).isoformat()
    by_name = {}
    for line in old_airlines.splitlines():
        f = line.split("\t")
        if len(f) >= 2 and f[0].strip():
            by_name.setdefault(f[1].strip(), f[0].strip())
    names = {f.split("\t")[0]: f.split("\t")[1] for f in old_airlines.splitlines() if "\t" in f}
    airports = {}
    for line in old_airports.splitlines():
        f = line.split("\t")
        if f and f[0].strip():
            airports[f[0].strip()] = line
    fl_dur, pair_dur = old_durations(old_schedule)

    def airline(name, flight):
        """예전 파일에 있는 항공사면 그 코드(ICAO), 아니면 편명 앞 두 글자(IATA)"""
        name = (name or "").strip()
        code = by_name.get(name)
        if not code:
            m = re.match(r"[A-Z0-9]{2}", flight or "")
            code = m[0] if m else name
        if code not in names and name:
            names[code] = name
        return code

    def airport(code, name, country):
        if code and code not in airports and name:
            airports[code] = f"{code}\t{name.strip()}\t{country}"

    korea = {line.split("	")[0] for line in old_airports.splitlines() if line.endswith("	한국")}
    lines, stat = set(), Counter()
    for kind, items in (("D", dom), ("I", intl)):
        for r in items:
            if str(r.get("fgenType", "")).upper() == "D":
                continue
            start, end = ymd(r.get("firstdate")), ymd(r.get("lastdate"))
            dep = hhmm(r.get("st"))
            flight = (r.get("flightid") or "").strip()
            a, b = (r.get("depcitycode") or "").strip(), (r.get("arrvcitycode") or "").strip()
            dd = days(r)
            if not (start and end and dep and flight and a and b and dd) or end < today:
                continue
            dur = fl_dur.get(flight) or pair_dur.get((a, b))
            if not dur:
                stat[kind + "_no_duration"] += 1
                continue
            name = (r.get("depcity") or "").split("/")[-1] if kind == "D" else r.get("depcity")
            airport(a, name, "한국" if kind == "D" or a in korea else "")
            name = (r.get("arrvcity") or "").split("/")[-1] if kind == "D" else r.get("arrvcity")
            airport(b, name, "한국" if kind == "D" or b in korea else "")
            code = (r.get("airlineCode") or r.get("airlinecode") or "").strip()
            lines.add("	".join([kind, airline(r.get("airline"), code or flight), flight, a, b, dep, clock(minutes(dep) + dur), dd, start, end]))
            stat[kind] += 1
    lines = sorted(lines, key=lambda l: (l.split("\t")[0], l.split("\t")[2], l.split("\t")[8]))
    used = {l.split("\t")[1] for l in lines}
    al = [f"{k}\t{names.get(k, k)}" for k in sorted(used)]
    ap = list(airports.values())   # 예전 순서 그대로(한국 공항 먼저), 새 공항은 뒤에
    return lines, al, ap, stat


def main():
    key = read_key()
    if not key:
        sys.exit("인증키가 없습니다 (TAGO_SERVICE_KEY)")
    dom, intl = fetch_all("taxfree-dom", key), fetch_all("taxfree-int", key)
    print(f"API: 국내선 {len(dom)}개, 국제선 {len(intl)}개", flush=True)
    rd = lambda n: (OUT / n).read_text(encoding="utf-8") if (OUT / n).exists() else ""
    lines, al, ap, stat = build(dom, intl, rd("air_schedule.tsv"), rd("air_airlines.tsv"), rd("air_airports.tsv"))
    if stat["D"] < 1000:
        sys.exit(f"국내선이 너무 적어 파일을 바꾸지 않음 ({stat['D']}개)")
    (OUT / "air_schedule.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    (OUT / "air_airlines.tsv").write_text("\n".join(al) + "\n", encoding="utf-8", newline="\n")
    (OUT / "air_airports.tsv").write_text("\n".join(ap) + "\n", encoding="utf-8", newline="\n")
    ends = Counter(l.split("\t")[9] for l in lines).most_common(3)
    print(f"시간표 {len(lines)}줄 (국내선 {stat['D']}, 국제선 {stat['I']}, 비행시간 몰라 뺀 편 국내 {stat['D_no_duration']}·국제 {stat['I_no_duration']}), 항공사 {len(al)}, 공항 {len(ap)}, 많이 끝나는 날 {ends}")


if __name__ == "__main__":
    main()
