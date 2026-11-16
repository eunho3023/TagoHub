"""서울시 교통정보(TOPIS) 도로 링크 지도 자료 만들기 (실시간 도로 소통·돌발을 지도에 그리고 길안내에 쓰려고).

    py pipeline/build_seoul_links.py

필요: local.properties 의 SEOUL_SUBWAY_KEY (서울 열린데이터광장 인증키. 키는 출력·저장하지 않음)
받는 것 (서울 열린데이터광장 Open API):
  RoadDivInfo(도로 분류) → RoadInfo(도로 축, 분류별) → LinkWithLoad(도로별 링크, 축별: 방향·순번)
  → LinkInfo(링크 도로명·시작/끝 노드·거리) · LinkVerInfo(링크 꼭짓점, GRS80 TM → 위경도 seoul_tm.py)
만드는 파일 (같은 내용 두 곳):
  server/tago-proxy/site/seoul/links.json  (웹)
  app/src/main/assets/seoul_links.json     (앱)
형식: {"built": 초, "links": {링크 id: [도로명, 시작 노드, 끝 노드, 거리(m), 도로 분류(02~05), 폴리라인(정밀도 5)]}}
TOPIS 링크는 한 방향이라 같은 길의 상·하행이 따로 있음.
"""
import json
import pathlib
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from seoul_tm import tm_to_wgs84  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTS = [ROOT / "server/tago-proxy/site/seoul/links.json", ROOT / "app/src/main/assets/seoul_links.json"]


def key() -> str:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("SEOUL_SUBWAY_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("local.properties 에 SEOUL_SUBWAY_KEY 가 없습니다")


K = key()


def call(svc: str, a: int, b: int, *rest: str) -> str:
    path = "/".join([urllib.parse.quote(K, safe=""), "xml", svc, str(a), str(b)] + [urllib.parse.quote(str(x), safe="") for x in rest])
    for attempt in range(4):
        try:
            return urllib.request.urlopen("http://openapi.seoul.go.kr:8088/" + path, timeout=30).read().decode("utf-8", "replace")
        except Exception:  # 잠깐 끊김: 조금 쉬고 다시
            time.sleep(1 + attempt * 2)
    return ""


def rows(x: str) -> list:
    """<row> 마다 {태그: 값}"""
    return [dict(re.findall(r"<(\w+)>([^<]*)</\1>", r)) for r in re.findall(r"<row>(.*?)</row>", x, re.S)]


def encode(points) -> str:
    out, pl, pg = [], 0, 0
    for la, lo in points:
        a, b = round(la * 1e5), round(lo * 1e5)
        for v in (a - pl, b - pg):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        pl, pg = a, b
    return "".join(out)


def main():
    t0 = time.time()
    divs = [r["road_div_cd"] for r in rows(call("RoadDivInfo", 1, 100))]
    axes = []
    for d in divs:
        axes += [(r["axis_cd"], d) for r in rows(call("RoadInfo", 1, 1000, d))]
    print(f"도로 분류 {len(divs)}개, 도로 축 {len(axes)}개", flush=True)
    link_div = {}
    with ThreadPoolExecutor(8) as ex:
        for (axis, d), x in zip(axes, ex.map(lambda a: call("LinkWithLoad", 1, 1000, a[0]), axes)):
            for r in rows(x):
                if r.get("link_id"):
                    link_div.setdefault(r["link_id"], d)
    ids = sorted(link_div)
    print(f"링크 {len(ids)}개 ({time.time() - t0:.0f}초)", flush=True)

    def one(lid):
        info = rows(call("LinkInfo", 1, 5, lid))
        ver = rows(call("LinkVerInfo", 1, 1000, lid))
        ver.sort(key=lambda r: int(r.get("ver_seq") or 0))
        pts = []
        for r in ver:
            try:
                la, lo = tm_to_wgs84(float(r["grs80tm_x"]), float(r["grs80tm_y"]))
            except (KeyError, ValueError):
                continue
            if 37.0 < la < 38.0 and 126.5 < lo < 127.5 and (not pts or (round(la, 5), round(lo, 5)) != pts[-1]):
                pts.append((round(la, 5), round(lo, 5)))
        i = info[0] if info else {}
        return lid, i, pts

    links, n = {}, 0
    with ThreadPoolExecutor(8) as ex:
        for lid, i, pts in ex.map(one, ids):
            n += 1
            if n % 500 == 0:
                print(f"  {n}/{len(ids)} ({time.time() - t0:.0f}초)", flush=True)
            if len(pts) < 2:
                continue
            dist = int(float(i.get("map_dist") or 0))
            links[lid] = [i.get("road_name") or "", i.get("st_node_nm") or "", i.get("ed_node_nm") or "", dist, link_div[lid], encode(pts)]
    doc = {"built": int(time.time()), "links": links}
    text = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))
    for p in OUTS:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    print(f"서울 링크 {len(links)}개 / {len(ids)}개 → {len(text) // 1024}KB ({time.time() - t0:.0f}초)", flush=True)


if __name__ == "__main__":
    main()
