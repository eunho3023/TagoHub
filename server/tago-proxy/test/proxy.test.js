import { test } from "node:test";
import assert from "node:assert/strict";
import { cacheKey, cacheable, encodeKey, fillKeys, hostTarget, hostTtl, ttlFor, upstreamPath, upstreamUrl } from "../src/proxy.js";

test("허락한 기관 경로만 중계", () => {
  assert.equal(upstreamPath("/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"), "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList");
  assert.equal(upstreamPath("/dg/9999999/Some/Op"), null);
  assert.equal(upstreamPath("/dg/1613000"), null);
  assert.equal(upstreamPath("/dg/1613000/../9999999/x"), null);
  assert.equal(upstreamPath("/other/1613000/x"), null);
});

test("저장 시간: 도착·위치 5초, 목록 하루, 주변 1시간", () => {
  assert.equal(ttlFor("1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"), 5);
  assert.equal(ttlFor("1613000/BusLcInfoInqireService/getRouteAcctoBusLcList"), 5);
  assert.equal(ttlFor("1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList"), 86400);
  assert.equal(ttlFor("1613000/BusSttnInfoInqireService/getCtyCodeList"), 86400);
  assert.equal(ttlFor("1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList"), 3600);
  assert.equal(ttlFor("1613000/TrainInfo/GetStrtpntAlocFndTrainInfo"), 60);
});

test("키는 서버 것으로, 앱이 보낸 키는 버림", () => {
  const u = upstreamUrl("1613000/A/b", "?cityCode=25&nodeId=X&serviceKey=APPKEY", "ab+c/d==");
  assert.ok(u.startsWith("https://apis.data.go.kr/1613000/A/b?serviceKey=ab%2Bc%2Fd%3D%3D&"));
  assert.ok(!u.includes("APPKEY"));
  assert.ok(u.includes("cityCode=25") && u.includes("nodeId=X"));
  assert.equal(encodeKey(" ab%2Bc "), "ab%2Bc");
});

test("같은 요청은 같은 저장 이름 (순서·키 무시)", () => {
  assert.equal(cacheKey("1613000/A/b", "?b=2&a=1&serviceKey=K"), cacheKey("1613000/A/b", "?a=1&b=2"));
  assert.notEqual(cacheKey("1613000/A/b", "?a=1"), cacheKey("1613000/A/b", "?a=2"));
});

test("오류 응답은 저장하지 않음", () => {
  assert.equal(cacheable(200, "<response><header><resultCode>00</resultCode></header></response>"), true);
  assert.equal(cacheable(200, "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>"), false);
  assert.equal(cacheable(200, "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"), false);
  assert.equal(cacheable(500, "<ok/>"), false);
});

test("여러 기관 중계: 허락한 호스트만", () => {
  assert.deepEqual(hostTarget("/h/https/dapi.kakao.com/v2/local/search/keyword.json"), { scheme: "https", host: "dapi.kakao.com", path: "/v2/local/search/keyword.json" });
  assert.deepEqual(hostTarget("/h/http/swopenapi.seoul.go.kr/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x"), { scheme: "http", host: "swopenapi.seoul.go.kr", path: "/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x" });
  assert.equal(hostTarget("/h/https/evil.example.com/x"), null);
  assert.equal(hostTarget("/h/https/openapi.its.go.kr/x"), null);   // 9443 포트라 중계 못 함
  assert.equal(hostTarget("/h/http/www.utic.go.kr/guide/x"), null);   // 등록한 IP 에서만 응답
  assert.equal(hostTarget("/h/ftp/apis.data.go.kr/x"), null);
  assert.equal(hostTarget("/h/https/apis.data.go.kr/../x"), null);
});

test("자리표시를 키로: 주소는 인코딩, 헤더는 그대로, 없는 키는 오류", () => {
  const env = { TAGO_SERVICE_KEY: "ab+c/d==", KAKAO_REST_KEY: "kk", SEOUL_SUBWAY_KEY: "5a6b" };
  assert.equal(fillKeys("?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&a=1", env, true), "?serviceKey=ab%2Bc%2Fd%3D%3D&a=1");
  assert.equal(fillKeys("KakaoAK TAGOHUB_KAKAO_REST_KEY", env, false), "KakaoAK kk");
  assert.equal(fillKeys("/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml", env, false), "/api/subway/5a6b/xml");
  assert.throws(() => fillKeys("?k=TAGOHUB_EX_API_KEY", env, true), /EX_API_KEY/);
  assert.throws(() => fillKeys("?k=TAGOHUB_PATH_KEY", { PATH_KEY: "x" }, true));   // 목록에 없는 이름은 안 바꿈
  assert.equal(fillKeys("?a=1", env, true), "?a=1");
});

test("카카오 저장 시간", () => {
  assert.equal(hostTtl("dapi.kakao.com", "/v2/local/search/keyword.json"), 86400);
  assert.equal(hostTtl("apis-navi.kakaomobility.com", "/v1/directions"), 60);
  assert.equal(hostTtl("apis.data.go.kr", "/1613000/ArvlInfoInqireService/x"), 5);
});

import { webRequest } from "../src/proxy.js";

test("웹 버전: 실시간 도착·위치만, 질의는 정해진 것만", () => {
  const r = webRequest("/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?nodeId=JJB306000104&cityCode=35020&serviceKey=x&evil=1");
  assert.equal(r.path, "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList");
  assert.equal(r.search, "?cityCode=35020&nodeId=JJB306000104&pageNo=1&numOfRows=100&_type=xml");
  assert.ok(webRequest("/w/dg/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList", "?cityCode=25&routeId=DJB30300004"));
  assert.equal(webRequest("/w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo", "?cityCode=25"), null);
  assert.equal(webRequest("/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=25"), null);
  assert.equal(webRequest("/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=2a&nodeId=X"), null);
  assert.equal(webRequest("/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=25&nodeId=X"), null);
});

test("공공데이터포털이 잠시 바쁘다는 답(99)은 저장하지 않음", () => {
  assert.equal(cacheable(200, "<response><header><resultCode>99</resultCode><resultMsg>가용한 세션이 존재하지 않습니다. (30/30)</resultMsg></header></response>"), false);
  assert.equal(cacheable(200, "<response><header><resultCode>00</resultCode></header><body><totalCount>4</totalCount></body></response>"), true);
});
