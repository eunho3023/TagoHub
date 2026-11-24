import { test } from "node:test";
import assert from "node:assert/strict";

// busdata.js → core.js 가 브라우저 전역을 씀: 최소한으로 흉내 (crowd.test.js 와 같게)
const noop = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...noop };
globalThis.window = noop;
const realInterval = globalThis.setInterval;
globalThis.setInterval = (...a) => { const t = realInterval(...a); t.unref?.(); return t; };
const { daejeonArrivalList, daejeonBusList, daejeonRows, busAtStops, klidPick, klidTime, KLID_REGIONS, headingTracker, declutterCaptions, todayDeps, isLowArrival, toMinutes, nextLow, arriveStage, busSpacing, runningNow, allEnded } = await import("../site/js/busdata.js");
const { slotOf, timeScore, byTime } = await import("../site/js/store.js");
const { klidWebRequest } = await import("../src/proxy.js");

const head = (cd) => `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><ServiceResult><msgHeader><currentPage>1</currentPage><headerCd>${cd}</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg></msgHeader><msgBody>`;

test("대전 도착: 남은 초·정류장·방면·노선 종류, 출발 전 차(MSG_TP 07, 초 자리에 출발 시각)는 뺌 (2026-11-17 실제 응답 모양)", () => {
  const xml = head(0) + `<itemList><BUS_NODE_ID>8001378</BUS_NODE_ID><DESTINATION>탄방역종점</DESTINATION><EXTIME_MIN>3</EXTIME_MIN><EXTIME_SEC>128</EXTIME_SEC><MSG_TP>03</MSG_TP><ROUTE_CD>30300039</ROUTE_CD><ROUTE_NO>104</ROUTE_NO><ROUTE_TP>2  </ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList>`
    + `<itemList><DESTINATION>탑립동</DESTINATION><EXTIME_MIN>1</EXTIME_MIN><EXTIME_SEC>1448</EXTIME_SEC><MSG_TP>07</MSG_TP><ROUTE_CD>30300096</ROUTE_CD><ROUTE_NO>918</ROUTE_NO><ROUTE_TP>2</ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList></msgBody></ServiceResult>`;
  assert.deepEqual(daejeonArrivalList(xml), [{ routeId: "DJB30300039", routeNo: "104", routeType: "간선버스", vehicleType: null, prevStops: 2, arrSeconds: 128, city: "25", towards: "탄방역종점" }]);
  assert.deepEqual(daejeonRows(head(4) + "</msgBody></ServiceResult>"), []);
  assert.throws(() => daejeonRows(head(2) + "</msgBody></ServiceResult>"));
});

test("대전 버스 위치: 정류장 ID 로 자리 찾기, 같은 정류장이 두 번이면 오는 길(dir 1)은 뒤쪽", () => {
  const xml = head(0) + `<itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>0</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9263</PLATE_NO></itemList>`
    + `<itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>1</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9217</PLATE_NO></itemList></msgBody></ServiceResult>`;
  const buses = daejeonBusList(xml);
  assert.deepEqual(buses[0], { nodeId: "DJB8002589", ord: null, vehicleNo: "대전75자9263", lat: 36.359108, lng: 127.309164, dir: 0 });
  const stops = [{ id: "DJB8002589", seq: 7 }, { id: "DJB8001000", seq: 30 }, { id: "DJB8002589", seq: 64 }];
  const at = busAtStops(stops, buses);
  assert.deepEqual([...at.entries()].map(([i, l]) => [i, l.map((b) => b.vehicleNo)]), [[0, ["대전75자9263"]], [2, ["대전75자9217"]]]);
});

test("지도 범위 버스(초정밀): 3분 넘은 위치·지도 밖은 빼고, 번호·종류는 노선 기준정보로, 노선 id 는 USB+rteId", () => {
  const now = klidTime("2026-11-24 21:10:00");
  assert.equal(now, Date.parse("2026-11-24T12:10:00Z"));
  const routes = new Map([["192000001", { rteId: "192000001", rteNo: "중구01", rteType: "지선버스" }]]);
  const rows = [
    { rteId: "192000001", vhclNo: "1", rteNo: "", lat: "35.57", lot: "129.33", gthrDt: "2026-11-24 21:09:30.1" },
    { rteId: "192000001", vhclNo: "2", rteNo: "", lat: "35.57", lot: "129.33", gthrDt: "2026-11-11 12:10:01.9" },   // 며칠 전
    { rteId: "192000001", vhclNo: "3", rteNo: "", lat: "36.50", lot: "129.33", gthrDt: "2026-11-24 21:09:59" },     // 지도 밖
  ];
  const out = klidPick(rows, routes, KLID_REGIONS[0], { s: 35.5, w: 129.2, n: 35.6, e: 129.4 }, now);
  assert.deepEqual(out, [{ key: "192000001:1", routeId: "USB192000001", city: "26", no: "중구01", type: "지선버스", lat: 35.57, lng: 129.33 }]);
  assert.deepEqual(klidWebRequest("/w/klid/pos/3100000000/2"), { op: "rtm_loc_info", code: "3100000000", page: 2, ttl: 4 });
  assert.equal(klidWebRequest("/w/klid/routes/3100000000/1").ttl, 86400);
  assert.equal(klidWebRequest("/w/klid/pos/31/1"), null);
});

test("지도 버스 진행 방향·이름표 솎기·오늘 시간표·저상 도착", () => {
  const h = headingTracker();
  assert.equal(h("A", 37.5, 127.0), null);                 // 처음은 모름
  assert.equal(h("A", 37.50005, 127.0), null);             // 15m 안: 앞서 잰 방향(없음)
  assert.ok(Math.abs(h("A", 37.501, 127.0) - 0) < 1);      // 북쪽
  assert.ok(Math.abs(h("A", 37.501, 127.001) - 90) < 1);   // 동쪽
  const bus = (id, lat, lng, caption) => ({ id, lat, lng, bus3d: { no: "1", caption } });
  const ds = [bus("a", 37.5, 127.0, "저상 · 1"), bus("b", 37.5, 127.0001, "일반 · 2"), bus("c", 37.51, 127.0, "3"), { id: "s", lat: 37.5, lng: 127 }];
  assert.deepEqual(declutterCaptions(ds, 16, 1).map((d) => d.bus3d?.caption ?? null), ["저상 · 1", null, "3", null]);
  assert.deepEqual(declutterCaptions(ds, 14, 1).map((d) => d.bus3d?.caption ?? null), [null, null, null, null]);
  const deps = { weekday: [360], sat: [400], holi: [] };
  assert.deepEqual(todayDeps(deps, 3), [360]);
  assert.deepEqual(todayDeps(deps, 6), [400]);
  assert.deepEqual(todayDeps(deps, 0), [360]);              // 휴일 칸이 비면 평일
  assert.equal(isLowArrival({ vehicleType: "저상버스" }), true);
  assert.equal(isLowArrival({ vehicleType: "일반" }), false);
  assert.equal(isLowArrival({ vehicleType: null }), null);
  assert.equal(toMinutes("22:30"), 1350);
});

test("다음 저상버스·도착 알림 단계·버스 몰림·막차 뒤·시간대별 홈", () => {
  const low = { vehicleType: "저상버스", arrSeconds: 600, prevStops: 6 }, gen = { vehicleType: "일반", arrSeconds: 120, prevStops: 2 };
  assert.equal(nextLow([gen, low]), low);
  assert.equal(nextLow([low, gen]), null);                  // 먼저 오는 차가 저상
  assert.equal(nextLow([gen]), false);                      // 오는 차 중 저상 없음
  assert.equal(nextLow([{ arrSeconds: 60 }]), null);         // 차 종류 모름
  assert.equal(arriveStage({ arrSeconds: 600, prevStops: 8 }), 0);
  assert.equal(arriveStage({ arrSeconds: 280, prevStops: 5 }), 1);
  assert.equal(arriveStage({ arrSeconds: 900, prevStops: 3 }), 1);
  assert.equal(arriveStage({ arrSeconds: 80, prevStops: 2 }), 2);
  assert.equal(arriveStage({ arrSeconds: 30, prevStops: 0, waiting: true }), 0);
  assert.equal(arriveStage({ arrSeconds: 200, prevStops: null }, 120), 2);   // 받은 뒤 2분 지남
  // 정류장 300m 간격 → 한 칸 1.3분
  const stops = Array.from({ length: 40 }, (_, i) => ({ lat: 37.5 + i * 0.0027, lng: 127 }));
  const sp = busSpacing(stops, [3, 4, 30, -1]);
  assert.deepEqual(sp.bunched.map((x) => [x.i, x.j]), [[3, 4]]);
  assert.deepEqual([sp.gap.i, sp.gap.j], [4, 30]);
  assert.equal(busSpacing(stops, [3, 30], 30).gap, null);    // 배차 30분이면 35분 간격은 보통
  assert.deepEqual(busSpacing(stops, [5]), { bunched: [], gap: null });
  const sums = [{ no: "1", first: "05:30", last: "23:00" }, { no: "N1", first: "23:30", last: "04:00" }, { no: "2" }];
  assert.deepEqual(runningNow(sums, 60).map((s) => s.no), ["N1"]);
  assert.equal(allEnded(sums.slice(0, 1), 60), true);
  assert.equal(allEnded(sums, 60), false);
  assert.equal(allEnded([{ no: "2" }], 60), false);          // 첫차·막차를 모르면 끊겼다고 하지 않음
  // 2026-11-25(수) 08:10 한국 시각 = 평일 8시 칸
  const wed8 = Date.UTC(2026, 10, 24, 23, 10);
  assert.equal(slotOf(wed8), 8);
  assert.equal(slotOf(Date.UTC(2026, 10, 28, 23, 10)), 24 + 8);   // 일요일
  const c = new Array(48).fill(0); c[8] = 3; c[7] = 1; c[18] = 9;
  assert.equal(timeScore(c, wed8), 7);
  const u = { work: (() => { const a = new Array(48).fill(0); a[18] = 5; return a; })(), home: c };
  assert.deepEqual(byTime([{ id: "work" }, { id: "home" }, { id: "x" }], u, wed8).map((x) => x.id), ["home", "work", "x"]);
});
