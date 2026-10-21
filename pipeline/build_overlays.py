#!/usr/bin/env python3
"""
지도 레이어 정보(돌발·재난·CCTV 목록 등)를 공공 API 에서 전국 단위로 한 번에 받아 파일로 저장한다.
→ Release `data-latest` 에 올리면 앱이 API 를 직접 부르지 않고 이 파일을 읽는다 (빠르고 요청 제한에 안 걸림).

저장하는 파일 (out_overlays/): API 가 준 응답 원본을 gzip 으로 압축한 것(이름 끝에 .gz) + ov_meta.json (받은 시각)
  ov_utic_incidents.xml      UTIC 돌발정보
  ov_utic_road_risk.json     UTIC 도로위험상황예보
  ov_its_events.xml          ITS 돌발정보
  ov_its_disasters.xml       ITS 재난정보 (최근 7일)
  ov_its_safe_driving.xml    ITS 주의운전구간
  ov_its_cctv_ex.xml         ITS CCTV 목록 (고속도로)
  ov_its_cctv_its.xml        ITS CCTV 목록 (국도)
  ov_utic_safety.json        UTIC 교통안전정보 (16MB 엑셀 묶음을 미리 풀어 점 목록만: [분류,id,설명,주소,위도,경도]) — 12시간마다만 새로 만듦

키: 환경변수 UTIC_API_KEY, ITS_API_KEY (없으면 저장소 루트 local.properties 에서 읽음).
한 항목이 실패하면 그 파일만 새로 만들지 않고(= 올리지 않고) 나머지는 그대로 진행한다. 앱은 오래된 파일을 쓰지 않는다.
"""
import datetime
import gzip
import io
import json
import os
import re
import zipfile
import xml.etree.ElementTree as ET
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "pipeline", "out_overlays")
UA = "Mozilla/5.0 TagoHub-overlays"
# 우리나라 전체 범위 (제주·울릉도·독도 포함)
KOREA = {"minX": "124.0", "maxX": "132.0", "minY": "33.0", "maxY": "39.0"}


def read_local_properties() -> dict:
    props = {}
    path = os.path.join(ROOT, "local.properties")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    props[k.strip()] = v.strip()
    return props


def get_key(name: str) -> str:
    return os.environ.get(name) or read_local_properties().get(name, "")


def fetch(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - 재시도 후 마지막 오류를 그대로 올림
            last = e
            time.sleep(2 * (attempt + 1))
    raise last  # type: ignore[misc]


def looks_ok(name: str, body: bytes) -> bool:
    """오류 응답(인증 실패·빈 응답)을 정상 데이터로 올리지 않도록 최소한만 확인"""
    text = body.decode("utf-8", errors="replace").strip().lstrip("﻿")
    if len(text) < 20:
        return False
    low = text[:2000].lower()
    if "<errorcode>" in low or "<errormessage>" in low and "<item" not in low and "<data" not in low:
        return False
    if "openapi_serviceresponse" in low or "service_key_is_not_registered" in low:
        return False
    if name.endswith(".json"):
        try:
            json.loads(text)
        except ValueError:
            return False
        return True
    return text.startswith("<")


def its_url(endpoint: str, key: str, **params) -> str:
    q = {"apiKey": key, "getType": "xml", **KOREA, **params}
    return f"https://openapi.its.go.kr:9443/{endpoint}?" + urllib.parse.urlencode(q)


def jobs(utic_key: str, its_key: str):
    today = datetime.date.today()
    week_ago = today - datetime.timedelta(days=7)
    if utic_key:
        yield "ov_utic_incidents.xml", "http://www.utic.go.kr/guide/imsOpenData.do?" + urllib.parse.urlencode({"key": utic_key})
        yield "ov_utic_road_risk.json", "http://www.utic.go.kr/guide/getRoadAccJson.do?" + urllib.parse.urlencode({"key": utic_key})
    if its_key:
        yield "ov_its_events.xml", its_url("eventInfo", its_key, type="all", eventType="all")
        yield "ov_its_disasters.xml", its_url(
            "disasterInfo", its_key, category="D", eventType="all",
            startDate=week_ago.strftime("%Y%m%d"), endDate=today.strftime("%Y%m%d"))
        yield "ov_its_safe_driving.xml", its_url("posIncidentInfo", its_key)
        for t in ("ex", "its"):
            yield f"ov_its_cctv_{t}.xml", its_url("cctvInfo", its_key, type=t, cctvType="1")


SAFETY_NAME = "ov_utic_safety.json"
SAFETY_EVERY_SEC = 12 * 3600
META_URL = os.environ.get(
    "OVERLAY_META_URL",
    "https://github.com/eunho3023/TagoHub/releases/download/data-latest/ov_meta.json",
)
_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def previous_meta() -> dict:
    """이번에 새로 만들지 않은 파일(안전정보)의 시각을 잃지 않도록 Release 의 이전 meta 를 이어받음"""
    try:
        return dict(json.loads(fetch(META_URL, timeout=20).decode("utf-8")).get("updated", {}))
    except Exception:  # noqa: BLE001 - 없으면 처음부터
        return {}


def build_safety(utic_key: str) -> bytes:
    """tsdmsOpenData.do (엑셀 여러 개가 든 zip) → 앱이 쓰는 점 목록 JSON.
    열 규칙은 앱의 UticSafetyRowParser 와 같음: B=Y 만, M 경도, N 위도, A id, O/P 설명, G/D 주소. 첫 줄은 제목."""
    raw = fetch("http://www.utic.go.kr/guide/tsdmsOpenData.do?" + urllib.parse.urlencode({"key": utic_key}), timeout=180)
    if raw[:2] != b"PK":
        raise ValueError("zip 이 아님: " + raw[:120].decode("utf-8", errors="replace"))
    points = []
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        for info in z.infolist():
            if info.is_dir() or not info.filename.lower().endswith(".xlsx"):
                continue
            name = info.filename
            if not info.flag_bits & 0x800:  # UTF-8 표시가 없으면 한국어 윈도우(CP949) 이름
                try:
                    name = name.encode("cp437").decode("cp949")
                except UnicodeError:
                    pass
            cat = name.rsplit("/", 1)[-1][:-5]
            with zipfile.ZipFile(io.BytesIO(z.read(info))) as wb:
                shared = []
                if "xl/sharedStrings.xml" in wb.namelist():
                    for _, el in ET.iterparse(wb.open("xl/sharedStrings.xml")):
                        if el.tag == _NS + "si":
                            shared.append("".join(t.text or "" for t in el.iter(_NS + "t")))
                            el.clear()
                for _, el in ET.iterparse(wb.open("xl/worksheets/sheet1.xml")):
                    if el.tag != _NS + "row":
                        continue
                    if int(el.get("r") or 0) > 1:
                        row = {}
                        for c in el.findall(_NS + "c"):
                            col = re.match(r"[A-Z]+", c.get("r")).group(0)
                            v = c.find(_NS + "v")
                            if c.get("t") == "s":
                                val = shared[int(v.text)] if v is not None and v.text else ""
                            elif c.get("t") == "inlineStr":
                                val = "".join(t.text or "" for t in c.iter(_NS + "t"))
                            else:
                                val = v.text if v is not None and v.text else ""
                            row[col] = val
                        try:
                            if row.get("B") == "Y" and row.get("A"):
                                lng, lat = float(row["M"]), float(row["N"])
                                if -90 <= lat <= 90 and -180 <= lng <= 180:
                                    points.append([cat, row["A"], row.get("O") or row.get("P") or cat, row.get("G") or row.get("D") or None, lat, lng])
                        except (KeyError, ValueError):
                            pass
                    el.clear()
    if not points:
        raise ValueError("안전정보 점이 하나도 없음")
    return json.dumps(points, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    utic_key, its_key = get_key("UTIC_API_KEY"), get_key("ITS_API_KEY")
    if not utic_key and not its_key:
        print("UTIC_API_KEY / ITS_API_KEY 가 없습니다.", file=sys.stderr)
        return 1
    meta_path = os.path.join(OUT, "ov_meta.json")
    updated: dict = previous_meta()
    ok = failed = 0
    for name, url in jobs(utic_key, its_key):
        try:
            body = fetch(url)
            if not looks_ok(name, body):
                raise ValueError("정상 응답이 아님: " + body[:120].decode("utf-8", errors="replace").replace("\n", " "))
            with open(os.path.join(OUT, name + ".gz"), "wb") as f:  # 앱이 받는 양을 줄이려고 압축 (약 1/6)
                f.write(gzip.compress(body, 9))
            updated[name] = int(time.time())
            ok += 1
            print(f"OK   {name}  {len(body):,} bytes")
        except Exception as e:  # noqa: BLE001
            failed += 1
            # 키가 주소에 들어 있어서 오류 문구에서 지움
            msg = str(e)
            for k in (utic_key, its_key):
                if k:
                    msg = msg.replace(k, "***")
            print(f"FAIL {name}: {msg}", file=sys.stderr)
            stale = os.path.join(OUT, name + ".gz")
            if os.path.exists(stale):
                os.remove(stale)  # 실패한 항목은 올리지 않음 (오래된 파일을 새것처럼 올리지 않게)
    if utic_key and time.time() - updated.get(SAFETY_NAME, 0) > SAFETY_EVERY_SEC:
        try:
            body = build_safety(utic_key)
            with open(os.path.join(OUT, SAFETY_NAME + ".gz"), "wb") as f:
                f.write(gzip.compress(body, 9))
            updated[SAFETY_NAME] = int(time.time())
            ok += 1
            print(f"OK   {SAFETY_NAME}  {len(body):,} bytes")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {SAFETY_NAME}: {str(e).replace(utic_key, '***')}", file=sys.stderr)
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump({"updated": updated, "generated": int(time.time())}, f)
    print(f"완료: 성공 {ok}, 실패 {failed}")
    return 0 if ok > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
