"""고속·시외버스 터미널의 시·도와 위치를 모아 app/src/main/assets/coach_terminals.tsv 로 만든다.

TAGO 의 터미널 목록(고속 ExpBusInfo, 시외 SuburbsBusInfo)에는 위치가 없고, 고속은 시·도도 없다.
그래서 카카오 장소 검색(REST 키)으로 "이름 + 터미널"을 찾아 좌표와 시·도를 채운다. 터미널 선택 화면의
'거리순'·'지역별'에 쓰인다. 한 번 만들어 앱에 넣어 두는 파일이라 앱이 켜질 때 서버를 부르지 않는다.

  python pipeline/build_terminal_geo.py            # local.properties 의 TAGO_SERVICE_KEY, KAKAO_REST_KEY 사용

출력 형식(탭 구분, 한 줄에 한 터미널): ID  이름  시·도  위도  경도   (위치를 못 찾으면 위도·경도 비움)
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "coach_terminals.tsv"

REGION_SHORT = {
    "서울": "서울", "부산": "부산", "대구": "대구", "인천": "인천", "광주": "광주", "대전": "대전", "울산": "울산", "세종": "세종",
    "경기": "경기", "강원": "강원", "충북": "충북", "충남": "충남", "전북": "전북", "전남": "전남", "경북": "경북", "경남": "경남", "제주": "제주",
}


def prop(name: str) -> str:
    text = (ROOT / "local.properties").read_text(encoding="utf-8")
    raw = re.search(r"^" + name + r"=(.*)$", text, re.M).group(1).strip()
    return re.sub(r"\\(.)", r"\1", raw)  # 자바 properties 의 역슬래시 이스케이프 풀기


def get_json(url: str, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def terminals(key: str):
    q = urllib.parse.quote(key, safe="")
    out = []
    for path, kind in (("ExpBusInfo/GetExpBusTrminlList", "고속"), ("SuburbsBusInfo/GetSuberbsBusTrminlList", "시외")):
        got = []
        for page in range(1, 6):
            url = f"https://apis.data.go.kr/1613000/{path}?serviceKey={q}&_type=json&numOfRows=1000&pageNo={page}"
            body = get_json(url)["response"]["body"]
            raw = (body.get("items") or {}).get("item") or []
            raw = [raw] if isinstance(raw, dict) else raw
            got += raw
            if not raw or len(got) >= int(body.get("totalCount", 0)):
                break
        out += [(t["terminalId"], t["terminalNm"].strip(), (t.get("cityName") or "").strip(), kind) for t in got if t.get("terminalId")]
    return out


def region_of(address: str) -> str:
    first = address.split()[0] if address else ""
    for k, v in REGION_SHORT.items():
        if first.startswith(k):
            return v
    return ""


def geocode(name: str, kind: str, rest: str):
    """이름으로 터미널 찾기. 못 찾으면 None"""
    base = re.sub(r"\(.*?\)", "", name).strip()
    queries = [f"{base} {'고속버스터미널' if kind == '고속' else '버스터미널'}", f"{base} 터미널", base]
    for query in queries:
        url = "https://dapi.kakao.com/v2/local/search/keyword.json?" + urllib.parse.urlencode({"query": query, "size": 5})
        try:
            docs = get_json(url, {"Authorization": f"KakaoAK {rest}"}).get("documents", [])
        except urllib.error.HTTPError as e:
            print("  카카오 오류", e.code, query, file=sys.stderr)
            continue
        time.sleep(0.05)
        # 이름이 맞는 것만 (엉뚱한 지역의 같은 이름을 잡지 않게). 터미널 분류를 먼저
        want = base.replace(" ", "")
        ok = [d for d in docs
              if 33.0 <= float(d["y"]) <= 39.5 and 124.0 <= float(d["x"]) <= 132.0
              and want[:3] in d["place_name"].replace(" ", "")]
        ok.sort(key=lambda d: ("버스터미널" not in d.get("category_name", ""), "터미널" not in d.get("category_name", "")))
        if ok:
            d = ok[0]
            return float(d["y"]), float(d["x"]), region_of(d.get("address_name", ""))
    return None


def main():
    tago, rest = prop("TAGO_SERVICE_KEY"), prop("KAKAO_REST_KEY")
    rows = terminals(tago)
    print(len(rows), "개 터미널")
    cache = {}
    lines = []
    miss = 0
    for i, (tid, name, city, kind) in enumerate(rows):
        ck = (name, kind)
        if ck not in cache:
            cache[ck] = geocode(name, kind, rest)
        g = cache[ck]
        region = region_of(city) or (g[2] if g else "")
        if g is None:
            miss += 1
        lines.append("\t".join([tid, name, region, f"{g[0]:.6f}" if g else "", f"{g[1]:.6f}" if g else ""]))
        if i % 100 == 0:
            print(i, "/", len(rows))
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("저장", OUT, "위치 못 찾음", miss)


if __name__ == "__main__":
    main()
