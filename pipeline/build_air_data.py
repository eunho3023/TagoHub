"""한국공항공사 공공데이터(CSV)로 앱에 넣을 항공 자료를 만든다.

    python pipeline/build_air_data.py  [CSV 가 있는 폴더]      # 기본: ~/Downloads

읽는 파일(공공데이터포털 '한국공항공사_…' 파일데이터, 인코딩 CP949/UTF-8 자동 판별):
  국내선·국제선 항공기스케줄, 항공사 코드 정보, 주요공항 코드정보, 공항 위치정보, 전국공항 연락처·라운지,
  층별 입점업체 현황(공항별), 공항별 시설현황·이용시설정보·지연·결항 현황·교통정보·문화 행사 일정, 계류장 및 주차장 정보
만드는 파일(app/src/main/assets):
  air_schedule.tsv   종류(D/I) \t 항공사ICAO \t 편명 \t 출발 \t 도착 \t 출발시각 \t 도착시각 \t 운항요일 \t 시작일 \t 종료일
  air_airlines.tsv   ICAO \t 항공사명
  air_airports.tsv   IATA \t 한글 이름 \t 나라
  airport_info.json  공항별 정보 (주소·좌표·시설·라운지·입점업체·지연·결항·교통 …)
"""
import csv
import io
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets"
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"

# 공항 약칭(파일에 쓰인 이름) → 공항 코드
IATA = {"김포": "GMP", "김해": "PUS", "제주": "CJU", "대구": "TAE", "울산": "USN", "청주": "CJJ", "양양": "YNY", "무안": "MWX",
        "광주": "KWJ", "여수": "RSU", "사천": "HIN", "포항경주": "KPO", "군산": "KUV", "원주": "WJU"}


def find(pattern):
    hits = sorted(SRC.glob(f"한국공항공사_{pattern}*.csv"))
    return hits[-1] if hits else None


def rows(path):
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    return list(csv.DictReader(io.StringIO(text)))


def short(name):
    """'김포국제공항'·'포항경주공항'·'김포' → 약칭"""
    n = name.strip().replace("국제공항", "").replace("공항", "")
    return n


def num(v):
    v = (v or "").strip().replace(",", "")
    return float(v) if re.fullmatch(r"-?\d+(\.\d+)?", v) else None


def build_schedule():
    names = {}
    p = find("항공사 코드 정보")
    if p:
        for r in rows(p):
            names[r["항공사ICAO코드"].strip()] = r["항공사명"].strip()
    lines = []
    for kind, pat in (("D", "국내선 항공기스케줄"), ("I", "국제선 항공기스케줄")):
        p = find(pat)
        if not p:
            continue
        for r in rows(p):
            lines.append("\t".join([kind, r["항공사"].strip(), r["운항편명"].strip(), r["출발공항"].strip(), r["도착공항"].strip(),
                                    r["출발시간"].strip(), r["도착시간"].strip(), r["운항요일"].strip(), r["시작일자"].strip(), r["종료일자"].strip()]))
    (OUT / "air_schedule.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    used = {l.split("\t")[1] for l in lines}
    (OUT / "air_airlines.tsv").write_text("\n".join(f"{k}\t{names.get(k, k)}" for k in sorted(used)) + "\n", encoding="utf-8", newline="\n")
    print("schedule rows", len(lines), "airlines", len(used))


def build_airports():
    p = find("주요공항 코드정보")
    out = []
    if p:
        for r in rows(p):
            code = r["공항코드"].strip()
            raw = r["공항명(영문)"].strip()
            ko = re.sub(r"\(.*\)", "", raw).strip()
            out.append(f"{code}\t{ko}\t{r['구분'].strip()}")
    (OUT / "air_airports.tsv").write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
    print("airports", len(out))


def build_info():
    info = {k: {"code": v, "name": k + "공항"} for k, v in IATA.items()}
    p = find("공항 위치정보")
    if p:
        for r in rows(p):
            k = short(r["공항명"])
            if k in info:
                info[k].update(address=r["행정구역"].strip(), lat=num(r["위도(WGS84좌표)"]), lng=num(r["경도(WGS84좌표)"]))
    p = find("전국공항 연락처 정보")
    airline_phones = []
    if p:
        for r in rows(p):
            who = r["구분"].strip()
            k = short(who)
            if k in info:
                info[k]["phone"] = r["연락처"].strip()
                info[k]["name"] = who
            elif who:
                airline_phones.append({"name": who, "phone": r["연락처"].strip()})
    p = find("공항별 시설현황")
    if p:
        for r in rows(p):
            k = short(r["공항명"])
            if k in info:
                info[k]["facility"] = {kk.strip(): v.strip() for kk, v in r.items() if kk and kk.strip() != "공항명" and v and v.strip()}
    p = find("공항 이용시설정보")
    if p:
        for r in rows(p):
            k = short(r["공항"])
            if k in info:
                info[k].setdefault("counters", {})[r["구분"].strip()] = {kk.strip(): v.strip() for kk, v in r.items() if kk.strip() not in ("구분", "공항")}
    for key, pat, rename in (("delay", "공항별 지연 현황", None), ("cancel", "공항별 결항 현황", None)):
        p = find(pat)
        if p:
            for r in rows(p):
                k = short(r["공항"])
                if k in info:
                    info[k].setdefault(key, {})[r["노선"].strip()] = {kk.strip(): v.strip() for kk, v in r.items() if kk.strip() not in ("공항", "노선")}
    p = find("공항 계류장 및 주차장 정보")
    if p:
        for r in rows(p):
            k = short(r["공항별"])
            if k in info:
                info[k]["parking"] = {kk.strip(): v.strip() for kk, v in r.items() if kk.strip() != "공항별"}
    p = find("공항별 교통정보")
    if p:
        for r in rows(p):
            k = short(list(r.values())[0])
            if k in info:
                vals = [v.strip() for v in r.values()]
                info[k].setdefault("transport", []).append({"type": vals[2], "count": vals[3], "routes": vals[4]})
    p = find("공항별 문화 행사 일정")
    if p:
        for r in rows(p):
            vals = list(r.values())
            k = short(vals[0])
            if k in info:
                info[k]["events"] = {hdr.strip(): (v or "").strip() for hdr, v in list(r.items())[1:] if (v or "").strip()}
    p = find("전국공항 라운지 정보")
    if p:
        seen = set()
        for r in rows(p):
            k = short(r["공항"])
            key = (k, r["시설명"], r["위치"])
            if k in info and key not in seen:
                seen.add(key)
                info[k].setdefault("lounges", []).append({"name": r["시설명"].strip(), "place": r["위치"].strip(), "hours": r["운영시간"].strip(),
                                                          "phone": r["전화번호"].strip(), "services": r["주요상품_서비스"].strip()})
    for path in sorted(SRC.glob("한국공항공사_*층별 입점업체 현황*.csv")):
        for r in rows(path):
            k = short(r["공항"])
            if k in info:
                info[k].setdefault("shops", []).append({"terminal": r["청사"].strip(), "floor": r["위치"].strip(), "type": r["업종"].strip(), "name": r["업체명"].strip()})
    (OUT / "airport_info.json").write_text(json.dumps({"airports": info, "airlines": airline_phones}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8", newline="\n")
    print("airport_info", len(info), "airports,", sum(len(v.get("shops", [])) for v in info.values()), "shops")


if __name__ == "__main__":
    build_schedule()
    build_airports()
    build_info()
