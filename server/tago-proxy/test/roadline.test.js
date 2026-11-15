import { test } from "node:test";
import assert from "node:assert/strict";
import { despike, roundCorners, tidyRoad } from "../site/js/roadline.js";

// 큰길: 서쪽에서 동쪽으로 (a → b → c)
const a = [35.80, 127.10], b = [35.80, 127.1010], c = [35.80, 127.1020], e = [35.80, 127.1035];
const l1 = [35.8003, 127.1010], l2 = [35.8003, 127.1013], l3 = [35.8000, 127.1013];

test("골목 들르기: 같은 자리로 나와 가던 방향으로 계속 가면 뺌 (앱 Shapes.despike 와 같음)", () => {
  const tip = [35.8004, 127.1010]; // 큰길에서 북쪽으로 44m
  assert.deepEqual(despike([a, b, tip, b, c]), [a, b, c]);
  assert.deepEqual(despike([a, b, l1, l2, l3, b, c]), [a, b, c]);
  // 교차로에서 꺾여 나가도(들어온 방향과 90도) 회차가 아님 → 뺌
  const south = [35.7990, 127.1010];
  assert.deepEqual(despike([a, b, l1, l2, l3, b, south]), [a, b, south]);
});

test("회차: 고리를 돌거나 막다른 길 끝까지 갔다가 왔던 쪽으로 되돌아가면 남김", () => {
  const back = [35.79995, 127.1000];
  const loop = [a, b, l1, l2, l3, b, back];
  assert.deepEqual(despike(loop), loop);
  const stub = [a, b, [35.8004, 127.1010], b, back];
  assert.deepEqual(despike(stub), stub);
});

test("회전교차로를 도는 길은 남김", () => {
  // b 동쪽 15m 를 중심으로 반지름 15m: 동쪽으로 오다가 b(서쪽 끝)에서 들어가 시계 반대 방향으로 270도 돌고 북쪽으로 나감 (좌회전)
  const k = 111320 * Math.cos((35.8 * Math.PI) / 180), cLng = 127.1010 + 15 / k;
  const ring = [];
  for (let d = 180; d <= 450; d += 15) {
    const t = (d * Math.PI) / 180;
    ring.push([35.80 + (15 * Math.sin(t)) / 110540, cLng + (15 * Math.cos(t)) / k]);
  }
  const exit = ring[ring.length - 1], north = [exit[0] + 0.0004, exit[1]];
  const path = [a, ...ring, north];
  assert.deepEqual(despike(path), path);
});

test("큰길 옆에 찍힌 정류장 때문에 옆길로 빠졌다 들어온 우회는 곧게, 정류장 없는 굽은 길·터미널 들르기는 그대로", () => {
  const s1 = [35.8005, 127.1012], s2 = [35.8005, 127.1020], back = [35.80, 127.1022];
  const path = [a, b, s1, s2, back, e];
  const stray = [35.8004, 127.1015, "풍남문"];
  assert.deepEqual(despike(path, [stray]), [a, b, back, e]);
  assert.deepEqual(despike(path), path);
  assert.deepEqual(despike(path, [[35.8004, 127.1015, "강진터미널"]]), path);
  // 같은 자리로 돌아오는 고리도 터미널을 들르면 그대로
  const loop = [a, b, l1, l2, l3, b, c];
  assert.deepEqual(despike(loop, [[35.8003, 127.1011, "임실터미널"]]), loop);
});

test("꺾이는 곳은 모서리 15m 안에서 둥글게, 곧은 길은 그대로", () => {
  const corner = [35.8010, 127.1010];
  const r = roundCorners([a, b, corner]);
  assert.ok(r.length > 3);
  assert.deepEqual(r[0], a);
  assert.deepEqual(r[r.length - 1], corner);
  const m = (p, q) => Math.hypot((q[0] - p[0]) * 110540, (q[1] - p[1]) * 111320 * Math.cos((p[0] * Math.PI) / 180));
  for (const p of r.slice(1, -1)) assert.ok(m(p, b) <= 15.01);
  assert.deepEqual(roundCorners([a, b, c]), [a, b, c]);
  assert.deepEqual(tidyRoad([a, c]), [a, c]);
});

test("노선 정류장 차례가 되돌아가는 곳(길 건너 같은 정류장에 이어 섬)은 꺾어 나가도 회차로 남김 (기린대로 병무청)", () => {
  const south = [35.7990, 127.1010];
  const path = [a, b, l1, l2, l3, b, south];
  assert.deepEqual(despike(path), [a, b, south]);   // 정류장을 모르면 꺾어 나가는 고리는 뺌
  const pair = [[35.8003, 127.10105, "기린대로 병무청"], [35.8003, 127.10125, "기린대로 병무청"]];   // 약 18m 마주 봄
  assert.deepEqual(despike(path, [[35.79, 127.09, "앞"], ...pair, [35.79, 127.11, "뒤"]]), path);
});
