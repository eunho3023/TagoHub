import { test } from "node:test";
import assert from "node:assert/strict";

// busdata.js → core.js 가 브라우저 전역을 씀: 최소한으로 흉내
const noop = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...noop };
globalThis.window = noop;
// edits.js 가 불러올 때 거는 주기 확인(setInterval)이 테스트를 끝나지 못하게 붙잡지 않게
const realInterval = globalThis.setInterval;
globalThis.setInterval = (...a) => { const t = realInterval(...a); t.unref?.(); return t; };
const { crowdSeoul, crowdGyeonggi, arrivalParts, atOrigin, markWaiting, passed, jeonjuType, jeonjuNo, inService, kstMinute } = await import("../site/js/busdata.js");

test("혼잡·빈자리: 서울 congestion·isFullFlag, 경기 crowded·remainSeatCnt (앱 Crowd 와 같게)", () => {
  assert.equal(crowdSeoul(3, 0), "여유");
  assert.equal(crowdSeoul("6", null), "매우혼잡");
  assert.equal(crowdSeoul(5, "1"), "만차");
  assert.equal(crowdSeoul(0, 0), null);
  assert.equal(crowdGyeonggi(1, 22), "빈자리 22");
  assert.equal(crowdGyeonggi(1, 0), "여유"); // 좌석 없는 시내버스는 0
  assert.equal(crowdGyeonggi(null, -1), null);
  assert.deepEqual(arrivalParts({ arrSeconds: 420, prevStops: 3, vehicleType: "저상버스", crowd: "혼잡" }), ["7분", "3정류장 전", "저상", "혼잡"]);
  assert.deepEqual(arrivalParts({ arrSeconds: 10, prevStops: 0, crowd: "빈자리 5" }), ["정류장 도착", "빈자리 5"]);
});

test("기점에 서 있는 버스는 '정류장 도착'이 아니라 '출발 대기' (앱 OriginWait 와 같게)", () => {
  assert.equal(atOrigin("전주역", "전주역(종점)", "송천동"), true);
  assert.equal(atOrigin("전주역", "전주역", "전주역"), false); // 회차 노선은 닿는 버스일 수도 있어 그대로
  assert.equal(atOrigin("한성여객", "전주역", "송천동"), false);
  const [w, far] = markWaiting("전주역", "전주역", "송천동", [{ prevStops: 0, arrSeconds: 0 }, { prevStops: 4, arrSeconds: 500 }]);
  assert.deepEqual(arrivalParts(w), ["출발 대기"]);
  assert.equal(passed(w, 600), false);
  assert.equal(far.waiting, undefined);
});

test("전주 노선 종류: 기점과 종점이 같으면 순환 (2번 평화동종점→평화동종점, 앱 JeonjuMappers 와 같게)", () => {
  assert.equal(jeonjuType("0", "2", "평화동종점", "평화동종점"), "순환");
  assert.equal(jeonjuType("0", "104", "평화동종점", "송천동종점"), "일반");
  assert.equal(jeonjuNo("3", "1"), "3-1");   // 가지 번호 붙임
  assert.equal(jeonjuNo(3, 2), "3-2");
  assert.equal(jeonjuNo("2", "0"), "2");
  assert.equal(jeonjuNo("104", undefined), "104");
  assert.equal(jeonjuType("1", "7"), "일반");   // brtClass 1 은 가지 번호(7-1)
  assert.equal(jeonjuType("0", "6001", "비전대학교", "비전대학교"), "간선");   // 간선이 먼저
  assert.equal(jeonjuType("0", "4000"), "간선");
});

test("위치로 지나감: 3정류장 지난 버스의 낡은 '도착'은 뺌", async () => {
  const { verdict } = await import("../site/js/busdata.js");
  assert.equal(verdict(18, [21], 0), "passed");
  assert.equal(verdict(18, [21, 16], 2), "keep");
  assert.equal(verdict(18, [30], 0), "keep");
});

test("도착 없음 → 운행종료·첫차까지·다음 출발 (앱 NoLiveText 와 같음)", async () => {
  const { noLive, waitText } = await import("../site/js/busdata.js");
  assert.deepEqual(noLive("06:10", "19:30", 290, 101), { main: "운행종료", sub: "첫차 06:10 · 4시간 29분 후", ended: true });
  assert.equal(noLive("06:10", "19:30", null, 21 * 60 + 30).sub, "내일 첫차 06:10 · 8시간 40분 후");
  assert.equal(noLive("06:00", "22:00", 20, 14 * 60 + 25).main, "다음 출발 약 14:40");
  assert.equal(noLive("05:30", "00:30", null, 23 * 60 + 50).main, "막차 00:30");
  assert.equal(noLive(null, "22:00", 10, 600), null);
  assert.equal(waitText(60), "1시간");
});

test("시간표상 운행 시간 안인지 (실시간 위치가 안 오는 노선 문구, 앱 TagoTime.inService 와 같음)", () => {
  assert.equal(inService("07:40", "18:00", 17 * 60 + 58), true);    // 여수 365-5 오후 5시 58분
  assert.equal(inService("07:40", "18:00", 18 * 60 + 30), false);
  assert.equal(inService("07:40", "18:00", 7 * 60), false);
  assert.equal(inService("05:30", "00:30", 23 * 60 + 50), true);    // 막차가 자정 넘어
  assert.equal(inService("05:30", "00:30", 10), true);
  assert.equal(inService("05:30", "00:30", 4 * 60), false);
  assert.equal(inService(null, "18:00", 600), false);
  assert.equal(kstMinute(Date.UTC(2026, 10, 20, 8, 58)), 17 * 60 + 58);
});

test("하루 한 번만 다니는 노선(첫차 = 막차): 하루 1번 시각으로", async () => {
  const { noLive, planText } = await import("../site/js/busdata.js");
  assert.deepEqual(noLive("22:40", "22:40", null, 20 * 60 + 43), { main: "하루 1번 22:40", sub: "1시간 57분 후 · 기점", ended: false });
  assert.deepEqual(noLive("06:00", "06:00", null, 7 * 60), { main: "출발함", sub: "내일 06:00 · 하루 1번 · 23시간 후", ended: false });
  assert.equal(noLive("06:00", "06:00", null, 20 * 60 + 43).main, "운행종료");
  assert.equal(planText({ first: "06:00", last: "06:00", interval: 15 }, 1), "하루 1번 06:00");
  assert.equal(planText({ first: "06:00", last: "22:20", interval: 15 }, 1), "첫차 06:00 · 막차 22:20 · 배차 15분");
});

test("차 종류: 서울 busType·경기 lowPlate (모르는 값은 null)", async () => {
  const { seoulKind, ggKind } = await import("../site/js/busdata.js");
  assert.equal(seoulKind("1"), "저상");
  assert.equal(seoulKind("0"), "일반");
  assert.equal(seoulKind("2"), "굴절");
  assert.equal(seoulKind(undefined), null);
  assert.equal(ggKind(1), "저상");
  assert.equal(ggKind(0), "일반");
  assert.equal(ggKind(2), "2층");
  assert.equal(ggKind(9), null);
});

test("실시간 버스 없는 줄의 다음 시간표 시각: 공식 시간표·배차 간격만, 첫차·막차뿐이면 없음 (전주 풍남중 74번)", async () => {
  const { nextPlanned, clockText } = await import("../site/js/busdata.js");
  // 공식 배차시간표: 기점 07:30·08:10·22:00 + 이 정류장까지 10분 → 07:01 이면 다음 07:40 (39분)
  const deps = { sum: { first: "07:30", last: "22:00", deps: { weekday: [450, 490, 1320] } }, offset: 10, downOffset: null };
  assert.deepEqual(nextPlanned(deps, 3, 421), { at: 460, wait: 39 });
  assert.equal(clockText(460), "07:40");
  assert.equal(nextPlanned(deps, 3, 1331), null);   // 오늘 남은 것 없음
  // 첫차·막차만 (배차 모름): 아침에 "막차"를 다음 차처럼 보이지 않게 null
  assert.equal(nextPlanned({ sum: { first: "06:00", last: "22:25" }, offset: 0 }, 3, 421), null);
  // 배차 간격을 알면 계산한 시각
  assert.deepEqual(nextPlanned({ sum: { first: "06:00", last: "22:00", interval: 20 }, offset: 5 }, 3, 421), { at: 425, wait: 4 });
});

test("속초 배차시간표는 서버에 묻지 않고 배포 때 만든 자료 조각(data/times/<지역>.json)에서", async () => {
  const { routeDeps } = await import("../site/js/busdata.js");
  const asked = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    asked.push(String(url));
    const body = /data\/times\/sokcho\.json/.test(url) ? { "43101003": { weekday: [380, 440], sat: [380], holi: [380], source: "속초시 버스정보시스템 배차시간표" } }
      : /data\/manifest\.json/.test(url) ? { built: 1 } : {};
    return { ok: true, status: 200, text: async () => JSON.stringify(body) };
  };
  try {
    const d = await routeDeps("SKB43101003", "7");
    assert.deepEqual(d && d.weekday, [380, 440]);
    assert.equal(asked.some((u) => u.includes("w/bus/time/")), false);
  } finally { globalThis.fetch = realFetch; }
});

test("TAGO 버스 위치 줄: 동시에 max 개까지, 급한 것(노선 화면)이 지도 배경 노선들보다 먼저", async () => {
  const { pacer } = await import("../site/js/busdata.js");
  let t = 0;
  const go = pacer(2, 250, () => t, async (ms) => { t += ms; });
  const order = [];
  let inFlight = 0, most = 0;
  const job = (name) => async () => {
    inFlight++; most = Math.max(most, inFlight); order.push([name, t]);
    await new Promise((ok) => setTimeout(ok, 5));
    inFlight--;
    return name;
  };
  const all = [];
  for (let i = 0; i < 6; i++) all.push(go(job("bg" + i)));
  all.push(go(job("route"), true));
  assert.deepEqual(await Promise.all(all), ["bg0", "bg1", "bg2", "bg3", "bg4", "bg5", "route"]);
  assert.equal(most <= 2, true);
  // 첫 것은 바로 보냄, 그다음은 기다리던 배경 노선 다섯보다 노선 화면이 먼저
  assert.equal(order.findIndex((o) => o[0] === "route"), 1);
  // 시작 사이는 250ms 넘게
  for (let i = 1; i < order.length; i++) assert.equal(order[i][1] - order[i - 1][1] >= 250, true);
});

test("제천처럼 exact 시간표는 빈 요일 칸이 운행 없음 (평일 시각으로 채우지 않음), 아니면 예전처럼 채움", async () => {
  const { plannedTimes, todayDeps } = await import("../site/js/busdata.js");
  const loose = { weekday: [445, 790], sat: [], holi: [] };
  const exact = { ...loose, exact: true };
  assert.deepEqual(plannedTimes({ deps: loose }, 5, 7), [450, 795]);   // 일요일: 평일 시각으로 채움 (예전과 같음)
  assert.deepEqual(plannedTimes({ deps: exact }, 5, 7), []);          // 일요일 운행 없음
  assert.deepEqual(plannedTimes({ deps: exact }, 5, 3), [450, 795]);
  assert.deepEqual(todayDeps(loose, 0), [445, 790]);
  assert.deepEqual(todayDeps(exact, 0), []);
  assert.deepEqual(todayDeps(exact, 6), []);
});

test("전주 버스 위치는 전주시 버스정보 응답에서 버스가 있는 정류장 줄만 (busNo 뒤 빈칸·저상 busLow, 165번 2026-11-26)", async () => {
  const { jeonjuBusRows } = await import("../site/js/busdata.js");
  const row = (seq, std, name, bus, low) => `<list class="object"><BNo type="string"/><bnodeId type="string">${std}</bnodeId><brsSeqno type="string">${seq}</brsSeqno>` +
    `<busLow type="string">${low}</busLow><busNo type="string">${bus}</busNo><reMark type="string"/><stopKname type="string">${name}</stopKname><stopStandardid type="string">${std}</stopStandardid></list>`;
  const xml = `<?xml version="1.0"?><RFC30><code type="string">000</code><msg type="string">OK</msg><routeList class="array">` +
    row(19, "305032360", "한국국토정보공사", "", "") + row(20, "306032156", "국민연금공단", "1594        ", "0") + row(4, "312032125", "혁신금호어울림아파트", "1737", "1") + `</routeList></RFC30>`;
  assert.deepEqual(jeonjuBusRows(xml), [
    { nodeId: "JUB306032156", ord: 20, vehicleNo: "1594", kind: "일반", name: "국민연금공단" },
    { nodeId: "JUB312032125", ord: 4, vehicleNo: "1737", kind: "저상", name: "혁신금호어울림아파트" },
  ]);
  assert.equal(jeonjuBusRows(`<RFC30><code type="string">999</code></RFC30>`), null);   // 오류 → TAGO 로
  assert.deepEqual(jeonjuBusRows(`<RFC30><code type="string">000</code><routeList class="array"></routeList></RFC30>`), []);   // 다니는 버스 없음
});

test("부산 버스 위치는 부산 BIMS 응답에서 차가 있는 줄만 (GPS·자료 시각·저상, 1001번 2026-11-26)", async () => {
  const { busanBusRows } = await import("../site/js/busdata.js");
  const item = (idx, node, car, extra) => `<item><bstopidx>${idx}</bstopidx><bstopnm>x</bstopnm><nodeid>${node}</nodeid><lineno>1001</lineno>${car ? `<carno>${car}</carno>` : ""}${extra || ""}</item>`;
  const xml = `<?xml version="1.0" encoding="utf-8" ?><response><header><resultCode>00</resultCode></header><body><items>` +
    item(1, "517010000", "") + item(10, "186190401", "71자2617", "<gpsym>224158</gpsym><lat>35.1780672</lat><lin>129.1748480</lin><lowplate>0</lowplate>") +
    item(12, "186220301", "71자2700", "<gpsym>224150</gpsym><lat>35.17</lat><lin>129.18</lin><lowplate>1</lowplate>") + `</items></body></response>`;
  const now = Date.UTC(2026, 10, 26, 13, 42, 10);   // 한국 22:42:10
  assert.deepEqual(busanBusRows(xml, now), [
    { nodeId: "BSB186190401", ord: 10, vehicleNo: "71자2617", lat: 35.1780672, lng: 129.174848, dataAt: Date.UTC(2026, 10, 26, 13, 41, 58), kind: "일반" },
    { nodeId: "BSB186220301", ord: 12, vehicleNo: "71자2700", lat: 35.17, lng: 129.18, dataAt: Date.UTC(2026, 10, 26, 13, 41, 50), kind: "저상" },
  ]);
  // 자정 막 지나 받은 어제 23:59 자료
  const mid = busanBusRows(xml.replace("224158", "235950"), Date.UTC(2026, 10, 26, 15, 0, 10));
  assert.equal(mid[0].dataAt, Date.UTC(2026, 10, 26, 14, 59, 50));
  assert.equal(busanBusRows(`<response><header><resultCode>99</resultCode></header></response>`), null);   // 오류 → TAGO 로
});

test("광주 버스 위치는 광주 BIS 응답에서 (정류장 id KJB+CURR_STOP_ID, 저상 LOW_BUS, 518번 2026-11-26)", async () => {
  const { gwangjuBusRows } = await import("../site/js/busdata.js");
  const ok = (items) => JSON.stringify({ RESPONSE: { RESULT: { RESULT_CODE: "SUCCESS" }, BUSLOCATION_LIST: { ITEM: items }, ROW_COUNT: 2 } });
  assert.deepEqual(gwangjuBusRows(ok([{ LINE_ID: 238, CURR_STOP_ID: 155, CARNO: "광주77바2300", LOW_BUS: "0", SEQ: 52 }, { LINE_ID: 238, CURR_STOP_ID: 1193, CARNO: "광주77바2393", LOW_BUS: "1", SEQ: 104 }])), [
    { nodeId: "KJB155", vehicleNo: "광주77바2300", kind: "일반" },
    { nodeId: "KJB1193", vehicleNo: "광주77바2393", kind: "저상" },
  ]);
  assert.deepEqual(gwangjuBusRows(ok({ CURR_STOP_ID: 7, CARNO: "광주1", LOW_BUS: "1" })), [{ nodeId: "KJB7", vehicleNo: "광주1", kind: "저상" }]);   // 한 대면 배열이 아닐 수 있음
  assert.deepEqual(gwangjuBusRows(ok([])), []);
  assert.equal(gwangjuBusRows(`<OpenAPI_ServiceResponse><returnReasonCode>04</returnReasonCode></OpenAPI_ServiceResponse>`), null);   // 오류 → TAGO 로
});

test("완주 고산북부권은 시간표를 옮기지 않고 원본(완주군시설관리공단) 링크만 (공공누리 제4유형, 2026-11-27)", async () => {
  const { outsideTimes, routeDeps } = await import("../site/js/busdata.js");
  const at = outsideTimes(36.09, 127.28);   // 운주터미널
  assert.equal(at.length, 1);
  assert.ok(at[0].url.startsWith("https://www.wjss.or.kr/") && at[0].label.includes("완주"));
  assert.deepEqual(outsideTimes(35.82, 127.15), []);   // 전주 시내
  assert.deepEqual(outsideTimes(null, null), []);
  // WNB 노선 시간표 조각(data/times/wanju.json)은 더 묻지 않음
  const asked = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => { asked.push(String(url)); return { ok: true, status: 200, text: async () => "{}" }; };
  try { await routeDeps("WNB001", "고산-운주").catch(() => null); } finally { globalThis.fetch = realFetch; }
  assert.equal(asked.some((u) => u.includes("data/times/wanju")), false);
});

test("교통안전공단 노선(TSB)도 TAGO 도시 코드가 있으면 버스 위치를 물음 (구례 6-1번, 2026-11-27)", async () => {
  const { positions } = await import("../site/js/busdata.js");
  const asked = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    asked.push(String(url));
    const body = `<response><header><resultCode>00</resultCode></header><body><items><item><gpslati>35.26076</gpslati><gpslong>127.46027</gpslong>` +
      `<nodeid>TSB331000211</nodeid><nodenm>방광</nodenm><nodeord>13</nodeord><routenm>6-1</routenm><vehicleno>전남72아7010</vehicleno></item></items></body></response>`;
    return { ok: true, status: 200, headers: { get: () => null }, text: async () => body };
  };
  let p;
  try { p = await positions({ id: "TSB331000021", city: "36330" }); } finally { globalThis.fetch = realFetch; }
  assert.ok(asked.some((u) => u.includes("getRouteAcctoBusLcList") && u.includes("TSB331000021")));
  assert.equal(p.state === "unsupported", false);
  // 도시 코드가 숫자가 아닌 교통안전공단 노선(TAGO 에 없는 곳)은 예전처럼 묻지 않음
  assert.equal((await positions({ id: "TSB312000001", city: "TS312" })).state, "unsupported");
});

test("종점 내리는 곳: 이 정류장 id 가 노선 맨 끝에만 있으면 기점 출발 대신 '종점 · 내리는 곳' (구례공영버스터미널 3310054, 2026-11-27)", async () => {
  const { alightOnlyAt } = await import("../site/js/busdata.js");
  const stops = [{ id: "TSB331000262", name: "구례공영버스터미널" }, { id: "TSB331000100", name: "하나로마트" }, { id: "TSB331000054", name: "구례공영버스터미널" }];
  assert.equal(alightOnlyAt(stops, "TSB331000054"), true);    // 내리는 곳
  assert.equal(alightOnlyAt(stops, "TSB331000262"), false);   // 타는 곳(기점)
  assert.equal(alightOnlyAt(stops, "TSB331000100"), false);   // 가운데
  // 같은 정류장이 처음과 끝에 다 있는 순환 노선은 타는 곳이기도 함
  assert.equal(alightOnlyAt([{ id: "A" }, { id: "B" }, { id: "A" }], "A"), false);
  assert.equal(alightOnlyAt([{ id: "A" }], "A"), false);
  assert.equal(alightOnlyAt(stops, "ZZZ"), false);
});
