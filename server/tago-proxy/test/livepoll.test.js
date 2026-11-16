import { test } from "node:test";
import assert from "node:assert/strict";

// core.js 는 브라우저 전역(location·document·window)을 씀: 최소한으로 흉내
const listeners = { doc: {}, win: {} };
const target = (bag) => ({
  addEventListener: (t, f) => (bag[t] ??= new Set()).add(f),
  removeEventListener: (t, f) => bag[t]?.delete(f),
  fire: (t) => [...(bag[t] || [])].forEach((f) => f()),
  count: (t) => bag[t]?.size || 0,
});
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...target(listeners.doc) };
globalThis.window = target(listeners.win);
const { livePoll, onReturn } = await import("../site/js/core.js");
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test("livePoll: 실패하면 2·4·6초 간격(여기선 20·40·60ms)으로 다시, 마지막 시도에만 lastTry", async () => {
  const calls = [];
  const p = livePoll(async ({ lastTry }) => { calls.push(lastTry); return false; }, 100000, { stepMs: 20 });
  await wait(200);
  p.stop();
  assert.deepEqual(calls, [false, false, false, true]); // 처음 + 다시 3번, 넷째가 마지막
  assert.equal(listeners.doc.visibilitychange?.size || 0, 0); // stop 하면 듣기도 끝
});

test("livePoll: 돌아오면(보이게 됨) 바로 다시, 성공 직후(5초 안)는 건너뜀", async () => {
  let n = 0;
  const p = livePoll(async () => { n++; return true; }, 100000, { minGapMs: 30 });
  await wait(5);
  assert.equal(n, 1);
  document.fire("visibilitychange"); // 방금 받았으니 건너뜀
  await wait(5);
  assert.equal(n, 1);
  await wait(40);
  document.fire("visibilitychange");
  await wait(5);
  assert.equal(n, 2);
  window.fire("online");
  await wait(40);
  window.fire("online");
  await wait(5);
  assert.equal(n, 3);
  p.stop();
});

test("livePoll: 가려져 있으면 주기 실행을 건너뜀", async () => {
  let n = 0;
  const p = livePoll(async () => { n++; return true; }, 15);
  await wait(5);
  document.hidden = true;
  const before = n;
  await wait(60);
  assert.equal(n, before);
  document.hidden = false;
  await wait(40);
  assert.ok(n > before);
  p.stop();
});

test("onReturn: 보이게 되거나 인터넷이 켜지면, 가려진 채면 안 부름, 해제하면 끝", () => {
  let n = 0;
  const off = onReturn(() => n++);
  document.fire("visibilitychange");
  window.fire("pageshow");
  window.fire("online");
  assert.equal(n, 3);
  document.hidden = true;
  document.fire("visibilitychange");
  assert.equal(n, 3);
  document.hidden = false;
  off();
  window.fire("online");
  assert.equal(n, 3);
});
