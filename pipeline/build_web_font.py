"""웹 버전 한글 글꼴을 작게 만들기 (Noto Sans KR 10MB → 약 1MB).

웹 화면(Compose wasm)은 브라우저 글꼴을 못 써서 글꼴 파일을 통째로 받는데, 원본은 10MB 라 첫 화면이 몇 분씩 걸렸다.
필요한 글자만 남긴다: 영문·숫자·기호, 자주 쓰는 한글 2,350자(KS X 1001), 자모(입력 중 글자), 그리고
서버에 올린 정류장 이름·시군 이름에 나오는 모든 글자(드문 글자도 정류장 이름은 다 보이게). 굵기는 보통(400) 하나로.

실행: python pipeline/build_web_font.py --data server/tago-proxy/public/data --out server/tago-proxy/public/fonts/NotoSansKR-sub.ttf
--src 를 안 주면 Google Fonts 저장소에서 원본을 받아 --cache 폴더에 두고 다시 씀.
"""
import argparse, json, pathlib, sys, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC_URL = "https://github.com/google/fonts/raw/main/ofl/notosanskr/NotoSansKR%5Bwght%5D.ttf"


def common_hangul():
    """KS X 1001 완성형 한글 2,350자 (EUC-KR 로 쓸 수 있는 글자)"""
    out = []
    for cp in range(0xAC00, 0xD7A4):
        try:
            chr(cp).encode("euc-kr")
            out.append(chr(cp))
        except UnicodeEncodeError:
            pass
    return out


def data_chars(data: pathlib.Path):
    chars = set()
    for f in list((data / "names").glob("*.json")) + [data / "cities.json"]:
        if f.exists():
            chars.update(f.read_text(encoding="utf-8"))
    return chars


def wanted(data: pathlib.Path) -> str:
    chars = set(chr(c) for c in range(0x20, 0x7F))                 # 영문·숫자·기호
    chars.update(chr(c) for c in range(0xA0, 0x100))               # 라틴-1 기호
    chars.update(chr(c) for c in range(0x3131, 0x3190))            # 한글 자모 (입력 중)
    chars.update("·…←→↑↓↔⇄−×○●◎△▲▽▼□■☆★※〈〉《》「」『』【】〔〕～￦℃")
    chars.update(common_hangul())
    chars.update(data_chars(data))
    return "".join(sorted(c for c in chars if c.isprintable() or c == " "))


def build(src: pathlib.Path, out: pathlib.Path, text: str) -> int:
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    from fontTools import subset

    font = TTFont(src)
    if "fvar" in font:
        font = instancer.instantiateVariableFont(font, {"wght": 400})
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.hinting = False
    s = subset.Subsetter(opts)
    s.populate(text=text)
    s.subset(font)
    out.parent.mkdir(parents=True, exist_ok=True)
    font.save(out)
    return out.stat().st_size


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--src")
    ap.add_argument("--data", default=str(ROOT / "server/tago-proxy/public/data"))
    ap.add_argument("--out", default=str(ROOT / "server/tago-proxy/public/fonts/NotoSansKR-sub.ttf"))
    ap.add_argument("--cache", default=str(ROOT / "server/tago-proxy/.fontcache"))
    a = ap.parse_args(argv)
    src = pathlib.Path(a.src) if a.src else pathlib.Path(a.cache) / "NotoSansKR.ttf"
    if not src.exists():
        src.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(SRC_URL, headers={"User-Agent": "TagoHub-build"})
        with urllib.request.urlopen(req, timeout=300) as r:
            src.write_bytes(r.read())
    text = wanted(pathlib.Path(a.data))
    size = build(src, pathlib.Path(a.out), text)
    print(f"웹 글꼴 만듦: 글자 {len(text)}개, {size // 1024}KB → {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
