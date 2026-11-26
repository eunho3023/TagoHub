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
  // 서울 버스: 위치·정류장 도착은 5초, 노선 경로·정류장 목록은 하루
  assert.equal(ttlFor("/api/rest/buspos/getBusPosByRtid"), 5);
  assert.equal(ttlFor("/api/rest/stationinfo/getStationByUid"), 5);
  assert.equal(ttlFor("/api/rest/busRouteInfo/getRoutePath"), 86400);
  assert.equal(ttlFor("/api/rest/busRouteInfo/getStaionByRoute"), 86400);
  assert.equal(ttlFor("1613000/BusSttnInfoInqireService/getCtyCodeList"), 86400);
  assert.equal(ttlFor("1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList"), 3600);
  assert.equal(ttlFor("1613000/TrainInfo/GetStrtpntAlocFndTrainInfo"), 1800);
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
  // 공공데이터포털이 바쁠 때 JSON 으로 오는 99 도 저장하지 않음
  assert.equal(cacheable(200, '{"response":{"header":{"resultCode":99,"resultMsg":"가용한 세션이 존재하지 않습니다"}}}'), false);
  assert.equal(cacheable(200, '{"response":{"header":{"resultCode":"00"},"body":{}}}'), true);
  assert.equal(cacheable(200, "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"), false);
  assert.equal(cacheable(500, "<ok/>"), false);
});

test("여러 기관 중계: 허락한 호스트만", () => {
  assert.deepEqual(hostTarget("/h/https/dapi.kakao.com/v2/local/search/keyword.json"), { scheme: "https", host: "dapi.kakao.com", path: "/v2/local/search/keyword.json" });
  assert.deepEqual(hostTarget("/h/http/swopenapi.seoul.go.kr/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x"), { scheme: "http", host: "swopenapi.seoul.go.kr", path: "/api/subway/TAGOHUB_SEOUL_SUBWAY_KEY/xml/x" });
  // 서울 버스 (앱이 자리표시 키로 보냄)
  assert.deepEqual(hostTarget("/h/http/ws.bus.go.kr/api/rest/busRouteInfo/getRoutePath"), { scheme: "http", host: "ws.bus.go.kr", path: "/api/rest/busRouteInfo/getRoutePath" });
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
  // 노선 id 에 "-" 가 든 지역 (구미 GMB101-110)
  assert.ok(webRequest("/w/dg/1613000/BusRouteInfoInqireService/getRouteInfoIem", "?cityCode=37050&routeId=GMB101-110"));
  assert.equal(webRequest("/w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo", "?cityCode=25"), null);
  assert.equal(webRequest("/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=25"), null);
  assert.equal(webRequest("/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=2a&nodeId=X"), null);
  assert.equal(webRequest("/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "?cityCode=25&nodeId=X"), null);
});

test("공공데이터포털이 잠시 바쁘다는 답(99)은 저장하지 않음", () => {
  assert.equal(cacheable(200, "<response><header><resultCode>99</resultCode><resultMsg>가용한 세션이 존재하지 않습니다. (30/30)</resultMsg></header></response>"), false);
  assert.equal(cacheable(200, "<response><header><resultCode>00</resultCode></header><body><totalCount>4</totalCount></body></response>"), true);
});

test("웹 버전: 지하철 역 찾기·시간표, 기차 열차 (질의 검사, 개수 정함)", () => {
  const a = webRequest("/w/dg/1613000/SubwayInfo/GetKwrdFndSubwaySttnList", "?subwayStationName=" + encodeURIComponent("서울역"));
  assert.ok(a && a.search.includes("numOfRows=100"));
  const b = webRequest("/w/dg/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList", "?subwayStationId=MTRS11133&dailyTypeCode=01&upDownTypeCode=U");
  assert.ok(b && b.search.includes("numOfRows=1000"));
  assert.equal(webRequest("/w/dg/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList", "?subwayStationId=MTRS11133&dailyTypeCode=04&upDownTypeCode=U"), null);
  const c = webRequest("/w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo", "?depPlaceId=NAT010000&arrPlaceId=NAT014445&depPlandTime=20261105");
  assert.ok(c && c.search.includes("numOfRows=300"));
  assert.equal(webRequest("/w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo", "?depPlaceId=X&arrPlaceId=NAT014445&depPlandTime=20261105"), null);
  assert.equal(ttlFor("1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList"), 86400);
  assert.equal(ttlFor("1613000/TrainInfo/GetStrtpntAlocFndTrainInfo"), 1800);
});

test("웹 버전: 고속·시외버스 (터미널 id 모양 검사)", () => {
  const a = webRequest("/w/dg/1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo", "?depTerminalId=NAEK010&arrTerminalId=NAEK700&depPlandTime=20261105");
  assert.ok(a && a.search.includes("numOfRows=300"));
  assert.ok(webRequest("/w/dg/1613000/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo", "?depTerminalId=NAI3214401&arrTerminalId=NAI3507401&depPlandTime=20261105"));
  assert.ok(webRequest("/w/dg/1613000/SuburbsBusInfo/GetSuberbsBusTrminlList", "?terminalNm=" + encodeURIComponent("전주")));
  assert.equal(webRequest("/w/dg/1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo", "?depTerminalId=X1&arrTerminalId=NAEK700&depPlandTime=20261105"), null);
  assert.equal(ttlFor("1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo"), 1800);
  assert.equal(ttlFor("1613000/SuburbsBusInfo/GetSuberbsBusTrminlList"), 86400);
});

import { exWebRequest, exUrl } from "../src/proxy.js";

test("웹 한국도로공사: 허락한 기능과 쪽 번호만", () => {
  assert.deepEqual(exWebRequest("/w/ex/burstInfo/realTimeSms", ""), { path: "burstInfo/realTimeSms", page: "1", ttl: 120, extra: "" });
  assert.equal(exWebRequest("/w/ex/business/curStateStation", "?pageNo=3&key=evil").page, "3");
  assert.equal(exWebRequest("/w/ex/business/curStateStation", "?pageNo=21"), null);
  assert.equal(exWebRequest("/w/ex/business/curStateStation", "?pageNo=0"), null);
  // 휴게소 상세: 휴게소 번호(6자리)가 있어야만
  assert.equal(exWebRequest("/w/ex/restinfo/restBrandList", ""), null);
  assert.equal(exWebRequest("/w/ex/restinfo/restBrandList", "?stdRestCd=12&x=1"), null);
  assert.equal(exWebRequest("/w/ex/restinfo/restBrandList", "?stdRestCd=000001").extra, "&stdRestCd=000001");
  assert.equal(exUrl("restinfo/restBrandList", "1", "k", "&stdRestCd=000001"), "https://data.ex.co.kr/openapi/restinfo/restBrandList?key=k&type=json&numOfRows=99&pageNo=1&stdRestCd=000001");
  assert.equal(exWebRequest("/w/ex/../burstInfo/realTimeSms", ""), null);
  const u = exUrl("burstInfo/realTimeSms", "2", " k+/ ");
  assert.equal(u, "https://data.ex.co.kr/openapi/burstInfo/realTimeSms?key=k%2B%2F&type=json&numOfRows=99&pageNo=2");
});

import { seoulWebRequest, seoulArrivalUrl } from "../src/proxy.js";

test("웹 서울 지하철: 역 이름만", () => {
  assert.deepEqual(seoulWebRequest("/w/seoul/arrival/" + encodeURIComponent("총신대입구(이수)")), { name: "총신대입구(이수)", ttl: 10 });
  assert.equal(seoulWebRequest("/w/seoul/arrival/" + encodeURIComponent("a/b")), null);
  assert.equal(seoulWebRequest("/w/seoul/arrival/%E0%A4%A"), null);
  assert.equal(seoulWebRequest("/w/seoul/position/1호선"), null);
  assert.equal(seoulArrivalUrl("서울", " k "), "http://swopenapi.seoul.go.kr/api/subway/k/json/realtimeStationArrival/0/60/%EC%84%9C%EC%9A%B8");
});

import { kakaoWebRequest } from "../src/proxy.js";

test("웹 카카오: 장소 찾기·길찾기만, 좌표는 소수 5자리", () => {
  const a = kakaoWebRequest("/w/kakao/place", "?q=" + encodeURIComponent("대전역") + "&x=127.4345678&y=36.33&key=evil");
  assert.equal(a.url, "https://dapi.kakao.com/v2/local/search/keyword.json?query=%EB%8C%80%EC%A0%84%EC%97%AD&size=15&x=127.43457&y=36.33000&sort=accuracy");
  assert.equal(a.ttl, 86400);
  assert.equal(kakaoWebRequest("/w/kakao/place", "?q="), null);
  assert.equal(kakaoWebRequest("/w/kakao/place", "?q=" + "가".repeat(41)), null);
  const t = kakaoWebRequest("/w/kakao/transit", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2");
  assert.equal(t.url, "https://dapi.kakao.com/v2/routing/publictraffic?start_x=127.10000&start_y=36.10000&end_x=127.20000&end_y=36.20000");
  assert.equal(kakaoWebRequest("/w/kakao/transit", "?sx=126.97068121721812&sy=37.5546788388674&ex=127.0276&ey=37.4979").url,
    "https://dapi.kakao.com/v2/routing/publictraffic?start_x=126.97068&start_y=37.55468&end_x=127.02760&end_y=37.49790");
  assert.equal(kakaoWebRequest("/w/kakao/transit", "?sx=1e9&sy=36&ex=127&ey=36"), null);
  assert.equal(kakaoWebRequest("/w/kakao/boat", "?sx=1&sy=1&ex=1&ey=1"), null);
  // 도보·자전거·자동차 (경유지 5곳까지, 좌표 아닌 값은 막음)
  assert.equal(kakaoWebRequest("/w/kakao/walk", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2").url,
    "https://dapi.kakao.com/v2/routing/walk?start_x=127.10000&start_y=36.10000&end_x=127.20000&end_y=36.20000");
  assert.equal(kakaoWebRequest("/w/kakao/bicycle", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2").url.split("?")[0], "https://dapi.kakao.com/v2/routing/bicycle");
  const c = kakaoWebRequest("/w/kakao/car", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2&via=127.15,36.15|127.17,36.17");
  assert.ok(c.url.startsWith("https://apis-navi.kakaomobility.com/v1/directions?origin=127.10000,36.10000&destination=127.20000,36.20000&waypoints=127.15000,36.15000%7C127.17000,36.17000&priority=RECOMMEND"));
  assert.equal(c.ttl, 60);
  assert.equal(kakaoWebRequest("/w/kakao/car", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2&via=" + Array(6).fill("127.1,36.1").join("|")), null);
  assert.equal(kakaoWebRequest("/w/kakao/car", "?sx=127.1&sy=36.1&ex=127.2&ey=36.2&via=evil"), null);
});

import { kacWebRequest } from "../src/proxy.js";

test("웹 한국공항공사: 정해 둔 기능과 운항정보 질의만", () => {
  assert.deepEqual(kacWebRequest("/w/kac/airport-congestion/v1", "?serviceKey=evil"), { path: "airport-congestion/v1", search: "&type=json&pageNo=1&numOfRows=20", ttl: 120 });
  assert.equal(kacWebRequest("/w/kac/flight-aircraft-status/info", "?air=GMP&date=20261105").search,
    "&type=json&pageNo=1&numOfRows=100&schAirCode=GMP&schStTime=202611050000&schEdTime=202611052359");
  assert.equal(kacWebRequest("/w/kac/flight-aircraft-status/info", "?air=gmp&date=20261105"), null);
  assert.equal(kacWebRequest("/w/kac/flight-aircraft-status/info", "?air=GMP&date=20261105&pageNo=11"), null);
  assert.equal(kacWebRequest("/w/kac/parking-valet-congestion/gmp-dom", ""), null);
});

import { signalWebRequest } from "../src/proxy.js";

test("웹 신호등: 서울 교차로 번호, 울산 목록·지금만", () => {
  assert.deepEqual(signalWebRequest("/w/signal/seoul/1234"), { kind: "seoul", id: "1234", ttl: 3 });
  assert.equal(signalWebRequest("/w/signal/seoul/12a"), null);
  assert.deepEqual(signalWebRequest("/w/signal/ulsan/map"), { kind: "ulsan", op: "crsrd_map_info", ttl: 86400 });
  assert.equal(signalWebRequest("/w/signal/ulsan/now").op, "tl_drct_info");
  assert.equal(signalWebRequest("/w/signal/jeju/now"), null);
});

import { busWebRequest } from "../src/proxy.js";
test("웹 서울·경기 버스: 정해진 모양의 id 만", () => {
  assert.equal(busWebRequest("/w/bus/seoul/arrival/01002").url,
    "http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid?arsId=01002&resultType=json&serviceKey=KEY");
  assert.equal(busWebRequest("/w/bus/seoul/pos/100100118").cacheKey, "bus/seoul/pos/100100118");
  assert.equal(busWebRequest("/w/bus/gg/arrival/233001450").url,
    "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2?stationId=233001450&format=json&serviceKey=KEY");
  assert.equal(busWebRequest("/w/bus/gg/pos/234000315").ttl, 10);
  assert.equal(busWebRequest("/w/bus/seoul/arrival/1002"), null);
  assert.equal(busWebRequest("/w/bus/gg/arrival/12345"), null);
  assert.equal(busWebRequest("/w/bus/seoul/route/100100118"), null);
  assert.equal(busWebRequest("/w/bus/gg/pos/2340003a5"), null);
  const gr = busWebRequest("/w/bus/gg/routes/202000088");
  assert.ok(gr.url.includes("busstationservice/v2/getBusStationViaRouteListv2?stationId=202000088"));
  assert.equal(gr.ttl, 86400);
  assert.equal(busWebRequest("/w/bus/seoul/routes/01002"), null);
  const jr = busWebRequest("/w/bus/jeonju/routes/306101123");
  assert.ok(jr.url.startsWith("https://apis.data.go.kr/4641000/busstop/general_busstop_common?stopStandardid=306101123&stopStdid=306101123"));
  const ja = busWebRequest("/w/bus/jeonju/arrival/306101123");
  assert.ok(ja.url.includes("realtime/bus_location_busstop_infomation_common?stopStdid=306101123") && ja.ttl === 5);
  const jp = busWebRequest("/w/bus/jeonju/pos/305001140");
  assert.ok(jp.url.includes("realtime/bus_location_bus_position_common?brtStdid=305001140") && jp.ttl === 5 && jp.cacheKey === "bus/jeonju/pos/305001140");
  assert.equal(busWebRequest("/w/bus/jeonju/routes/3061011"), null);
  const tr = webRequest("/w/dg/1613000/BusSttnInfoInqireService/getSttnThrghRouteList", "?cityCode=35010&nodeid=JJB306000038");
  assert.ok(tr && tr.search.includes("nodeid=JJB306000038"));
  assert.ok(webRequest("/w/dg/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList", "?gpsLati=35.938251&gpsLong=126.946368"));
  assert.equal(webRequest("/w/dg/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList", "?gpsLati=35.9382519&gpsLong=126.946368"), null);
  assert.equal(webRequest("/w/dg/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList", "?gpsLati=1&gpsLong=126.9"), null);
});

import { icnWebRequest } from "../src/proxy.js";
test("웹 인천공항 운항: 날짜·쪽만", () => {
  const r = icnWebRequest("/w/icn/dep", "?date=20261105&pageNo=2");
  assert.equal(r.url, "https://apis.data.go.kr/B551177/statusOfAllFltDeOdp/getFltDeparturesDeOdp?serviceKey=KEY&type=json&numOfRows=1000&pageNo=2&searchdtCode=S&searchDate=20261105&searchFrom=0000&searchTo=2400&passengerOrCargo=P");
  assert.ok(icnWebRequest("/w/icn/arr", "?date=20261105").url.includes("getFltArrivalsDeOdp"));
  assert.equal(icnWebRequest("/w/icn/dep", "?date=2026-11-05"), null);
  assert.equal(icnWebRequest("/w/icn/dep", "?date=20261105&pageNo=9"), null);
  assert.equal(icnWebRequest("/w/icn/cargo", "?date=20261105"), null);
});

test("웹 통로: 노선 정류장 목록 (자료 조각에 없는 노선)", () => {
  const r = webRequest("/w/dg/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", "?cityCode=35010&routeId=JUB305001089");
  assert.equal(r.path, "1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList");
  assert.ok(r.search.includes("numOfRows=1000"));
  assert.ok(webRequest("/w/dg/1613000/BusRouteInfoInqireService/getRouteInfoIem", "?cityCode=35010&routeId=JUB305001089"));
  assert.equal(webRequest("/w/dg/1613000/BusRouteInfoInqireService/getRouteNoList", "?cityCode=35010&routeNo=1"), null);
});

test("웹 경기 노선 정류장 목록 (하루 저장), 서울은 없음", () => {
  const r = busWebRequest("/w/bus/gg/stops/234000315");
  assert.ok(r.url.includes("busrouteservice/v2/getBusRouteStationListv2?routeId=234000315"));
  assert.equal(r.ttl, 86400);
  assert.equal(busWebRequest("/w/bus/seoul/stops/100100118"), null);
});

test("전주 노선 정류장 차례: 전주시 버스정보 노선별 버스 위치 (하루 저장, TAGO 한도가 찼을 때 웹이 씀)", () => {
  const r = busWebRequest("/w/bus/jeonju/stops/305001818");
  assert.match(r.url, /4641000\/realtime\/bus_location_bus_position_common\?brtStdid=305001818&serviceKey=KEY/);
  assert.equal(r.cacheKey, "bus/jeonju/stops/305001818");
  assert.equal(r.ttl, 86400);
  assert.equal(busWebRequest("/w/bus/jeonju/stops/12"), null);
});

test("제주 노선 정류장 차례: 제주 버스정보 (키 없음, 하루 저장, TAGO 한도가 찼을 때 웹이 씀)", () => {
  const r = busWebRequest("/w/bus/jeju/stops/405130002");
  assert.equal(r.url, "https://bus.jeju.go.kr/api/searchBusRouteStationList.do?route_id=405130002");
  assert.equal(r.cacheKey, "bus/jeju/stops/405130002");
  assert.equal(r.ttl, 86400);
  // 정류장 도착 (TAGO 보다 새로워 웹이 먼저 씀, 5초 저장)
  const a = busWebRequest("/w/bus/jeju/arrival/405000153");
  assert.equal(a.url, "https://bus.jeju.go.kr/api/searchArrivalInfoList.do?station_id=405000153");
  assert.equal(a.ttl, 5);
  assert.equal(busWebRequest("/w/bus/jeju/pos/405130002"), null);
  // 노선 전체 목록 (데이터 갱신이 제주 서버에 바로 못 붙을 때)
  const l = busWebRequest("/w/bus/jeju/routes/all");
  assert.equal(l.url, "https://bus.jeju.go.kr/api/searchBusRouteList.do");
  assert.equal(l.ttl, 86400);
  assert.equal(busWebRequest("/w/bus/jeju/routes/405130002"), null);
  // 노선 종류 (누리집 노선 정보, POST): 제주만
  const ln = busWebRequest("/w/bus/jeju/line/405410111");
  assert.equal(ln.url, "https://bus.jeju.go.kr/data/search/getLineInfoByLineId");
  assert.equal(ln.post, "lineId=405410111");
  assert.equal(ln.ttl, 86400);
  assert.equal(busWebRequest("/w/bus/seoul/line/100100118"), null);
  assert.equal(busWebRequest("/w/bus/gg/line/233000031"), null);
  // 경기 노선 정보 (첫차·막차·배차, 하루 저장). 다른 지역은 없음
  const gi = busWebRequest("/w/bus/gg/info/234000015");
  assert.ok(gi.url.includes("busrouteservice/v2/getBusRouteInfoItemv2?routeId=234000015&format=json"));
  assert.equal(gi.ttl, 86400);
  assert.equal(gi.cacheKey, "bus/gg/info/234000015");
  assert.equal(busWebRequest("/w/bus/seoul/info/100100118"), null);
  assert.equal(busWebRequest("/w/bus/jeju/info/405000001"), null);
  assert.equal(busWebRequest("/w/bus/jeju/stops/12"), null);
});

test("웹 울산 도착: 공공데이터포털 울산 BIS(GW), 한글이 깨지지 않게 _type, 5초 저장, 도착만", () => {
  const r = busWebRequest("/w/bus/ulsan/arrival/193012313");
  assert.equal(r.url, "https://apis.data.go.kr/6310000/bis/getBusArrivalInfo?stopid=193012313&pageNo=1&numOfRows=100&_type=xml&serviceKey=KEY");
  assert.equal(r.ttl, 5);
  // 앱이 /h/ 중계로 같은 것을 물을 때의 저장 이름과 같음 (index.js: https://cache.tagohub/<scheme>/<host><path><search>)
  const app = "https/apis.data.go.kr/6310000/bis/getBusArrivalInfo?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&stopid=193012313&pageNo=1&numOfRows=100&_type=xml";
  assert.equal(r.cacheKey, app);
  assert.equal(ttlFor("/6310000/bis/getBusArrivalInfo"), 5);   // 앱 /h/ 중계도 같은 저장 시간
  // 부산·광주·여수 도착도 5초 (이름에 arrival 이 없어 60초 저장되던 것)
  for (const p of ["/6260000/BusanBIMS/stopArrByBstopid", "/6290000/gj_bis/arriveInfo", "/4810000/arrive/getArrInfoByStopID"]) assert.equal(ttlFor(p), 5, p);
  assert.equal(busWebRequest("/w/bus/ulsan/pos/193012313"), null);
  assert.equal(busWebRequest("/w/bus/ulsan/arrival/12345"), null);
});

test("웹 대전 도착·위치: 대전 BIS(6300000), 앱 /h/ 중계와 같은 저장 이름, 5초 저장", () => {
  const a = busWebRequest("/w/bus/daejeon/arrival/8001378");
  assert.equal(a.url, "https://apis.data.go.kr/6300000/arrive/getArrInfoByStopID?BusStopID=8001378&serviceKey=KEY");
  assert.equal(a.cacheKey, "https/apis.data.go.kr/6300000/arrive/getArrInfoByStopID?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&BusStopID=8001378");
  assert.equal(a.ttl, 5);
  const p = busWebRequest("/w/bus/daejeon/pos/30300039");
  assert.equal(p.url, "https://apis.data.go.kr/6300000/busposinfo/getBusPosByRtid?busRouteId=30300039&serviceKey=KEY");
  assert.equal(p.cacheKey, "https/apis.data.go.kr/6300000/busposinfo/getBusPosByRtid?serviceKey=TAGOHUB_TAGO_SERVICE_KEY&busRouteId=30300039");
  for (const x of ["/6300000/arrive/getArrInfoByStopID", "/6300000/busposinfo/getBusPosByRtid"]) assert.equal(ttlFor(x), 5, x);
  assert.equal(busWebRequest("/w/bus/daejeon/arrival/30300039"), null);  // 정류장은 7자리
  assert.equal(busWebRequest("/w/bus/daejeon/pos/8001378"), null);       // 노선은 8자리
  assert.equal(busWebRequest("/w/bus/daejeon/stops/8001378"), null);
});

test("'all' 은 제주 노선 목록만", () => {
  assert.equal(busWebRequest("/w/bus/seoul/pos/all"), null);
  assert.equal(busWebRequest("/w/bus/jeonju/routes/all"), null);
});

test("웹 서울 지하철 열차 위치: 아는 노선만, 10초 저장", async () => {
  const { seoulPositionRequest, seoulPositionUrl } = await import("../src/proxy.js");
  assert.deepEqual(seoulPositionRequest("/w/seoul/position/" + encodeURIComponent("2호선")), { line: "2호선", ttl: 10 });
  assert.deepEqual(seoulPositionRequest("/w/seoul/position/" + encodeURIComponent("수인분당선")), { line: "수인분당선", ttl: 10 });
  assert.equal(seoulPositionRequest("/w/seoul/position/" + encodeURIComponent("부산1호선")), null);
  assert.equal(seoulPositionRequest("/w/seoul/position/x/y"), null);
  assert.equal(seoulPositionUrl("2호선", " k "), "http://swopenapi.seoul.go.kr/api/subway/k/json/realtimePosition/0/300/" + encodeURIComponent("2호선"));
});

import { ovWebRequest, cctvRows, kakaoWebRequest as kakaoWebRequest2, webRequest as webRequest2 } from "../src/proxy.js";

test("웹 CCTV 목록: 고속도로·국도 파일만, XML 을 [이름, 위도, 경도, 주소]로", () => {
  assert.deepEqual(ovWebRequest("/w/ov/cctv/ex"), { file: "ov_its_cctv_ex.xml.gz", type: "ex", ttl: 600 });
  assert.equal(ovWebRequest("/w/ov/cctv/x"), null);
  assert.equal(ovWebRequest("/w/ov/../ov_meta.json"), null);
  const xml = "<response><data><cctvurl>http://a.kr/1/x+y==</cctvurl><coordy>37.4067123</coordy><cctvname>[경부선] 판교</cctvname><coordx>127.097</coordx></data>" +
    "<data><cctvurl></cctvurl><coordy>37.1</coordy><coordx>127.1</coordx><cctvname>주소 없음</cctvname></data>" +
    "<data><cctvurl>http://a.kr/2</cctvurl><coordy>0</coordy><coordx>0</coordx><cctvname>위치 없음</cctvname></data></response>";
  assert.deepEqual(cctvRows(xml), [["[경부선] 판교", 37.40671, 127.097, "http://a.kr/1/x+y=="]]);
});

test("웹 주유소: 카카오 장소 분류는 주유소(OL7)와 좁은 칸만", () => {
  const r = kakaoWebRequest2("/w/kakao/category", "?code=OL7&rect=127.02,37.5,127.04,37.52&page=2");
  assert.ok(r.url.includes("category_group_code=OL7&rect=127.02000,37.50000,127.04000,37.52000&page=2&size=15"));
  assert.equal(r.ttl, 86400);
  assert.equal(kakaoWebRequest2("/w/kakao/category", "?code=FD6&rect=127.02,37.5,127.04,37.52"), null);
  assert.equal(kakaoWebRequest2("/w/kakao/category", "?code=OL7&rect=126,37,127,38"), null);
  assert.equal(kakaoWebRequest2("/w/kakao/category", "?code=OL7&rect=127.02,37.5,127.04,37.52&page=4"), null);
});

test("웹 여객선 운항: 날짜만 받고 JSON 으로", () => {
  const r = webRequest2("/w/dg/B554035/oprt-schd-info-v2/get-oprt-schd-info-v2", "?rlvtYmd=20261122&serviceKey=evil");
  assert.equal(r.search, "?rlvtYmd=20261122&pageNo=1&numOfRows=1000&dataType=JSON");
  assert.equal(webRequest2("/w/dg/B554035/oprt-schd-info-v2/get-oprt-schd-info-v2", "?rlvtYmd=2026-11-22"), null);
});
