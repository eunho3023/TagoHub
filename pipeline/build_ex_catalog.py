#!/usr/bin/env python3
"""
한국도로공사 고속도로 공공데이터 포털(data.ex.co.kr) OpenAPI 목록의 요청 규격을 모아 ex_catalog.json 으로 저장한다.
각 API: id, 서비스명, 이름, 주소(url), 요청변수(req: "이름*:설명", *=필수), 출력변수(out)
목록 id 는 https://data.ex.co.kr/openapi/intro/introduce02 의 표에서 가져온 것 (이 사이트는 브라우저 헤더가 없으면 막음).
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

BASE = "https://data.ex.co.kr"
IDS = ("0153,0154,0620,0622,0165,0166,0167,0161,0162,0163,0164,0106,0103,0102,0105,0617,0100,0101,0509,0508,0605,0610,0602,0601,"
       "0604,0603,0616,0122,0124,0123,0117,0114,0147,0115,0401,0402,0110,0108,0111,0135,0133,0134,0304,0126,0125,0142,0140,0144,"
       "0149,0406,0405,0145,0146,0621,0623,0141,0150,0151,0152,0148,0611,0615,0317,0316,0506,0501,0505,0507,0503,0504,0502,0311,"
       "0314,0313,0312,0305,0302,0301,0614,0613,0303,0118,0119,0120,0121,0404,0612,0127,0129,0128,0403,0618,0104").split(",")
# 서비스(묶음) 이름: introduce02 표의 왼쪽 칸 (브라우저에서만 보여서 여기에 적어 둠)
SERVICE = dict(l.split("|") for l in """0153|특송기간 교통분석
0154|특송기간 교통분석
0620|통행요금
0622|전자조달 정보
0165|재난위험지도
0166|재난위험지도
0167|재난위험지도
0161|재난관리 기타현황
0162|재난관리 기타현황
0163|재난관리 기타현황
0164|재난관리 기타현황
0106|입출구 교통량
0103|입출구 교통량
0102|입출구 교통량
0105|입출구 교통량
0617|입출구 교통량
0100|입출구 교통량
0101|입출구 교통량
0509|융합데이터
0508|융합데이터
0605|융합데이터
0610|융합데이터
0602|융합데이터
0601|융합데이터
0604|융합데이터
0603|융합데이터
0616|유지관리기타정보
0122|위치정보
0124|위치정보
0123|위치정보
0117|영업소간 통행시간
0114|영업소간 통행시간
0147|영업소간 통행시간
0115|영업소간 통행시간
0401|영업소간 교통량
0402|영업소간 교통량
0110|영업소간 교통량
0108|영업소간 교통량
0111|영업소간 교통량
0135|영업소 진입조절 시행여부
0133|영업소 진입조절 시행여부
0134|영업소 진입조절 시행여부
0304|안전주행 콘텐츠 정보
0126|버스전용차로 운영 시간여부
0125|버스전용차로 운영 시간여부
0142|교통 운영현황
0140|교통 운영현황
0144|교통 운영현황
0149|교통 운영현황
0406|교통 운영현황
0405|교통 운영현황
0145|교통 운영현황
0146|교통 운영현황
0621|교통 운영현황
0623|교통 운영현황
0141|교통 운영현황
0150|교통 운영현황
0151|교통 운영현황
0152|교통 운영현황
0148|교통 돌발 정보
0611|교통 돌발 정보
0615|고속도로 휴게소현황
0317|고속도로 휴게소현황
0316|고속도로 휴게소현황
0506|고속도로 휴게소현황
0501|고속도로 휴게소현황
0505|고속도로 휴게소현황
0507|고속도로 휴게소현황
0503|고속도로 휴게소현황
0504|고속도로 휴게소현황
0502|고속도로 휴게소현황
0311|고속도로 지적 재산권 현황(특허 등)
0314|고속도로 주유소 현황
0313|고속도로 주유소 현황
0312|고속도로 주유 가격정보
0305|고속도로 세부교통량 현황
0302|고속도로 버스전용차로 통행속도 현황
0301|고속도로 버스전용차로 통행속도 현황
0614|고속도로 교통예보
0613|고속도로 교통예보
0303|고속도로 교통예보
0118|VMS 메시지
0119|VMS 메시지
0120|VMS 메시지
0121|VMS 메시지
0404|VDS 정보
0612|RSE 정보
0127|LCS 운영여부
0129|LCS 운영여부
0128|LCS 운영여부
0403|AVC 정보
0618|AVC 정보
0104|6대관문 입출구 교통량""".splitlines())
COMMON = {"key", "type", "numOfRows", "pageNo"}
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ex_catalog.json")


def main() -> int:
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
    opener.addheaders = [("User-Agent", UA)]
    opener.open(BASE + "/openapi/intro/introduce02", timeout=30).read()  # 쿠키 받기

    def post(ep: str, api_id: str) -> dict:
        req = urllib.request.Request(
            f"{BASE}/openapi/basicinfo/{ep}", data=urllib.parse.urlencode({"apiId": api_id}).encode(),
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": f"{BASE}/openapi/basicinfo/openApiInfoM?apiId={api_id}&pn=-1",
                     "Origin": BASE, "Accept": "application/json, text/javascript, */*; q=0.01"})
        return json.loads(opener.open(req, timeout=30).read().decode("utf-8"))

    catalog = []
    for i in IDS:
        info = post("openApiInfo", i)["openApiInfoVOList"]
        detail = [x for x in post("openApiInfoDetail", i)["openApiInfoVOList"] if x.get("columeCode")]
        rows = [x for x in info if x.get("apiUrl")]
        catalog.append({
            "id": i,
            "svc": SERVICE.get(i, ""),
            "name": (rows[0].get("apiName") if rows else "") or "",
            "url": rows[0]["apiUrl"] if rows else None,
            "req": [f'{x["columeCode"]}{"*" if x.get("isEssential") == "필수" else ""}:{x["columeName"]}'
                    for x in detail if x.get("isInput") == "Y" and x["columeCode"] not in COMMON],
            "out": [f'{x["columeCode"]}:{x["columeName"]}' for x in detail if x.get("isInput") != "Y"],
        })
        print(i, catalog[-1]["name"], catalog[-1]["url"], file=sys.stderr)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=0, separators=(",", ":"))
    print(f"저장: {OUT} ({len(catalog)}개)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
