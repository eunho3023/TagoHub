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
const { dotKey } = await import("../site/js/mapdiff.js");
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
  // 위치 줄 rteNo 가 내부 id 인 지역(구미): 기준정보 번호, 기준정보에 없으면 빈 번호
  const gumi = KLID_REGIONS.find((r) => r.name === "구미");
  const gRoutes = new Map([["355000004", { rteId: "355000004", rteNo: "20-2", rteType: "13" }]]);
  const gRows = [
    { rteId: "355000004", vhclNo: "9", rteNo: "355000255", lat: "36.13", lot: "128.33", gthrDt: "2026-11-24 21:09:30" },
    { rteId: "355000999", vhclNo: "8", rteNo: "355000777", lat: "36.13", lot: "128.33", gthrDt: "2026-11-24 21:09:30" },
  ];
  assert.deepEqual(klidPick(gRows, gRoutes, gumi, { s: 36.0, w: 128.2, n: 36.3, e: 128.5 }, now).map((x) => [x.no, x.routeId]), [["20-2", null], ["", null]]);
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

test("지도 버스: 이름표·방향이 바뀌면 다시 그림, 이름표는 id 순서로 정해 순서가 바뀌어도 같은 버스", () => {
  const d = { id: "sb:1", lat: 37.5, lng: 127, color: "#000", bus3d: { no: "165", caption: null, dir: 10 } };
  assert.notEqual(dotKey(d), dotKey({ ...d, bus3d: { ...d.bus3d, caption: "1580" } }));
  assert.notEqual(dotKey(d), dotKey({ ...d, bus3d: { ...d.bus3d, dir: 100 } }));
  assert.equal(dotKey(d), dotKey({ ...d, bus3d: { ...d.bus3d, dir: 12 } }));          // 15도 안은 같은 그림
  const a = { id: "sb:a", lat: 37.5, lng: 127.0, bus3d: { no: "1", caption: "A" } }, b = { id: "sb:b", lat: 37.5, lng: 127.0001, bus3d: { no: "2", caption: "B" } };
  const cap = (l) => Object.fromEntries(declutterCaptions(l, 16, 1).map((x) => [x.id, x.bus3d.caption]));
  assert.deepEqual(cap([a, b]), { "sb:a": "A", "sb:b": null });
  assert.deepEqual(cap([b, a]), { "sb:a": "A", "sb:b": null });
});

test("주변 가게 층(상가정보): 지도 가운데 50m 칸으로 묻고, 점·카드 모양", async () => {
  const seen = [];
  globalThis.fetch = async (u) => { seen.push(String(u)); return new Response(JSON.stringify({ places: [{ name: "지에스25광화문점", address: "서울 중구 세종대로 1", lat: 37.5671, lng: 126.979, category: "편의점", dist: 115 }] }), { status: 200, headers: { "Content-Type": "application/json" } }); };
  const { storesAt, storeDots, storeCard, STORE_KINDS } = await import("../site/js/stores.js");
  const b = { s: 37.5660, n: 37.5670, w: 126.9775, e: 126.9785 };
  const l1 = await storesAt("CS2", b), l2 = await storesAt("CS2", b);
  assert.equal(l1, l2);                                          // 같은 칸은 한 번만
  assert.equal(seen.filter((u) => u.includes("w/pub/store")).length, 1);
  assert.ok(seen.some((u) => /cat=CS2/.test(u) && /lat=37\.5665/.test(u) && /lng=126\.9780/.test(u)));
  const d = storeDots(l1)[0];
  assert.equal(d.label, STORE_KINDS.CS2.label); assert.ok(d.id.startsWith("st:CS2:"));
  assert.match(storeCard(l1[0]), /지에스25광화문점/);
  assert.deepEqual(await storesAt("XX9", b), []);               // 모르는 분류는 묻지 않음
});

test("초정밀 버스 지역 15곳: 지도 가운데로 지역 판단, 서울 빼기, id 가 다른 지역은 번호로", async () => {
  const { KLID_REGIONS, klidCovers, inSeoulView, klidPick, routeIdByNo } = await import("../site/js/busdata.js");
  assert.equal(KLID_REGIONS.length, 15);
  const box = (la, lo) => ({ s: la - 0.005, n: la + 0.005, w: lo - 0.005, e: lo + 0.005 });
  assert.equal(klidCovers(box(35.228, 128.681)), true);    // 창원
  assert.equal(klidCovers(box(36.351, 127.385)), false);   // 대전: 화면 안 노선마다
  assert.equal(inSeoulView(box(37.566, 126.978)), true);   // 서울시청
  assert.equal(inSeoulView(box(36.351, 127.385)), false);
  const gumi = KLID_REGIONS.find((r) => r.name === "구미");
  const rows = [{ rteId: "355000004", vhclNo: "경북70자1234", lat: "36.12", lot: "128.34", gthrDt: "2026-11-25 10:00:00" }];
  const b = { s: 36.0, n: 36.2, w: 128.2, e: 128.5 }, now = Date.parse("2026-11-25T10:01:00+09:00");
  const [x] = klidPick(rows, new Map([["355000004", { rteNo: "20-2", rteType: "지선" }]]), gumi, b, now);
  assert.equal(x.routeId, null); assert.equal(x.no, "20-2"); assert.equal(x.city, "37050");
  const cw = KLID_REGIONS.find((r) => r.name === "창원");
  assert.equal(klidPick([{ ...rows[0], rteId: "379000010", lat: "35.2", lot: "128.6" }], new Map(), cw, { s: 35, n: 35.4, w: 128.4, e: 128.8 }, now)[0].routeId, "CWB379000010");
  globalThis.fetch = async (u) => new Response(JSON.stringify(/routes_by_no/.test(String(u)) ? { 37050: { "20-2": ["GMB202-1", "GMB202-2"] } } : {}), { status: 200, headers: { "Content-Type": "application/json" } });
  assert.equal(await routeIdByNo("37050", "20-2"), "GMB202-1");
  assert.equal(await routeIdByNo("37050", "없음"), null);
});

test("경기 노선 정보 → 요약: 기점 평일 첫차·막차, 평일·토·일 배차 (1007, 2026-11-25 실제 답)", async () => {
  const { ggInfoSum, plannedTimes } = await import("../site/js/busdata.js");
  const body = { response: { msgHeader: { resultCode: 0 }, msgBody: { busRouteInfoItem: {
    routeId: 234000015, routeName: 1007, routeTypeCd: 11, routeTypeName: "직행좌석형시내버스", startStationName: "수원대학교", endStationName: "잠실광역환승센터",
    upFirstTime: "04:40", upLastTime: "22:00", downFirstTime: "06:10", downLastTime: "23:35", peekAlloc: 10, satPeekAlloc: 20, sunPeekAlloc: 0,
  } } } };
  const s = ggInfoSum(body, "GGB234000015");
  assert.deepEqual(s, { id: "GGB234000015", no: "1007", start: "수원대학교", end: "잠실광역환승센터", type: "직행좌석형시내버스", first: "04:40", last: "22:00", interval: 10, intervalSat: 20, intervalSun: null });
  const ts = plannedTimes(s, 15, 3);
  assert.equal(ts[0], 4 * 60 + 40 + 15);
  assert.equal(ts[1] - ts[0], 10);
  assert.equal(ggInfoSum({ response: { msgHeader: { resultCode: 4 } } }, "GGB1"), null);
});
