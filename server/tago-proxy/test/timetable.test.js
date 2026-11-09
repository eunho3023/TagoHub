import { test } from "node:test";
import assert from "node:assert/strict";
import { clockList, parseJeonjuTime, handleJeonjuTime } from "../src/timetable.js";

test("시각 목록: 앞뒤 공백·이상한 값 정리", () => {
  assert.deepEqual(clockList(" 06:00, 06:15,  ,25:99, 06:00"), [360, 375]);
});

test("전주 배차시간표: 토요일·일요일에 안 다니는 시각을 빼서 요일별로", () => {
  const t = parseJeonjuTime({ result: { COURSE_STIMELIST: " 06:00, 06:15, 06:30", SAT_NLIST: " 06:15", HOLI_NLIST: null, BRT_TEXT: "06:05 전북대농협앞 출발" } });
  assert.deepEqual(t.weekday, [360, 375, 390]);
  assert.deepEqual(t.sat, [360, 390]);
  assert.deepEqual(t.holi, [360, 375, 390]);
  assert.equal(t.note, "06:05 전북대농협앞 출발");
  assert.equal(parseJeonjuTime({ result: {} }), null);
});

test("전주 시간표 주소: 9자리만, 받은 것은 하루 저장", async () => {
  const store = new Map();
  globalThis.caches = { default: { match: async (k) => (store.has(k.url) ? new Response(store.get(k.url)) : undefined), put: async (k, r) => { store.set(k.url, await r.text()); } } };
  const waits = [];
  const ctx = { waitUntil: (p) => waits.push(p) };
  assert.equal((await handleJeonjuTime("12", ctx)).status, 400);
  let calls = 0;
  const fetcher = async (u, o) => { calls++; assert.match(o.body, /routeId=305001764/); return new Response(JSON.stringify({ result: { COURSE_STIMELIST: "06:00, 06:15" } })); };
  const a = await (await handleJeonjuTime("305001764", ctx, fetcher)).json();
  await Promise.all(waits);
  assert.deepEqual(a.weekday, [360, 375]);
  await handleJeonjuTime("305001764", ctx, fetcher);
  assert.equal(calls, 1);
});

import { parseCheonanHtml, parseChangwon, parseUlsan, handleBusTime } from "../src/timetable.js";
test("천안 시간표 화면: 평일·주말·공휴일 칸을 나눠 읽음", () => {
  const html = "<dl><dt>평일 출발시간</dt><dd>06:00</dd><dd>06:20</dd></dl><dl><dt>주말 출발시간</dt><dd>07:00</dd></dl><dl><dt>공휴일 출발시간</dt></dl>";
  const t = parseCheonanHtml(html);
  assert.deepEqual([t.weekday, t.sat, t.holi], [[360, 380], [420], [420]]);
  assert.equal(parseCheonanHtml("<html></html>"), null);
});
test("창원: 기점(0)·종점(1) 출발을 따로", () => {
  const d = { rows: [{ updnSe: "0", startTime: "05:40" }, { updnSe: "1", startTime: "06:10" }] };
  const t = parseChangwon([d, d, { rows: [] }]);
  assert.deepEqual([t.weekday, t.weekdayDown, t.holi], [[340], [370], []]);
});
test("울산: 기점·순환 칸, 괄호 붙은 시각도 읽음", () => {
  const j = { rows: [{ fiducialInfo: [{ bttStarttime: "0620" }, { bttStarttime: "0650(경유)" }], circularInfo: [{ bttStarttime: "0530" }] }] };
  assert.deepEqual(parseUlsan(j, false), [380, 410]);
  assert.deepEqual(parseUlsan(j, true), [330]);
});
test("지역 시간표 주소: 모르는 지역·형식은 400", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const ctx = { waitUntil() {} };
  assert.equal((await handleBusTime("seoul", "123456789", ctx)).status, 400);
  assert.equal((await handleBusTime("ulsan", "12", ctx)).status, 400);
});
