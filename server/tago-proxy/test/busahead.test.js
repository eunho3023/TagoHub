import { test } from "node:test";
import assert from "node:assert/strict";
import * as A from "../site/js/busahead.js";

const o = [37.5, 127.0];
const north = (m) => A.move(o, 0, m);

test("마지막 두 위치로 속도, 멈춤·튐은 0", () => {
  assert.ok(Math.abs(A.speed(o, north(140), 14000) - 10) < 0.01);
  assert.equal(A.speed(null, north(140), 14000), 0);
  assert.equal(A.speed(o, north(5), 14000), 0);
  assert.equal(A.speed(o, north(1000), 10000), 0);
});

test("자료 나이 + 받은 뒤 시간만큼 앞으로, 상한·앞 정류장·옛 자료", () => {
  assert.equal(A.aheadM(10, 0, 20000, 0), 100); // 늦은 시간의 절반만
  assert.equal(A.aheadM(10, 0, 20000, 5000), 125);
  assert.equal(A.aheadM(18, 0, 30000, 5000), 250);
  assert.equal(A.aheadM(10, 0, 20000, 5000, 80), 80);
  assert.equal(A.aheadM(10, 0, 70000, 0), 0);
  assert.equal(A.aheadM(5, 0, A.UNKNOWN_AGE_MS, 90000), 100); // 40초치까지만
});

test("앞 정류장: 진행 방향 앞, 선에서 35m 안", () => {
  const stops = [north(5), north(120), A.move(north(60), 90, 80), north(-50), north(200)];
  assert.ok(Math.abs(A.stopAhead(o, 0, stops) - 120) < 0.5);
  assert.equal(A.stopAhead(o, 180, [north(120)]), null);
});

test("기록: 서울은 자료 시각으로 멈춤을 알고, TAGO 는 그대로 와도 멈춤으로 보지 않음", () => {
  const t0 = 1e6;
  let t = A.update(null, o, t0, t0 - 20000);
  assert.equal(t.age, 20000);
  t = A.update(t, north(140), t0 + 10000, t0 - 6000);
  assert.ok(Math.abs(t.v - 10) < 0.01);
  assert.ok(Math.abs(t.deg) < 0.01);
  // 지금 그릴 자리: (받을 때 16초 전 + 받은 뒤 4초) × 10m/s × 0.5 = 100m 앞 (앞 정류장 50m 면 거기까지)
  assert.ok(Math.abs(A.meters(A.predict(t, [], t0 + 14000), north(140)) - 100) < 1);
  assert.ok(Math.abs(A.meters(A.predict(t, [north(190)], t0 + 14000), north(140)) - 50) < 1);
  assert.equal(A.update(t, north(141), t0 + 20000, t0 - 6000), t); // 자료 시각 그대로 = 안 바뀜
  const stopped = A.update(t, north(141), t0 + 20000, t0 + 8000);
  assert.equal(stopped.still, true);
  assert.deepEqual(A.predict(stopped, [], t0 + 25000), north(140)); // 멈추면 받은 자리
  let u = A.update(null, o, t0);
  u = A.update(u, north(100), t0 + 10000);
  const same = A.update(u, north(101), t0 + 20000);
  assert.equal(same, u);
  assert.equal(same.still, false);
});

test("서울 dataTm", () => {
  assert.equal(A.seoulTime("20261116201530"), Date.parse("2026-11-16T11:15:30Z"));
  assert.equal(A.seoulTime("x"), null);
  assert.equal(A.seoulTime(undefined), null);
});
