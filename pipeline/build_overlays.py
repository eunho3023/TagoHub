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

키: 환경변수 UTIC_API_KEY, ITS_API_KEY (없으면 저장소 루트 local.properties 에서 읽음).
한 항목이 실패하면 그 파일만 새로 만들지 않고(= 올리지 않고) 나머지는 그대로 진행한다. 앱은 오래된 파일을 쓰지 않는다.
"""
import datetime
import gzip
import json
import os
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


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    utic_key, its_key = get_key("UTIC_API_KEY"), get_key("ITS_API_KEY")
    if not utic_key and not its_key:
        print("UTIC_API_KEY / ITS_API_KEY 가 없습니다.", file=sys.stderr)
        return 1
    meta_path = os.path.join(OUT, "ov_meta.json")
    updated: dict = {}
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
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump({"updated": updated, "generated": int(time.time())}, f)
    print(f"완료: 성공 {ok}, 실패 {failed}")
    return 0 if ok > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
