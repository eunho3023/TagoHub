"""배포할 때 받는 지자체 공식 배차시간표 (서버(클라우드)에서는 접속이 막힌 지역용).

천안시 버스정보시스템(its.cheonan.go.kr)은 클라우드 서버에서 오는 요청을 받지 않아,
deploy.ps1 을 실행하는 컴퓨터에서 받아 웹 자료 조각(public/data/times/cheonan.json)으로 같이 올림.
형식: {노선 9자리: {"weekday": [분...], "sat": [...], "holi": [...], "source": "..."}}
실패해도 배포는 계속 (그 지역은 첫차·막차 예상 시각으로 보임).
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (compatible; TagoHub/1.0)", "Accept-Language": "ko-KR,ko;q=0.9"}
CHEONAN = "https://its.cheonan.go.kr"


def _req(url, data=None, timeout=20):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def clock_list(items):
    out = set()
    for t in items:
        m = re.match(r"\s*(\d{1,2}):?(\d\d)\b", str(t or ""))
        if m and int(m.group(1)) < 30 and int(m.group(2)) < 60:
            out.add(int(m.group(1)) * 60 + int(m.group(2)))
    return sorted(out)


def parse_cheonan_html(html):
    """천안 시간표 창: <dt>평일 출발시간</dt> 다음 <dd>시각</dd> 들 (주말·공휴일 칸도 같은 모양)"""
    def part(label):
        i = html.find(f"<dt>{label} 출발시간</dt>")
        if i < 0:
            return []
        rest = html[i + 10:]
        end = rest.find("<dt>")
        return clock_list(re.findall(r"<dd>\s*(\d{1,2}:\d\d)", rest if end < 0 else rest[:end]))
    weekday = part("평일")
    if not weekday:
        return None
    sat, holi = part("주말"), part("공휴일")
    return {"weekday": weekday, "sat": sat or weekday, "holi": holi or sat or weekday, "source": "천안시 버스정보시스템"}


def cheonan_route_ids():
    ids, page = [], 1
    while page < 60:
        j = json.loads(_req(CHEONAN + "/bis/getBusTimeTable.do", {"thisPage": page, "searchKeyword": ""}))
        rows = j.get("resultList") or []
        if not rows:
            break
        ids += [str(r["ROUTE_ID"]) for r in rows if r.get("ROUTE_ID")]
        if len(ids) >= int(rows[0].get("TABLE_CNT") or 0):
            break
        page += 1
    return list(dict.fromkeys(ids))


def cheonan_one(rid):
    rows = json.loads(_req(CHEONAN + "/bis/getRouteList.do", {"searchKeyword": rid})).get("resultList") or []
    it = next((x for x in rows if str(x.get("ROUTE_ID")) == rid), None)
    if not it:
        return None
    q = urllib.parse.urlencode({"routeName": it.get("ROUTE_NAME", ""), "routeDirection": it.get("ROUTE_DIRECTION", ""), "relayAreacode": it.get("RELAY_AREACODE", ""),
                                "routeExplain": "", "stName": "", "edName": "", "fstTime": it.get("FST_TIME") or "", "lstTime": it.get("LST_TIME") or "",
                                "routeDLength": it.get("ROUTE_DLENGTH") or "", "avgTime": it.get("AVG_TIME") or ""})
    return parse_cheonan_html(_req(CHEONAN + "/bis/showBusTimeTable.do?" + q))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="public/data 폴더")
    a = ap.parse_args()
    import os
    os.makedirs(os.path.join(a.out, "times"), exist_ok=True)
    out = {}
    try:
        ids = cheonan_route_ids()
        for rid in ids:
            try:
                t = cheonan_one(rid)
                if t:
                    out[rid] = t
            except Exception:
                pass
            time.sleep(0.1)
        print(f"  천안 배차시간표 {len(out)}/{len(ids)}개 노선")
    except Exception as e:
        print(f"  천안 배차시간표 받기 실패 (건너뜀): {e}")
    if out:
        with open(os.path.join(a.out, "times", "cheonan.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
