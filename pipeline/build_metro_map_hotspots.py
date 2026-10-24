"""서울교통공사 노선도 그림에서 역 이름 글자를 찾아, 그림 속 역 위치(눌러서 역을 열 자리)를 만든다.

    python pipeline/build_metro_map_hotspots.py  [노선도.pdf]  [--ocr-json 이미 만든 OCR 결과.json]

1) PDF 를 큰 그림(PNG)으로 그림: 노선도는 글자가 선(그림)으로 바뀌어 있어 글자를 직접 뽑을 수 없음.
2) OCR 로 한글 글자 위치를 읽음 (RapidOCR 한국어 모델. 이 단계에만 필요: `pip install rapidocr onnxruntime pymupdf pillow`).
   파이썬 3.12 정도가 필요하고, OCR 결과는 --ocr-json 으로 저장·재사용할 수 있음.
3) 읽은 글자를 앱의 수도권 역 이름과 맞춰 app/src/main/assets/seoul_metro_map_hotspots.tsv 로 저장
   (역 이름 \t x \t y, 그림 크기에 대한 0~1 비율). 앱은 이 자리를 누르면 그 역을 연다.
   여러 줄로 나뉜 이름("디지털" + "미디어시티")은 가까운 글자끼리 이어서 맞춤.

이 스크립트가 만든 그림은 app/src/main/assets/seoul_metro_map.jpg (앱이 보여 주는 노선도).
"""
import json
import re
import sys
from difflib import SequenceMatcher
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
SCALE = 3.0
TILE, OVERLAP = 1800, 250


def norm(s: str) -> str:
    """앱의 StationName.norm 과 같은 방식: 괄호·공백 제거, 세 글자 이상이고 '역'으로 끝나면 '역'을 뗌"""
    t = re.sub(r"\(.*?\)", "", s).replace(" ", "").strip()
    return t[:-1] if len(t) >= 3 and t.endswith("역") else t


def hangul_only(s: str) -> str:
    return "".join(ch for ch in s if "가" <= ch <= "힣" or ch.isdigit())


def render_and_ocr(pdf_path: Path):
    import numpy as np
    import pymupdf
    from PIL import Image
    from rapidocr import LangRec, ModelType, OCRVersion, RapidOCR

    doc = pymupdf.open(pdf_path)
    pix = doc[0].get_pixmap(matrix=pymupdf.Matrix(SCALE, SCALE), alpha=False)
    img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    # 앱에 넣는 그림: 4608 정도로 줄여 JPEG 로 (JPEG 는 확대한 부분만 빨리 읽을 수 있음)
    side = 4608
    img.resize((side, side * pix.height // pix.width), Image.LANCZOS).save(ASSETS / "seoul_metro_map.jpg", "JPEG", quality=90, optimize=True)
    # 글자 인식은 설정마다 놓치는 글자가 달라서(큰 굵은 글씨는 어떤 설정에선 빠짐) 여러 설정으로 돌려 합침
    configs = [(OCRVersion.PPOCRV5, TILE, "min"), (OCRVersion.PPOCRV5, 960, "max"), (OCRVersion.PPOCRV5, 640, "max"),
               (OCRVersion.PPOCRV4, TILE, "min"), (OCRVersion.PPOCRV4, 640, "max")]
    W, H = img.size
    items = []
    for ver, limit, kind in configs:
        ocr = RapidOCR(params={"Det.ocr_version": ver, "Rec.ocr_version": ver, "Rec.lang_type": LangRec.KOREAN,
                               "Rec.model_type": ModelType.MOBILE, "Det.model_type": ModelType.MOBILE,
                               "Det.limit_side_len": limit, "Det.limit_type": kind})
        for y in range(0, H, TILE - OVERLAP):
            for x in range(0, W, TILE - OVERLAP):
                crop = img.crop((x, y, min(x + TILE, W), min(y + TILE, H)))
                res = ocr(np.array(crop))
                if res is None or res.boxes is None:
                    continue
                for box, txt, sc in zip(res.boxes, res.txts, res.scores):
                    xs = [p[0] for p in box]
                    ys = [p[1] for p in box]
                    items.append({"t": txt, "s": float(sc), "x0": float(min(xs) + x), "y0": float(min(ys) + y), "x1": float(max(xs) + x), "y1": float(max(ys) + y)})
        print("설정", ver.name, limit, kind, "누적", len(items), flush=True)
    return {"size": [W, H], "items": items}


def station_names():
    names = set()
    for line in (ASSETS / "subway_stations.tsv").read_text(encoding="utf-8").splitlines()[1:]:
        c = line.split("\t")
        if len(c) < 6:
            continue
        lat, lng = float(c[3]), float(c[4])
        if 36.55 <= lat <= 38.35 and 126.3 <= lng <= 127.95:
            n = norm(c[1])
            if n:
                names.add(n)
    return names


def best(name_set, text):
    """OCR 글자 → 역 이름. 정확히 같으면 그것, 아니면 비슷한 것(0.85 이상)"""
    t = hangul_only(norm(text))
    if len(t) < 2:
        return None, 0.0
    if t in name_set:
        return t, 1.0
    cand, score = None, 0.0
    for n in name_set:
        if abs(len(n) - len(t)) > 1:
            continue
        r = SequenceMatcher(None, t, n).ratio()
        if r > score:
            cand, score = n, r
    return (cand, score) if score >= 0.85 else (None, score)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    pdf = Path(args[0]) if args else Path.home() / "Downloads" / "지하철_국문노선도_20250929.pdf"
    if "--ocr-json" in sys.argv:
        data = json.loads(Path(sys.argv[sys.argv.index("--ocr-json") + 1]).read_text(encoding="utf-8"))
    else:
        data = render_and_ocr(pdf)
        Path(ROOT / "pipeline/out").mkdir(exist_ok=True)
        (ROOT / "pipeline/out/metro_map_ocr.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    W, H = data["size"]
    names = station_names()
    items = [i for i in data["items"] if re.search(r"[가-힣]", i["t"])]
    for i in items:
        i["cx"], i["cy"] = (i["x0"] + i["x1"]) / 2, (i["y0"] + i["y1"]) / 2
        i["h"] = min(i["y1"] - i["y0"], i["x1"] - i["x0"]) or 1  # 글자 굵기(세로쓰기·가로쓰기 모두)

    found = {}  # 역 이름 → [(cx, cy, score)]

    def add(name, cx, cy, score):
        found.setdefault(name, []).append((cx, cy, score))

    used = set()
    # 1) 글자 하나로 이름이 되는 것
    for idx, it in enumerate(items):
        n, sc = best(names, it["t"])
        if n:
            add(n, it["cx"], it["cy"], sc)
            used.add(idx)
    # 2) 두 줄로 나뉜 이름 (디지털 / 미디어시티): 가깝게 붙은 글자 둘을 이어서
    for a in range(len(items)):
        if a in used:
            continue
        for b in range(len(items)):
            if b == a or b in used:
                continue
            A, B = items[a], items[b]
            near = abs(A["cx"] - B["cx"]) < 3 * A["h"] * 4 and abs(A["cy"] - B["cy"]) < A["h"] * 3.2
            if not near:
                continue
            for first, second in ((A, B), (B, A)):
                n, sc = best(names, first["t"] + second["t"])
                if n and len(n) >= 4:
                    add(n, (A["cx"] + B["cx"]) / 2, (A["cy"] + B["cy"]) / 2, sc)
                    used.update((a, b))
                    break

    # 3) 큰 굵은 글씨는 글자 하나하나(강, 남)로 쪼개져 읽히기도 해서, 같은 줄에 붙은 짧은 글자들을 이어서 이름과 맞춤
    short = [k for k in range(len(items)) if k not in used and len(hangul_only(items[k]["t"])) <= 3]
    for a in short:
        if a in used:
            continue
        A = items[a]
        row = [k for k in short if k not in used and abs(items[k]["cy"] - A["cy"]) < 0.7 * A["h"] and abs(items[k]["cx"] - A["cx"]) < 4.5 * A["h"]]
        row.sort(key=lambda k: items[k]["cx"])
        for size in (2, 3):
            hit = False
            for i in range(len(row) - size + 1):
                part = row[i:i + size]
                if a not in part:
                    continue
                text = "".join(hangul_only(items[k]["t"]) for k in part)
                if text in names and len(text) >= 2:
                    add(text, sum(items[k]["cx"] for k in part) / size, sum(items[k]["cy"] for k in part) / size, 0.95)
                    used.update(part)
                    hit = True
                    break
            if hit:
                break

    # 4) 점(역 표시)에 붙어 있어 한글 글자가 깨져 읽힌 큰 글씨 역 이름: 바로 아래 영어 이름(Gangnam …)은 또렷하게 읽혀서,
    #    못 찾은 역의 한글 이름을 로마자로 바꿔(korean-romanizer, 이 단계에만 필요) 영어 글자와 비슷하면 그 자리로 씀
    try:
        from korean_romanizer.romanizer import Romanizer
    except ImportError:
        Romanizer = None
    if Romanizer is not None:
        latin = {}
        for it in data["items"]:
            t = re.sub(r"[^a-z]", "", it["t"].lower())
            if len(t) >= 3 and not re.search(r"[가-힣]", it["t"]):
                latin.setdefault(t, []).append(((it["x0"] + it["x1"]) / 2, (it["y0"] + it["y1"]) / 2, it["y1"] - it["y0"]))
        # 로마자가 공식 표기와 다른 역은 영어 표기의 앞부분으로
        prefix = {"홍대입구": "hongikuniv", "동대문역사문화공원": "dongdaemunhist", "구로디지털단지": "gurodigital"}
        for n in sorted(names - set(found)):
            want = prefix.get(n) or re.sub(r"[^a-z]", "", Romanizer(n).romanize().lower())
            if len(want) < 5:
                continue   # 짧은 이름은 영어가 비슷한 다른 역과 헷갈리기 쉬워 건너뜀
            cands = []
            for t, pts in latin.items():
                ok = t.startswith(want) if n in prefix else (SequenceMatcher(None, want, t).ratio() >= 0.8)
                if ok:
                    cands.append((t, pts))
            clusters = []
            for t, pts in cands:
                for cx, cy, hh in pts:
                    for g in clusters:
                        if abs(g[0] - cx) < 140 and abs(g[1] - cy) < 140:
                            break
                    else:
                        clusters.append((cx, cy, hh))
            if len(clusters) == 1:          # 한 곳으로 정해질 때만 (여러 곳이면 틀릴 수 있어 건너뜀)
                cx, cy, hh = clusters[0]
                add(n, cx, cy - hh * 1.2, 0.8)   # 한글 이름은 영어 바로 위

    # 같은 역을 겹친 조각에서 두 번 읽은 것은 하나로 (가까운 것끼리 평균), 점수 높은 것 우선
    rows = []
    for n, pts in sorted(found.items()):
        groups = []
        for cx, cy, sc in sorted(pts, key=lambda p: -p[2]):
            for g in groups:
                if abs(g[0][0] - cx) < 140 and abs(g[0][1] - cy) < 140:
                    g.append((cx, cy, sc))
                    break
            else:
                groups.append([(cx, cy, sc)])
        for g in groups:
            x, y = sum(p[0] for p in g) / len(g) / W, sum(p[1] for p in g) / len(g) / H
            if (y < 0.095 and x < 0.64) or (y > 0.95 and x > 0.8):
                continue
            rows.append((n, x, y))
    out = ASSETS / "seoul_metro_map_hotspots.tsv"
    out.write_text("\n".join(f"{n}\t{x:.5f}\t{y:.5f}" for n, x, y in rows) + "\n", encoding="utf-8")
    missing = sorted(names - set(found))
    print(f"역 {len(found)}개 / 앱의 수도권 역 {len(names)}개, 자리 {len(rows)}곳 → {out}")
    print("못 찾은 역:", ", ".join(missing[:80]), "..." if len(missing) > 80 else "")


if __name__ == "__main__":
    main()
