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
