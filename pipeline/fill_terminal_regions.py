"""
고속·시외버스 터미널 중 시·도·위치가 빈 곳을 다시 찾아 채운다 → app/src/main/assets/coach_terminals.tsv (웹은 build_web_data.py 가 복사)

build_terminal_geo.py 는 "이름 + 터미널"로 카카오 장소를 찾아서, 터미널이 아닌 길가 정류소(호계동·용인신갈·영통·배방정류소 …)
97곳은 시·도와 위치가 비어 있었음 (2026-11-22). 웹·앱 터미널 목록에서 지역 칸이 비고, '지역별'에서 "기타"로 묶였음.
여기서는 "이름 정류소/정류장/버스정류장"까지 더 찾아 보고, 찾은 시·도가 같은 번호대(고속 NAEK 첫 자리, 시외 NAI 두 자리) 터미널들의
시·도와 맞을 때만 채운다 (같은 이름의 다른 지역 정류장을 잡지 않게). 못 찾은 곳은 그대로 비워 둠. 여러 번 돌려도 같은 결과

  python pipeline/fill_terminal_regions.py    # local.properties 의 KAKAO_REST_KEY 사용
"""
import collections
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TSV = ROOT / "app" / "src" / "main" / "assets" / "coach_terminals.tsv"
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_terminal_geo import prop, region_of  # noqa: E402


# 손으로 정한 곳 (카카오로 찾으면 엉뚱한 곳이 나오거나 못 찾음): 이름으로 시·도가 분명한 곳만, 위치는 비움.
# 부산시외는 경남 쪽 다른 장소가 잡혔음
SKIP = {"NAEK701"}
BY_HAND = {
    "NAEK701": "부산", "NAEK703": "부산", "NAEK740": "부산",            # 부산시외·부산사상·김해공항(부산 강서구)
    "NAEK737": "경남",                                                    # 장유인천(김해 장유)
    "NAEK161": "경기", "NAEK141": "경기", "NAEK144": "경기", "NAEK153": "경기", "NAEK155": "경기",  # 용인기흥역·여주프리·부밭·삼계리·초부리 (번호대 경기)
    "NAEK357": "충북", "NAEK371": "충남", "NAEK466": "충남",            # 오송단지·한국전통대(부여)·대천욕장(보령)
    "NAEK644": "전북", "NAEK893": "경북", "NAEK934": "경북",            # 전북강진(임실)·내남(경주)·석적(칠곡)
}
# 탈 수 없는 자리(요금 정산용 "통합단말", "…_수수료")는 목록에서 뺌
JUNK = re.compile(r"^통합단말$|_수수료$")


def band(tid: str) -> str:
    """같은 지역끼리 모인 번호대: 고속 NAEK + 첫 자리, 시외 NAI + 두 자리"""
    return tid[:5] if tid.startswith("NAEK") else tid[:5] if tid.startswith("NAI") else tid[:4]


def search(query: str, rest: str):
    url = "https://dapi.kakao.com/v2/local/search/keyword.json?" + urllib.parse.urlencode({"query": query, "size": 15})
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {rest}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8")).get("documents", [])


def find(name: str, allowed: set, rest: str):
    """이름이 맞고 시·도가 [allowed] 안인 장소 (교통 분류 먼저). 못 찾으면 None"""
    base = re.sub(r"\(.*?\)", "", name).replace("정류소", "").strip()
    hint = (re.search(r"\((.*?)\)", name) or [None, ""])[1]
    want = base.replace(" ", "")
    for q in (f"{base} 정류소", f"{base} 버스정류장", f"{base} 정류장", f"{hint} {base}".strip(), base):
        try:
            docs = search(q, rest)
        except Exception as e:  # 한도·연결 문제는 건너뜀
            print("  카카오 오류", q, e, file=sys.stderr)
            continue
        time.sleep(0.05)
        ok = []
        for d in docs:
            region = region_of(d.get("address_name", ""))
            if region in allowed and want[:2] in d["place_name"].replace(" ", ""):
                ok.append((d, region))
        ok.sort(key=lambda x: ("교통" not in x[0].get("category_name", ""), "정류" not in x[0]["place_name"] and "터미널" not in x[0]["place_name"]))
        if ok:
            d, region = ok[0]
            return float(d["y"]), float(d["x"]), region
    return None


def main():
    rest = prop("KAKAO_REST_KEY")
    rows = [l.split("\t") for l in TSV.read_text(encoding="utf-8").splitlines() if l.strip()]
    rows = [r + [""] * (5 - len(r)) for r in rows]
    rows = [r for r in rows if not JUNK.search(r[1].strip())]
    for r in rows:
        if r[0] in BY_HAND and not r[2]:
            r[2] = BY_HAND[r[0]]
    # 번호대마다 시·도 (그 번호대 터미널의 10% 넘는 시·도만 받아들임)
    by = collections.defaultdict(collections.Counter)
    for r in rows:
        if r[2]:
            by[band(r[0])][r[2]] += 1
    filled = 0
    for r in rows:
        if r[2]:
            continue
        c = by.get(band(r[0]), collections.Counter())
        total = sum(c.values())
        allowed = {k for k, v in c.items() if total and v / total > 0.1}
        if not allowed:
            continue
        if r[0] in SKIP:
            continue
        g = find(r[1], allowed, rest)
        if g:
            r[2], r[3], r[4] = g[2], f"{g[0]:.6f}", f"{g[1]:.6f}"
            filled += 1
            print(f"  {r[0]} {r[1]} → {g[2]} ({g[0]:.5f}, {g[1]:.5f})")
    TSV.write_text("\n".join("\t".join(r) for r in rows) + "\n", encoding="utf-8")
    left = sum(1 for r in rows if not r[2])
    print(f"{TSV.name}: {filled}곳 채움, 아직 빈 곳 {left}", flush=True)


if __name__ == "__main__":
    main()
