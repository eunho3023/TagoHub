import { test } from "node:test";
import assert from "node:assert/strict";
import { cacheKey, cacheable, encodeKey, ttlFor, upstreamPath, upstreamUrl } from "../src/proxy.js";

test("허락한 기관 경로만 중계", () => {
  assert.equal(upstreamPath("/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"), "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList");
  assert.equal(upstreamPath("/dg/9999999/Some/Op"), null);
  assert.equal(upstreamPath("/dg/1613000"), null);
  assert.equal(upstreamPath("/dg/1613000/../9999999/x"), null);
  assert.equal(upstreamPath("/other/1613000/x"), null);
});

test("저장 시간: 도착·위치 15초, 목록 하루, 주변 1시간", () => {
  assert.equal(ttlFor("1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"), 15);
  assert.equal(ttlFor("1613000/BusLcInfoInqireService/getRouteAcctoBusLcList"), 15);
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
